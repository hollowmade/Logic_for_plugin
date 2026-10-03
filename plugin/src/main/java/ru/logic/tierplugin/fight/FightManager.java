package ru.logic.tierplugin.fight;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.EndReason;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.text.Text;
import ru.logic.tierplugin.tracker.ActiveFights;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the lifecycle of fights: start, end with a winner, cancel, and disconnects.
 * Implements {@link ActiveFights} so the metrics tracker can attach telemetry
 * without knowing how fights are started (admin command now, duels later).
 * <p>
 * Disconnect: the fight pauses for {@code reconnect_grace_seconds}. If the player
 * rejoins in time the fight continues; otherwise it ends as a LEAVE (see RatingService).
 * If both players are gone, the fight is cancelled unrated.
 */
public class FightManager implements ActiveFights {

    private final LogicTierPlugin plugin;
    private final FightSettings settings;

    /** Active fights keyed by each participant's UUID. */
    private final Map<UUID, Fight> active = new ConcurrentHashMap<>();

    /** Messages for players who were offline when their fight was decided. */
    private final Map<UUID, List<Component>> pendingNotices = new ConcurrentHashMap<>();

    public FightManager(LogicTierPlugin plugin, FightSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public Optional<FightRef> fightOf(UUID player) {
        return getFight(player).map(Fight::ref);
    }

    public Optional<Fight> getFight(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean isInFight(UUID player) {
        return active.containsKey(player);
    }

    /**
     * Starts a fight between two players.
     *
     * @return false if it is the same player or either is already fighting
     */
    public boolean startFight(UUID a, UUID b, Gamemode gamemode) {
        if (a.equals(b) || isInFight(a) || isInFight(b)) return false;
        Fight f = new Fight(a, b, gamemode, System.currentTimeMillis());
        active.put(a, f);
        active.put(b, f);
        return true;
    }

    /** Records a kill of the opponent and ends the fight with {@code killer} as the winner. */
    public void killed(UUID killer) {
        getFight(killer).ifPresent(f -> {
            f.recordKill(killer);
            endFight(killer, EndReason.KILL);
        });
    }

    /** Drops the fight of {@code participant} without touching any rating. */
    public void cancelFight(UUID participant) {
        Fight f = remove(participant);
        if (f != null) plugin.getMetricsTracker().discard(f.ref().id());
    }

    // ── Disconnects ────────────────────────────────────────────

    /** A participant left the server: pause and give them time to come back. */
    public void playerQuit(UUID player) {
        Fight f = active.get(player);
        if (f == null) return;
        UUID opponent = f.ref().opponentOf(player);

        if (f.disconnected() != null) {
            // The opponent is already gone too: nobody to award the win to
            cancelFight(player);
            plugin.getLogger().info("Оба игрока покинули бой — бой отменён без рейтинга.");
            return;
        }

        int grace = settings.reconnectGraceSeconds();
        f.markDisconnected(player, System.currentTimeMillis(),
                Bukkit.getScheduler().runTaskLater(plugin, () -> graceExpired(f, player), grace * 20L));
        notify(opponent, Text.parse(Text.PREFIX + "<yellow>Соперник отключился.</yellow> <gray>Ждём <white>"
                + grace + "</white> сек. — если не вернётся, победа засчитается тебе.</gray>"));
    }

    /** A player joined: resume their paused fight and deliver messages they missed. */
    public void playerJoin(Player player) {
        UUID id = player.getUniqueId();
        List<Component> notices = pendingNotices.remove(id);
        if (notices != null) notices.forEach(player::sendMessage);

        Fight f = active.get(id);
        if (f != null && id.equals(f.disconnected())) {
            f.clearDisconnect();
            Text.send(player, "<green>Ты вернулся — бой продолжается.</green>");
            notify(f.ref().opponentOf(id), Text.parse(Text.PREFIX + "<green>Соперник вернулся — бой продолжается.</green>"));
        }
    }

    private void graceExpired(Fight f, UUID leaver) {
        if (active.get(leaver) != f || !leaver.equals(f.disconnected())) return; // resolved meanwhile
        UUID opponent = f.ref().opponentOf(leaver);
        if (Bukkit.getPlayer(opponent) == null) {
            cancelFight(leaver);
            return;
        }
        endFight(opponent, EndReason.LEAVE);
    }

    // ── Ending ─────────────────────────────────────────────────

    /** Ends the fight with {@code winner} as the winner and submits it for rating. */
    private void endFight(UUID winner, EndReason reason) {
        Fight f = active.get(winner);
        if (f == null) return;
        long endedAt = reason == EndReason.LEAVE ? f.disconnectedAt() : System.currentTimeMillis();
        remove(winner);

        UUID loser = f.ref().opponentOf(winner);
        Map<UUID, FightMetrics> metrics = plugin.getMetricsTracker().finish(f.ref());
        MatchInput in = new MatchInput(f.gamemode(),
                f.killsOf(winner), f.killsOf(loser),
                metrics.get(winner), metrics.get(loser),
                endedAt - f.startedAt(), reason);

        plugin.getStorage().recordMatch(winner, loser, in, plugin.getRatingService())
                .whenComplete((out, err) -> plugin.sync(() -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить матч", err);
                        Component msg = Text.parse(Text.PREFIX + "<#FF6B6B>Не удалось сохранить матч — рейтинг не изменён.");
                        notify(winner, msg);
                        notifyOrQueue(loser, msg);
                        return;
                    }
                    if (out.isLeave()) {
                        reportLeaveWinner(winner, f.gamemode(), out, in.winnerMetrics());
                        reportLeaver(loser, f.gamemode(), out);
                    } else {
                        report(winner, f.gamemode(), out, out.winner(), in.winnerMetrics(), true);
                        report(loser, f.gamemode(), out, out.loser(), in.loserMetrics(), false);
                    }
                }));
    }

    public void shutdown() {
        active.values().forEach(Fight::clearDisconnect);
        active.clear();
    }

    private Fight remove(UUID participant) {
        Fight f = active.get(participant);
        if (f == null) return null;
        f.clearDisconnect();
        active.remove(f.ref().playerA(), f);
        active.remove(f.ref().playerB(), f);
        return f;
    }

    // ── Messages ───────────────────────────────────────────────

    private void report(UUID uuid, Gamemode gm, MatchOutcome out, MatchOutcome.Side side,
                        FightMetrics m, boolean won) {
        List<Component> card = new ArrayList<>();
        card.add(title(won ? "<green><bold>⚔ ПОБЕДА</bold></green>" : "<red><bold>☠ ПОРАЖЕНИЕ</bold></red>", gm, null));
        if (out.rated()) {
            card.add(eloLine(side, won ? out.weight() : out.loserWeight(), false));
        } else {
            card.add(Text.parse(" <gray>Бой без рейтинга: это уже <white>" + out.matchNumberToday()
                    + "</white>-й бой с этим соперником сегодня.</gray>"));
        }
        card.add(tierLine(side));
        card.add(Text.parse(" ").append(Text.metrics(m)));
        notifyCard(uuid, card, false);
    }

    private void reportLeaveWinner(UUID uuid, Gamemode gm, MatchOutcome out, FightMetrics m) {
        List<Component> card = new ArrayList<>();
        card.add(title("<green><bold>⚔ ПОБЕДА</bold></green>", gm, "соперник покинул бой"));
        if (out.rated()) {
            card.add(eloLine(out.winner(), out.weight(), false));
            card.add(tierLine(out.winner()));
        } else {
            card.add(Text.parse(" <gray>Бой толком не начался (ни одного удара, меньше "
                    + plugin.getRatingService().getConfig().leave().realFightMillis() / 1000
                    + " сек.) — рейтинг не начислен.</gray>"));
        }
        card.add(Text.parse(" ").append(Text.metrics(m)));
        notifyCard(uuid, card, false);
    }

    /** The leaver is usually offline: the card waits for their next join. */
    private void reportLeaver(UUID uuid, Gamemode gm, MatchOutcome out) {
        var leave = plugin.getRatingService().getConfig().leave();
        List<Component> card = new ArrayList<>();
        card.add(title("<red><bold>✖ ТЕХНИЧЕСКОЕ ПОРАЖЕНИЕ</bold></red>", gm, null));
        card.add(Text.parse(" <gray>Ты покинул бой. Выход <white>№" + out.leaveNumber()
                + "</white> за " + leave.windowHours() + " ч.</gray>"));
        card.add(eloLine(out.loser(), out.loserWeight(), true));
        card.add(tierLine(out.loser()));
        if (leave.cooldownAfterLeaves() > 0 && out.leaveNumber() >= leave.cooldownAfterLeaves()) {
            card.add(Text.parse(" <#FF6B6B>Слишком много выходов — " + leave.cooldownMinutes()
                    + " мин. нельзя начинать бои.</#FF6B6B>"));
        }
        notifyCard(uuid, card, true);
    }

    private static Component title(String head, Gamemode gm, String note) {
        return Text.parse(" " + head + Text.DOT + "<gray>" + Text.mode(gm) + "</gray>"
                + (note != null ? Text.DOT + "<gray>" + note + "</gray>" : ""));
    }

    /** @param penalty show the weight as a leave penalty multiplier instead of a percentage */
    private static Component eloLine(MatchOutcome.Side side, double weight, boolean penalty) {
        String w = Math.abs(weight - 1) < 0.001 ? ""
                : penalty ? " <dark_gray>штраф ×" + Text.num(weight, 1) + "</dark_gray>"
                : " <dark_gray>вес " + Text.percent(weight) + "</dark_gray>";
        return Text.parse(" <gray>Рейтинг</gray> <white>" + Math.round(side.eloBefore()) + "</white>" + Text.ARROW
                + "<white>" + Math.round(side.eloAfter()) + "</white> <dark_gray>(</dark_gray>" + Text.delta(side.eloDelta())
                + "<dark_gray>)</dark_gray>" + w);
    }

    private static Component tierLine(MatchOutcome.Side side) {
        String tiers = side.tierChanged()
                ? Text.tier(side.tierBefore()) + Text.ARROW + Text.tier(side.tierAfter())
                : Text.tier(side.tierAfter());
        return Text.parse(" <gray>Тир</gray> " + tiers + (side.provisional() ? " <gray>(временный)</gray>" : ""));
    }

    private void notifyCard(UUID uuid, List<Component> lines, boolean queueIfOffline) {
        List<Component> all = new ArrayList<>();
        all.add(Text.parse(Text.RULE));
        all.addAll(lines);
        all.add(Text.parse(Text.RULE));
        for (Component c : all) {
            if (queueIfOffline) notifyOrQueue(uuid, c); else notify(uuid, c);
        }
    }

    private static void notify(UUID uuid, Component msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }

    /** Sends now if online, otherwise on the player's next join (until restart). */
    private void notifyOrQueue(UUID uuid, Component msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
        else pendingNotices.computeIfAbsent(uuid, k -> new ArrayList<>()).add(msg);
    }
}
