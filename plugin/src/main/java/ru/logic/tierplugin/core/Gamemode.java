package ru.logic.tierplugin.core;

import java.util.Optional;

/**
 * Supported PvP gamemodes, each with its own metric weights and Elo ladder.
 */
public enum Gamemode {
    CRYSTAL,
    SWORD,
    CART;

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
