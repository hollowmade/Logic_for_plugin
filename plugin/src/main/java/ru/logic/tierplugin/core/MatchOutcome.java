package ru.logic.tierplugin.core;

/**
 * What a processed match changed, for persistence and for telling the players.
 *
 * @param matchNumberToday 1-based index of this match among the pair's matches today
 * @param weight           winner's Elo multiplier (anti-abuse × score quality); 0 = winner gets nothing
 * @param loserWeight      loser's Elo multiplier: equals {@code weight} for a kill,
 *                         the leave penalty multiplier for a leave
 * @param leaveNumber      for a LEAVE: the loser's leaves within the window, this one included
 */
public record MatchOutcome(int matchNumberToday, double weight, double loserWeight,
                           EndReason endReason, int leaveNumber,
                           Side winner, Side loser) {

    /** True if the winner was credited. */
    public boolean rated() { return weight > 0; }

    public boolean isLeave() { return endReason == EndReason.LEAVE; }

    /** Per-player before/after snapshot. */
    public record Side(double eloBefore, double eloAfter,
                       Tier tierBefore, Tier tierAfter, boolean provisional,
                       double fightSkill) {

        public double eloDelta() { return eloAfter - eloBefore; }

        public boolean tierChanged() { return tierBefore != tierAfter; }
    }
}
