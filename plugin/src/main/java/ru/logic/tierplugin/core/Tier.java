package ru.logic.tierplugin.core;

/**
 * Enum of the 10 tier levels, ordered from lowest to highest.
 */
public enum Tier {
    LT5(1),
    HT5(2),
    LT4(3),
    HT4(4),
    LT3(5),
    HT3(6),
    LT2(7),
    HT2(8),
    LT1(9),
    HT1(10);

    private final int rank;

    Tier(int rank) { this.rank = rank; }

    public int getRank() { return rank; }

    /** Returns true if this tier is higher than {@code other}. */
    public boolean isHigherThan(Tier other) { return this.rank > other.rank; }

    /** Returns true if this tier is lower than {@code other}. */
    public boolean isLowerThan(Tier other) { return this.rank < other.rank; }
}
