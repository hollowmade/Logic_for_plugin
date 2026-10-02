package ru.logic.tierplugin.adapter;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.logic.tierplugin.tracker.MetricsTracker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates Bukkit combat events into {@link MetricsTracker} calls.
 * <ul>
 *   <li><b>Swing</b>: main-hand arm swing. The client also swings when placing
 *       blocks or using items, so swings in the same tick as a right-click
 *       interaction are ignored.</li>
 *   <li><b>Hit</b>: direct melee attack ({@code ENTITY_ATTACK}). Sweep and
 *       projectile damage count as damage dealt, not as hits.</li>
 *   <li><b>Damage taken</b>: final damage from any source.</li>
 * </ul>
 */
public class MetricsListener implements Listener {

    private final MetricsTracker tracker;

    /** Tick of each player's last right-click interaction. */
    private final Map<UUID, Integer> lastUseTick = new ConcurrentHashMap<>();

    public MetricsListener(MetricsTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        UUID id = event.getPlayer().getUniqueId();
        Integer useTick = lastUseTick.get(id);
        if (useTick != null && useTick == Bukkit.getCurrentTick()) return;
        tracker.swing(id, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            lastUseTick.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        lastUseTick.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        double damage = event.getFinalDamage();
        tracker.damageTaken(victim.getUniqueId(), damage);

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        Player attacker = attackerOf(byEntity.getDamager());
        if (attacker == null) return;

        if (byEntity.getDamager() == attacker && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            tracker.meleeHit(attacker.getUniqueId(), victim.getUniqueId(), damage, System.currentTimeMillis());
        } else {
            tracker.indirectDamage(attacker.getUniqueId(), victim.getUniqueId(), damage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUseTick.remove(event.getPlayer().getUniqueId());
    }

    /** The player behind the damage: the attacker itself or the shooter of a projectile. */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
