package ru.logic.tierplugin.core;

import java.util.UUID;

/**
 * A player's rating in one gamemode. Every gamemode has its own ladder,
 * so a strong Sword player starts from scratch in Crystal.
 */
public class ModeRating {

    private final UUID uuid;
    private final Gamemode gamemode;

    private double elo;
    private double ratingDeviation;   // Glicko RD: high = uncertain
    private double volatility;        // reserved for Glicko-2

    private double skillScore;        // 0–100, EMA over fights
    private double confidence;        // 0.0–1.0, derived from RD
    private int fights;
    private int wins;
    private int losses;

    private Tier tier;                // null = unranked
    private boolean provisional;      // true while the tier is a temporary estimate

    public ModeRating(UUID uuid, Gamemode gamemode, double elo, double ratingDeviation, double volatility) {
        this.uuid = uuid;
        this.gamemode = gamemode;
        this.elo = elo;
        this.ratingDeviation = ratingDeviation;
        this.volatility = volatility;
        this.provisional = true;
    }

    public UUID getUuid() { return uuid; }
    public Gamemode getGamemode() { return gamemode; }

    public double getElo() { return elo; }
    public void setElo(double elo) { this.elo = elo; }

    public double getRatingDeviation() { return ratingDeviation; }
    public void setRatingDeviation(double ratingDeviation) { this.ratingDeviation = ratingDeviation; }

    public double getVolatility() { return volatility; }
    public void setVolatility(double volatility) { this.volatility = volatility; }

    public double getSkillScore() { return skillScore; }
    public void setSkillScore(double skillScore) { this.skillScore = skillScore; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public int getFights() { return fights; }
    public void setFights(int fights) { this.fights = fights; }

    public int getWins() { return wins; }
    public void setWins(int wins) { this.wins = wins; }

    public int getLosses() { return losses; }
    public void setLosses(int losses) { this.losses = losses; }

    public Tier getTier() { return tier; }
    public void setTier(Tier tier) { this.tier = tier; }

    public boolean isProvisional() { return provisional; }
    public void setProvisional(boolean provisional) { this.provisional = provisional; }

    @Override
    public String toString() {
        return "ModeRating{" + gamemode + ", tier=" + tier + (provisional ? "?" : "")
                + ", elo=" + Math.round(elo) + ", rd=" + Math.round(ratingDeviation)
                + ", skill=" + Math.round(skillScore) + ", fights=" + fights + "}";
    }
}
