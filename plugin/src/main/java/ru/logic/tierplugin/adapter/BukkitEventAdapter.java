package ru.logic.tierplugin.adapter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.tracker.FightSession;
import ru.logic.tierplugin.tracker.FightTracker;

import java.util.Optional;
import java.util.UUID;

/**
 * Bridges Bukkit events into the {@link FightTracker} telemetry pipeline.
 * <p>
 * Listens for damage, death, join and quit events and translates them into
 * structured telemetry calls on the active {@link FightSession}.
 */
public class BukkitEventAdapter implements Listener {

    private final LogicTierPlugin plugin;
    private final FightTracker tracker;

    public BukkitEventAdapter(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.tracker = plugin.getFightTracker();
    }

    /** Keeps the players table current so offline lookups by name work. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.getStorage().touchPlayer(p.getUniqueId(), p.getName());
    }

    /**
     * Records damage dealt between two players who are in an active fight session.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        tracker.getSession(attacker.getUniqueId()).ifPresent(session -> {
            if (session.involves(victim.getUniqueId())) {
                session.recordHit(attacker.getUniqueId());
                session.recordDamage(attacker.getUniqueId(), event.getFinalDamage());
            }
        });
    }

    /**
     * Ends a fight on death: the opponent wins if they landed the kill,
     * otherwise (fall damage, /kill, lava) the fight is cancelled unrated.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getPlayer();
        Optional<FightSession> sessionOpt = tracker.getSession(dead.getUniqueId());
        if (sessionOpt.isEmpty()) return;

        FightSession session = sessionOpt.get();
        UUID opponent = session.getOpponent(dead.getUniqueId());
        Player killer = dead.getKiller();

        if (killer != null && killer.getUniqueId().equals(opponent)) {
            session.recordKill(opponent);
            tracker.endFight(opponent);
        } else {
            tracker.cancelFight(dead.getUniqueId());
            plugin.getLogger().info("Fight of " + dead.getName() + " ended without a kill by the opponent; unrated.");
        }
    }

    /** A player leaving mid-fight forfeits: the opponent is awarded the win. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        tracker.getSession(player.getUniqueId()).ifPresent(session -> {
            tracker.endFight(session.getOpponent(player.getUniqueId()));
            plugin.getLogger().info(player.getName() + " disconnected mid-fight. Win awarded to opponent.");
        });
    }
}
