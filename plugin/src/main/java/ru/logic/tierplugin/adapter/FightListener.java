package ru.logic.tierplugin.adapter;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.fight.FightManager;

import java.util.UUID;

/**
 * Fight lifecycle events: a kill ends the fight, leaving forfeits it.
 * Also keeps the players table current on join.
 */
public class FightListener implements Listener {

    private final LogicTierPlugin plugin;
    private final FightManager fights;

    public FightListener(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.fights = plugin.getFightManager();
    }

    /** Keeps the players table current so offline lookups by name work. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.getStorage().touchPlayer(p.getUniqueId(), p.getName());
    }

    /**
     * Ends a fight on death: the opponent wins if they landed the kill,
     * otherwise (fall damage, /kill, lava) the fight is cancelled unrated.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getPlayer();
        fights.fightOf(dead.getUniqueId()).ifPresent(fight -> {
            UUID opponent = fight.opponentOf(dead.getUniqueId());
            Player killer = dead.getKiller();
            if (killer != null && killer.getUniqueId().equals(opponent)) {
                fights.killed(opponent);
            } else {
                fights.cancelFight(dead.getUniqueId());
                plugin.getLogger().info("Fight of " + dead.getName()
                        + " ended without a kill by the opponent; unrated.");
            }
        });
    }

    /** A player leaving mid-fight forfeits: the opponent is awarded the win. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        fights.fightOf(player.getUniqueId()).ifPresent(fight -> {
            fights.endFight(fight.opponentOf(player.getUniqueId()));
            plugin.getLogger().info(player.getName() + " disconnected mid-fight. Win awarded to opponent.");
        });
    }
}
