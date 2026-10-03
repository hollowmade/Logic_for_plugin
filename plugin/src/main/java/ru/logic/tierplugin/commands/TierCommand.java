package ru.logic.tierplugin.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.CoreConfig;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.core.Tier;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.text.Text;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * /tier [player] — shows per-gamemode tier info for self or another player (online or offline).
 */
public class TierCommand implements CommandExecutor {

    private final LogicTierPlugin plugin;

    public TierCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        CompletableFuture<Optional<Storage.PlayerRef>> target;
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                Text.error(sender, "Использование: /tier " + Text.arg("игрок"));
                return true;
            }
            target = CompletableFuture.completedFuture(Optional.of(new Storage.PlayerRef(p.getUniqueId(), p.getName())));
        } else {
            target = PlayerResolver.resolve(plugin.getStorage(), args[0]);
        }

        target.thenCompose(ref -> ref.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : plugin.getStorage().profile(ref.get().uuid())
                                .thenApply(r -> Map.entry(ref.get(), r)))
                .whenComplete((res, err) -> plugin.sync(() -> {
                    if (err != null) {
                        Text.error(sender, "Ошибка базы данных — подробности в консоли сервера.");
                    } else if (res == null) {
                        Text.error(sender, "Игрок <name> ещё не заходил на сервер.", Text.plain("name", args[0]));
                    } else {
                        show(sender, res.getKey().name(), res.getValue(), plugin.getRatingService().getConfig());
                    }
                }));
        return true;
    }

    /** Profile card: one block per gamemode the player has fought in. */
    static void show(CommandSender sender, String name, Map<Gamemode, Storage.ProfileEntry> profile, CoreConfig cfg) {
        Text.line(sender, Text.RULE);
        Text.line(sender, " <gradient:#FFB347:#FF5E3A><bold>Профиль</bold></gradient> <white><bold><name></bold></white>",
                Text.plain("name", name));
        if (profile.isEmpty()) {
            Text.line(sender, " <gray>Рейтинговых боёв пока нет.</gray>");
        }
        for (Storage.ProfileEntry e : profile.values()) {
            ModeRating r = e.rating();
            String place = e.placement() == null ? ""
                    : Text.DOT + "<click:run_command:'/top " + r.getGamemode().configKey() + "'>"
                      + "<hover:show_text:'<gray>Открыть топ</gray>'><gray>Место в топе</gray> <" + Text.ACCENT + "><bold>#"
                      + e.placement().place() + "</bold></" + Text.ACCENT + "> <dark_gray>из " + e.placement().total()
                      + "</dark_gray></hover></click>";
            Text.line(sender, " <gray>" + Text.mode(r.getGamemode()) + "</gray> <dark_gray>»</dark_gray> "
                    + Text.tier(r.getTier(), r.isProvisional()) + place);
            Text.line(sender, "   <gray>Рейтинг</gray> <white>" + Math.round(r.getElo()) + "</white> <dark_gray>±"
                    + Math.round(r.getRatingDeviation()) + "</dark_gray>"
                    + Text.DOT + "<gray>Навык</gray> <white>" + Text.num(r.getSkillScore(), 0) + "</white><dark_gray>/100</dark_gray>"
                    + Text.DOT + "<hover:show_text:'<gray>Растёт с каждым боем. Временный тир\nфиксируется после боёв калибровки.</gray>'>"
                    + "<gray>Уверенность</gray> <white>" + Text.percent(r.getConfidence()) + "</white></hover>");
            int fights = r.getWins() + r.getLosses();
            Text.line(sender, "   <gray>Боёв</gray> <white>" + r.getFights() + "</white>"
                    + Text.DOT + "<gray>Победы</gray> <green>" + r.getWins() + "</green>"
                    + Text.DOT + "<gray>Поражения</gray> <red>" + r.getLosses() + "</red>"
                    + (fights > 0 ? Text.DOT + "<gray>Винрейт</gray> <white>" + Text.percent((double) r.getWins() / fights) + "</white>" : ""));
            Text.line(sender, "   " + nextTier(r, cfg));
            e.lifetime().ifPresent(m -> Text.line(sender, "   <gray>За всё время:</gray> <gray>точность</gray> <white>"
                    + (m.accuracy() < 0 ? "—" : Text.num(m.accuracy(), 0) + "%") + "</white>"
                    + Text.DOT + "<gray>КПС</gray> <white>" + Text.num(m.avgCps(), 1) + "</white>"
                    + Text.DOT + "<gray>лучшее комбо</gray> <white>" + m.bestCombo() + "</white>"
                    + Text.DOT + "<gray>урон за бой</gray> <white>" + Text.num(m.avgDamageDealt(), 1)
                    + "</white><dark_gray>/</dark_gray><white>" + Text.num(m.avgDamageTaken(), 1) + "</white>"));
        }
        Text.line(sender, Text.RULE);
    }

    /**
     * What the player still needs: during placement the fights left, afterwards
     * every requirement of the next tier with a tick or a cross.
     */
    static String nextTier(ModeRating r, CoreConfig cfg) {
        int placement = cfg.tierRules().placementFights();
        if (r.getFights() < placement) {
            int left = placement - r.getFights();
            return "<hover:show_text:'<gray>Пока идёт калибровка, тир оценивается только\nпо рейтингу и навыку и может меняться.</gray>'>"
                    + "<gray>Калибровка: до закрепления тира осталось</gray> <white>" + left + "</white> <gray>"
                    + (left % 10 == 1 && left % 100 != 11 ? "бой" : left % 10 >= 2 && left % 10 <= 4 && (left % 100 < 12 || left % 100 > 14) ? "боя" : "боёв")
                    + "</gray></hover>";
        }
        Tier current = r.getTier();
        if (current == Tier.HT1) return "<" + Text.ACCENT + ">Максимальный тир достигнут</" + Text.ACCENT + ">";
        Tier next = current == null ? Tier.LT5 : Tier.values()[current.ordinal() + 1];
        CoreConfig.TierThreshold t = cfg.tiers().get(next);
        if (t == null) return "";
        return "<hover:show_text:'<gray>Тир выдаётся, когда выполнены все условия сразу.</gray>'>"
                + "<gray>До</gray> " + Text.tier(next) + "<gray>:</gray></hover> "
                + check(r.getElo() >= t.minElo(), "Elo", Math.round(r.getElo()) + "/" + Math.round(t.minElo()))
                + Text.DOT + check(r.getSkillScore() >= t.minSkill(), "навык",
                        Text.num(r.getSkillScore(), 0) + "/" + Text.num(t.minSkill(), 0))
                + Text.DOT + check(r.getConfidence() >= t.minConfidence(), "уверенность",
                        Text.percent(r.getConfidence()) + "/" + Text.percent(t.minConfidence()))
                + Text.DOT + check(r.getFights() >= t.minFights(), "боёв", r.getFights() + "/" + t.minFights());
    }

    private static String check(boolean ok, String label, String value) {
        return (ok ? "<green>✔</green>" : "<red>✖</red>") + " <gray>" + label + "</gray> <white>" + value + "</white>";
    }
}
