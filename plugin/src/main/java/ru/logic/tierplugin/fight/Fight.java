package ru.logic.tierplugin.fight;

import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.tracker.ActiveFights;

import java.util.UUID;

/** An ongoing 1v1 fight: who, which gamemode, since when, and the kill score. */
public class Fight {

    private final ActiveFights.FightRef ref;
    private final Gamemode gamemode;
    private final long startedAt;
    private int killsA;
    private int killsB;

    Fight(UUID playerA, UUID playerB, Gamemode gamemode, long startedAt) {
        this.ref = new ActiveFights.FightRef(UUID.randomUUID(), playerA, playerB);
        this.gamemode = gamemode;
        this.startedAt = startedAt;
    }

    public ActiveFights.FightRef ref() { return ref; }
    public Gamemode gamemode() { return gamemode; }
    public long startedAt() { return startedAt; }

    void recordKill(UUID killer) {
        if (ref.playerA().equals(killer)) killsA++; else killsB++;
    }

    int killsOf(UUID player) {
        return ref.playerA().equals(player) ? killsA : killsB;
    }
}
