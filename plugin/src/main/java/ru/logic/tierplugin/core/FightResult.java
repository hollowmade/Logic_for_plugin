package ru.logic.tierplugin.core;

import java.util.UUID;

/**
 * Raw result of a single fight, produced by FightTracker and consumed by TierEngine.
 */
public class FightResult {

    private final UUID winnerUuid;
    private final UUID loserUuid;
    private final Gamemode gamemode;

    // Scores (e.g. kills in the fight)
    private final int winnerScore;
    private final int loserScore;

    // Telemetry buckets (0–100 normalised)
    private final double mechanicsScore;
    private final double combatScore;
    private final double decisionMakingScore;
    private final double movementScore;
    private final double consistencyScore;
    private final double adaptationScore;

    // Match quality
    private final long durationMillis;

    private FightResult(Builder b) {
        this.winnerUuid = b.winnerUuid;
        this.loserUuid = b.loserUuid;
        this.gamemode = b.gamemode;
        this.winnerScore = b.winnerScore;
        this.loserScore = b.loserScore;
        this.mechanicsScore = b.mechanicsScore;
        this.combatScore = b.combatScore;
        this.decisionMakingScore = b.decisionMakingScore;
        this.movementScore = b.movementScore;
        this.consistencyScore = b.consistencyScore;
        this.adaptationScore = b.adaptationScore;
        this.durationMillis = b.durationMillis;
    }

    public UUID getWinnerUuid() { return winnerUuid; }
    public UUID getLoserUuid() { return loserUuid; }
    public Gamemode getGamemode() { return gamemode; }
    public int getWinnerScore() { return winnerScore; }
    public int getLoserScore() { return loserScore; }
    public double getMechanicsScore() { return mechanicsScore; }
    public double getCombatScore() { return combatScore; }
    public double getDecisionMakingScore() { return decisionMakingScore; }
    public double getMovementScore() { return movementScore; }
    public double getConsistencyScore() { return consistencyScore; }
    public double getAdaptationScore() { return adaptationScore; }
    public long getDurationMillis() { return durationMillis; }

    // ── Builder ─────────────────────────────────────────────
    public static final class Builder {
        private UUID winnerUuid, loserUuid;
        private Gamemode gamemode;
        private int winnerScore, loserScore;
        private double mechanicsScore, combatScore, decisionMakingScore;
        private double movementScore, consistencyScore, adaptationScore;
        private long durationMillis;

        public Builder winner(UUID uuid) { this.winnerUuid = uuid; return this; }
        public Builder loser(UUID uuid) { this.loserUuid = uuid; return this; }
        public Builder gamemode(Gamemode gm) { this.gamemode = gm; return this; }
        public Builder winnerScore(int s) { this.winnerScore = s; return this; }
        public Builder loserScore(int s) { this.loserScore = s; return this; }
        public Builder mechanics(double v) { this.mechanicsScore = v; return this; }
        public Builder combat(double v) { this.combatScore = v; return this; }
        public Builder decisionMaking(double v) { this.decisionMakingScore = v; return this; }
        public Builder movement(double v) { this.movementScore = v; return this; }
        public Builder consistency(double v) { this.consistencyScore = v; return this; }
        public Builder adaptation(double v) { this.adaptationScore = v; return this; }
        public Builder duration(long ms) { this.durationMillis = ms; return this; }
        public FightResult build() { return new FightResult(this); }
    }
}
