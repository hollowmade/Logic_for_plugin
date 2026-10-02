package ru.logic.tierplugin.fight;

import org.bukkit.scheduler.BukkitTask;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.tracker.ActiveFights;

import java.util.UUID;

/** An ongoing 1v1 fight: who, which gamemode, since when, the kill score and a pending disconnect. */
public class Fight {

    private final ActiveFights.FightRef ref;
    private final Gamemode gamemode;
    private final long startedAt;
    private int killsA;
    private int killsB;

    // Set while a participant is offline and the reconnect grace period runs
    private UUID disconnected;
    private long disconnectedAt;
    private BukkitTask forfeitTask;

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

    // ── Disconnect / reconnect ────────────────────────────────

    /** @return the participant who is currently offline, or null */
    UUID disconnected() { return disconnected; }

    long disconnectedAt() { return disconnectedAt; }

    void markDisconnected(UUID player, long at, BukkitTask task) {
        this.disconnected = player;
        this.disconnectedAt = at;
        this.forfeitTask = task;
    }

    /** Clears the pending disconnect and stops its forfeit timer. */
    void clearDisconnect() {
        if (forfeitTask != null) forfeitTask.cancel();
        forfeitTask = null;
        disconnected = null;
    }
}
