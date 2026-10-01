package ru.logic.tierplugin.core;

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
}
