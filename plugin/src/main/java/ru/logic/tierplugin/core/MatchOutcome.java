package ru.logic.tierplugin.core;

/**
 * What a processed match changed, for persistence and for telling the players.
 *
 * @param matchNumberToday 1-based index of this match among the pair's matches today
 * @param weight           final Elo multiplier (anti-abuse × score quality); 0 = unrated
 */
public record MatchOutcome(int matchNumberToday, double weight,
                           Side winner, Side loser) {

    public boolean rated() { return weight > 0; }

    /** Per-player before/after snapshot. */
    public record Side(double eloBefore, double eloAfter,
                       Tier tierBefore, Tier tierAfter, boolean provisional,
                       double fightSkill) {

        public double eloDelta() { return eloAfter - eloBefore; }

        public boolean tierChanged() { return tierBefore != tierAfter; }
    }
}
