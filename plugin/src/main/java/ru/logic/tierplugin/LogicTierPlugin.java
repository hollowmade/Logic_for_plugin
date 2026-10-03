package ru.logic.tierplugin;

import org.bukkit.plugin.java.JavaPlugin;
import ru.logic.tierplugin.adapter.ConfigLoader;
import ru.logic.tierplugin.adapter.FightListener;
import ru.logic.tierplugin.adapter.MetricsListener;
import ru.logic.tierplugin.commands.DuelCommand;
import ru.logic.tierplugin.commands.TierAdminCommand;
import ru.logic.tierplugin.commands.TierCommand;
import ru.logic.tierplugin.core.RatingService;
import ru.logic.tierplugin.fight.FightManager;
import ru.logic.tierplugin.storage.Database;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.text.Text;
import ru.logic.tierplugin.tracker.MetricsTracker;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Main plugin class for LogicTierPlugin.
 * Initialises all subsystems: rating core, storage, fights, metrics tracker, commands, events.
 */
public final class LogicTierPlugin extends JavaPlugin {

    private Database database;
    private Storage storage;
    private RatingService ratingService;
    private FightManager fightManager;
    private MetricsTracker metricsTracker;
    private MetricsListener metricsListener;
    private int metricsSummaryFights;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1. Rating core (pure logic, config translated once)
        ratingService = new RatingService(ConfigLoader.load(getConfig(), getLogger()));

        // 2. Storage
        try {
            database = new Database(ConfigLoader.database(getConfig(), getDataFolder()), getLogger());
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Не удалось подключиться к базе данных. Плагин выключается.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        storage = new Storage(database, Clock.systemDefaultZone());

        // 3. Fights and the metrics tracker attached to them
        fightManager = new FightManager(this, ConfigLoader.fight(getConfig()));
        metricsTracker = new MetricsTracker(fightManager, ConfigLoader.tracker(getConfig()));
        metricsSummaryFights = getConfig().getInt("metrics.summary_fights", 20);

        // 4. Commands
        Objects.requireNonNull(getCommand("tier")).setExecutor(new TierCommand(this));
        TierAdminCommand admin = new TierAdminCommand(this);
        Objects.requireNonNull(getCommand("tieradmin")).setExecutor(admin);
        Objects.requireNonNull(getCommand("tieradmin")).setTabCompleter(admin);
        Objects.requireNonNull(getCommand("tieradmin")).permissionMessage(
                Text.parse(Text.PREFIX + "<#FF6B6B>Недостаточно прав."));
        Objects.requireNonNull(getCommand("duel")).setExecutor(new DuelCommand(this));

        // 5. Events (players already online after /reload are registered too)
        getServer().getPluginManager().registerEvents(new FightListener(this), this);
        metricsListener = new MetricsListener(metricsTracker, getLogger());
        getServer().getPluginManager().registerEvents(metricsListener, this);
        getServer().getOnlinePlayers().forEach(p -> storage.touchPlayer(p.getUniqueId(), p.getName()));

        getLogger().info("Плагин включён.");
    }

    @Override
    public void onDisable() {
        if (fightManager != null) fightManager.shutdown();
        if (database != null) database.close(); // drains queued writes first
        getLogger().info("Плагин выключен.");
    }

    /** Runs {@code task} on the main server thread (Bukkit API is not thread-safe). */
    public void sync(Runnable task) {
        if (isEnabled()) getServer().getScheduler().runTask(this, task);
    }

    public Storage getStorage() { return storage; }
    public RatingService getRatingService() { return ratingService; }
    public FightManager getFightManager() { return fightManager; }
    public MetricsTracker getMetricsTracker() { return metricsTracker; }
    public MetricsListener getMetricsListener() { return metricsListener; }
    /** How many recent fights {@code /tieradmin metrics} aggregates. */
    public int getMetricsSummaryFights() { return metricsSummaryFights; }
}
