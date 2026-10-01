package ru.logic.tierplugin.core;

import java.util.UUID;

/**
 * Aggregated profile of a player across all gamemodes.
 * Stored in memory while the player is online; persisted to DB on change.
 */
public class PlayerProfile {

    private final UUID uuid;
    private String lastName;

    // Elo / Glicko per gamemode
    private double eloRating;
    private double ratingDeviation;   // Glicko RD
    private double volatility;         // Glicko-2

    // Skill & confidence
    private double skillScore;         // 0–100
    private double confidence;         // 0.0–1.0
    private int totalFights;

    // Current tier
    private Tier tier;
    private boolean provisional;

    /** Use {@link TierEngine#newProfile} to get the configured initial values. */
    public PlayerProfile(UUID uuid, String lastName,
                         double eloRating, double ratingDeviation, double volatility) {
        this.uuid = uuid;
        this.lastName = lastName;
        this.eloRating = eloRating;
        this.ratingDeviation = ratingDeviation;
        this.volatility = volatility;
        this.skillScore = 0;
        this.confidence = 0;
        this.totalFights = 0;
        this.tier = null;
        this.provisional = true;
    }

    // ── Getters ────────────────────────────────────────────────
    public UUID getUuid() { return uuid; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public double getEloRating() { return eloRating; }
    public void setEloRating(double eloRating) { this.eloRating = eloRating; }

    public double getRatingDeviation() { return ratingDeviation; }
    public void setRatingDeviation(double ratingDeviation) { this.ratingDeviation = ratingDeviation; }

    public double getVolatility() { return volatility; }
    public void setVolatility(double volatility) { this.volatility = volatility; }

    public double getSkillScore() { return skillScore; }
    public void setSkillScore(double skillScore) { this.skillScore = skillScore; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public int getTotalFights() { return totalFights; }
    public void setTotalFights(int totalFights) { this.totalFights = totalFights; }

    public Tier getTier() { return tier; }
    public void setTier(Tier tier) { this.tier = tier; }

    public boolean isProvisional() { return provisional; }
    public void setProvisional(boolean provisional) { this.provisional = provisional; }

    @Override
    public String toString() {
        return "PlayerProfile{uuid=" + uuid + ", tier=" + tier + ", elo=" + eloRating
                + ", skill=" + skillScore + ", confidence=" + confidence + "}";
    }
}
