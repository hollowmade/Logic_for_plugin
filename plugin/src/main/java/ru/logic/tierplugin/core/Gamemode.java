package ru.logic.tierplugin.core;

import java.util.Optional;

/**
 * Supported PvP gamemodes, each with its own metric weights and Elo ladder.
 */
public enum Gamemode {
    SWORD;

    // Disabled until their metrics are done (crystal/anchor timing, cart placement).
    // The per-mode ladder, weights and storage already support them: to bring a mode
    // back, uncomment it here, in CoreConfig.defaults(), in config.yml skill_weights,
    // and the multi-mode tests in RatingCoreTest / StorageTest.
    // CRYSTAL,
    // CART,

    /** Returns the config key prefix for this gamemode's skill weights. */
    public String configKey() {
        return name().toLowerCase();
    }

    /** Case-insensitive parse, e.g. "sword" → SWORD. */
    public static Optional<Gamemode> parse(String s) {
        for (Gamemode gm : values()) {
            if (gm.name().equalsIgnoreCase(s)) return Optional.of(gm);
        }
        return Optional.empty();
    }
}
