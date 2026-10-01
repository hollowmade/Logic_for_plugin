package ru.logic.tierplugin;

import org.bukkit.plugin.java.JavaPlugin;
import ru.logic.tierplugin.adapter.BukkitEventAdapter;
import ru.logic.tierplugin.adapter.ConfigLoader;
import ru.logic.tierplugin.commands.DuelCommand;
import ru.logic.tierplugin.commands.TierAdminCommand;
import ru.logic.tierplugin.commands.TierCommand;
import ru.logic.tierplugin.core.RatingService;
import ru.logic.tierplugin.storage.Database;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.tracker.FightTracker;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Main plugin class for LogicTierPlugin.
 * Initialises all subsystems: storage, rating core, tracker, commands, events.
 */
public final class LogicTierPlugin extends JavaPlugin {

    private Database database;
    private Storage storage;
    private RatingService ratingService;
    private FightTracker fightTracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1. Rating core (pure logic, config translated once)
        ratingService = new RatingService(ConfigLoader.load(getConfig(), getLogger()));

        // 2. Storage
        try {
            database = new Database(ConfigLoader.database(getConfig(), getDataFolder()), getLogger());
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Failed to initialise database. Disabling plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        storage = new Storage(database, Clock.systemDefaultZone());

        // 3. Tracker
        fightTracker = new FightTracker(this);

        // 4. Commands
        Objects.requireNonNull(getCommand("tier")).setExecutor(new TierCommand(this));
        TierAdminCommand admin = new TierAdminCommand(this);
        Objects.requireNonNull(getCommand("tieradmin")).setExecutor(admin);
        Objects.requireNonNull(getCommand("tieradmin")).setTabCompleter(admin);
        Objects.requireNonNull(getCommand("duel")).setExecutor(new DuelCommand(this));

        // 5. Events (players already online after /reload are registered too)
        getServer().getPluginManager().registerEvents(new BukkitEventAdapter(this), this);
        getServer().getOnlinePlayers().forEach(p -> storage.touchPlayer(p.getUniqueId(), p.getName()));

        getLogger().info("Enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (fightTracker != null) fightTracker.shutdown();
        if (database != null) database.close(); // drains queued writes first
        getLogger().info("Disabled.");
    }

    /** Runs {@code task} on the main server thread (Bukkit API is not thread-safe). */
    public void sync(Runnable task) {
        if (isEnabled()) getServer().getScheduler().runTask(this, task);
    }

    public Storage getStorage() { return storage; }
    public RatingService getRatingService() { return ratingService; }
    public FightTracker getFightTracker() { return fightTracker; }
}
