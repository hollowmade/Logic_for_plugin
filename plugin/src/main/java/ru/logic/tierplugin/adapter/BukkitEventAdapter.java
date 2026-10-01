package ru.logic.tierplugin.adapter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.tracker.FightSession;
import ru.logic.tierplugin.tracker.FightTracker;

import java.util.Optional;

/**
 * Bridges Bukkit events into the {@link FightTracker} telemetry pipeline.
 * <p>
 * Listens for damage, death, and quit events and translates them into
 * structured telemetry calls on the active {@link FightSession}.
 */
public class BukkitEventAdapter implements Listener {

    private final LogicTierPlugin plugin;
    private final FightTracker tracker;

    public BukkitEventAdapter(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.tracker = plugin.getFightTracker();
    }

    /**
     * Records damage dealt between two players who are in an active fight session.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player)) return;

        Optional<FightSession> sessionOpt = tracker.getSession(attacker.getUniqueId());
        sessionOpt.ifPresent(session -> {
            if (session.involves(((Player) event.getEntity()).getUniqueId())) {
                session.recordHit(attacker.getUniqueId());
                session.recordDamage(attacker.getUniqueId(), event.getFinalDamage());
            }
        });
    }

    /**
     * Detects fight endings: if a player dies and both participants were in a session,
     * the killer is declared the winner.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player dead)) return;

        Optional<FightSession> sessionOpt = tracker.getSession(dead.getUniqueId());
        if (sessionOpt.isEmpty()) return;

        FightSession session = sessionOpt.get();

        Player killer = dead.getKiller();
        if (killer != null && session.involves(killer.getUniqueId())) {
            // Record the kill for score tracking
            session.recordKill(killer.getUniqueId());
            // End the fight with the killer as winner
            tracker.endFight(killer.getUniqueId());
        } else {
            // No valid killer (e.g. fall damage, /kill) — cancel the session
            tracker.getSession(dead.getUniqueId()).ifPresent(s -> {
                // Remove both participants without processing ratings
                tracker.getSession(s.getPlayer1()).ifPresent(ignored -> {});
                // Graceful shutdown of the session without rating change
                plugin.getFightTracker().shutdown();
                plugin.getLogger().warning("Fight session ended without a valid killer for "
                        + dead.getName() + ". No ratings updated.");
            });
        }
    }

    /**
     * Cleans up any active sessions when a player disconnects mid-fight.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Optional<FightSession> sessionOpt = tracker.getSession(player.getUniqueId());
        if (sessionOpt.isEmpty()) return;

        FightSession session = sessionOpt.get();
        // Award the win to the opponent if one player rage-quits
        java.util.UUID opponent = session.getOpponent(player.getUniqueId());
        tracker.endFight(opponent);
        plugin.getLogger().info(player.getName()
                + " disconnected mid-fight. Win awarded to opponent.");
    }
}
