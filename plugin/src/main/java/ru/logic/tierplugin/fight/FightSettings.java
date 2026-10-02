package ru.logic.tierplugin.fight;

/**
 * @param reconnectGraceSeconds a player who disconnects mid-fight may rejoin within
 *                              this time and continue; after it the fight is forfeited
 */
public record FightSettings(int reconnectGraceSeconds) {

    public static final FightSettings DEFAULTS = new FightSettings(30);
}
