package ru.logic.tierplugin.tracker;

import ru.logic.tierplugin.core.Gamemode;

import java.util.UUID;

/**
 * Holds live telemetry data for an ongoing 1v1 fight.
 * Tracks: hits/misses (accuracy), damage dealt/taken, kill score,
 * and combo streaks (current + max) per player.
 */
public class FightSession {

    private final UUID player1;
    private final UUID player2;
    private final Gamemode gamemode;
    private final long startTime;

    // ── Player 1 telemetry ─────────────────────────────────────
    private int p1Hits, p1Misses, p1DamageDealt, p1DamageTaken;
    private int p1Score;        // kills
    private int p1CurrentCombo; // ongoing hit streak
    private int p1MaxCombo;     // best streak this fight

    // ── Player 2 telemetry ─────────────────────────────────────
    private int p2Hits, p2Misses, p2DamageDealt, p2DamageTaken;
    private int p2Score;
    private int p2CurrentCombo;
    private int p2MaxCombo;

    public FightSession(UUID player1, UUID player2, Gamemode gamemode) {
        this.player1   = player1;
        this.player2   = player2;
        this.gamemode  = gamemode;
        this.startTime = System.currentTimeMillis();
    }

    // ── Getters ────────────────────────────────────────────────
    public UUID getPlayer1()        { return player1; }
    public UUID getPlayer2()        { return player2; }
    public Gamemode getGamemode()   { return gamemode; }
    public long getStartTime()      { return startTime; }
    public long getDurationMillis() { return System.currentTimeMillis() - startTime; }

    public int getP1Hits()        { return p1Hits; }
    public int getP1Misses()      { return p1Misses; }
    public int getP1DamageDealt() { return p1DamageDealt; }
    public int getP1DamageTaken() { return p1DamageTaken; }
    public int getP1Score()       { return p1Score; }
    public int getP1MaxCombo()    { return p1MaxCombo; }

    public int getP2Hits()        { return p2Hits; }
    public int getP2Misses()      { return p2Misses; }
    public int getP2DamageDealt() { return p2DamageDealt; }
    public int getP2DamageTaken() { return p2DamageTaken; }
    public int getP2Score()       { return p2Score; }
    public int getP2MaxCombo()    { return p2MaxCombo; }

    // ── Mutators ───────────────────────────────────────────────

    /**
     * Records a successful hit.
     * Extends the attacker's combo streak and resets the opponent's.
     */
    public void recordHit(UUID attacker) {
        if (attacker.equals(player1)) {
            p1Hits++;
            p1CurrentCombo++;
            if (p1CurrentCombo > p1MaxCombo) p1MaxCombo = p1CurrentCombo;
            p2CurrentCombo = 0;
        } else {
            p2Hits++;
            p2CurrentCombo++;
            if (p2CurrentCombo > p2MaxCombo) p2MaxCombo = p2CurrentCombo;
            p1CurrentCombo = 0;
        }
    }

    /**
     * Records a missed attack.
     * Resets the attacker's current combo streak.
     */
    public void recordMiss(UUID attacker) {
        if (attacker.equals(player1)) { p1Misses++; p1CurrentCombo = 0; }
        else                          { p2Misses++; p2CurrentCombo = 0; }
    }

    public void recordDamage(UUID attacker, double damage) {
        int dmg = (int) Math.round(damage);
        if (attacker.equals(player1)) { p1DamageDealt += dmg; p2DamageTaken += dmg; }
        else                          { p2DamageDealt += dmg; p1DamageTaken += dmg; }
    }

    public void recordKill(UUID killer) {
        if (killer.equals(player1)) p1Score++; else p2Score++;
    }

    // ── Helpers ────────────────────────────────────────────────

    /** @return true if this session involves the given player */
    public boolean involves(UUID uuid) {
        return player1.equals(uuid) || player2.equals(uuid);
    }

    /** @return the opponent's UUID for the given player */
    public UUID getOpponent(UUID uuid) {
        return player1.equals(uuid) ? player2 : player1;
    }
}
