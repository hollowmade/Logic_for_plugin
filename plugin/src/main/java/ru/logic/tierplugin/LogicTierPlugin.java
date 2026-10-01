package ru.logic.tierplugin;

import org.bukkit.plugin.java.JavaPlugin;
import ru.logic.tierplugin.adapter.BukkitEventAdapter;
import ru.logic.tierplugin.adapter.ConfigLoader;
import ru.logic.tierplugin.commands.DuelCommand;
import ru.logic.tierplugin.commands.TierAdminCommand;
import ru.logic.tierplugin.commands.TierCommand;
import ru.logic.tierplugin.core.TierEngine;
import ru.logic.tierplugin.storage.Database;
import ru.logic.tierplugin.tracker.FightTracker;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Main plugin class for LogicTierPlugin.
 * Initialises all subsystems: storage, core engine, tracker, commands, events.
 */
public final class LogicTierPlugin extends JavaPlugin {

    private static LogicTierPlugin instance;

    private Database database;
    private TierEngine tierEngine;
    private FightTracker fightTracker;

    @Override
    public void onEnable() {
        instance = this;
        Logger log = getLogger();

        saveDefaultConfig();

        // 1. Storage
        database = new Database(this);
        if (!database.init()) {
            log.severe("[LogicTierPlugin] Failed to initialise database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Core engine
        tierEngine = new TierEngine(ConfigLoader.load(getConfig(), log));

        // 3. Tracker
        fightTracker = new FightTracker(this);

        // 4. Commands
        Objects.requireNonNull(getCommand("tier")).setExecutor(new TierCommand(this));
        Objects.requireNonNull(getCommand("tieradmin")).setExecutor(new TierAdminCommand(this));
        Objects.requireNonNull(getCommand("duel")).setExecutor(new DuelCommand(this));

        // 5. Events
        getServer().getPluginManager().registerEvents(new BukkitEventAdapter(this), this);

        log.info("[LogicTierPlugin] Enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (fightTracker != null) fightTracker.shutdown();
        if (database != null) database.close();
        getLogger().info("[LogicTierPlugin] Disabled.");
    }

    public static LogicTierPlugin getInstance() { return instance; }
    public Database getDatabase() { return database; }
    public TierEngine getTierEngine() { return tierEngine; }
    public FightTracker getFightTracker() { return fightTracker; }
}
