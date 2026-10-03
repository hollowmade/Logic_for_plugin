package ru.logic.tierplugin.tracker;

import ru.logic.tierplugin.core.FightMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates combat telemetry per fight and per player.
 * <p>
 * Bukkit-free: the adapter layer translates server events into these calls, and
 * {@link ActiveFights} tells which fight a player is in. Events from players
 * outside a fight, or between players of different fights, are ignored.
 * The fight owner collects the result with {@link #finish} when the fight ends.
 */
public class MetricsTracker {

    private final ActiveFights fights;
    private final TrackerSettings settings;

    /** fight id → (player → log) */
    private final Map<UUID, Map<UUID, CombatLog>> logs = new ConcurrentHashMap<>();

    public MetricsTracker(ActiveFights fights, TrackerSettings settings) {
        this.fights = fights;
        this.settings = settings;
    }

    /**
     * An attack swing of the main hand (hit or miss).
     *
     * @param tick server tick; a swing and a hit in the same tick are one attack
     */
    public void swing(UUID player, long now, int tick) {
        fights.fightOf(player).ifPresent(f -> log(f, player).swing(now, tick));
    }

    /** A direct melee hit of {@code attacker} on {@code victim}. */
    public void meleeHit(UUID attacker, UUID victim, double damage, long now, int tick) {
        sameFight(attacker, victim).ifPresent(f -> {
            log(f, attacker).meleeHit(now, tick, damage);
            log(f, victim).hitByOpponent();
        });
    }

    /** Damage of {@code attacker} to {@code victim} that is not a direct hit (sweep, projectile). */
    public void indirectDamage(UUID attacker, UUID victim, double damage) {
        sameFight(attacker, victim).ifPresent(f -> log(f, attacker).indirectDamage(damage));
    }

    /** Any damage a fighting player takes, whatever the source. */
    public void damageTaken(UUID victim, double damage) {
        fights.fightOf(victim).ifPresent(f -> log(f, victim).damageTaken(damage));
    }

    /** Current metrics of a running fight, without ending it. Missing players get {@link FightMetrics#EMPTY}. */
    public Map<UUID, FightMetrics> snapshot(ActiveFights.FightRef fight) {
        return collect(fight, logs.getOrDefault(fight.id(), Map.of()));
    }

    /** Final metrics of a fight; forgets the fight. */
    public Map<UUID, FightMetrics> finish(ActiveFights.FightRef fight) {
        Map<UUID, CombatLog> fightLogs = logs.remove(fight.id());
        return collect(fight, fightLogs != null ? fightLogs : Map.of());
    }

    /** Drops a fight's telemetry (e.g. cancelled fight). */
    public void discard(UUID fightId) {
        logs.remove(fightId);
    }

    private Optional<ActiveFights.FightRef> sameFight(UUID a, UUID b) {
        return fights.fightOf(a).filter(f -> !a.equals(b) && f.involves(b));
    }

    private CombatLog log(ActiveFights.FightRef fight, UUID player) {
        return logs.computeIfAbsent(fight.id(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(player, p -> new CombatLog(settings));
    }

    private static Map<UUID, FightMetrics> collect(ActiveFights.FightRef fight, Map<UUID, CombatLog> fightLogs) {
        Map<UUID, FightMetrics> out = new HashMap<>();
        for (UUID p : new UUID[]{fight.playerA(), fight.playerB()}) {
            CombatLog l = fightLogs.get(p);
            out.put(p, l != null ? l.toMetrics() : FightMetrics.EMPTY);
        }
        return out;
    }
}
