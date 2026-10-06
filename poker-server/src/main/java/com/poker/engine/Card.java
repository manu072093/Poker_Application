package com.poker.engine;

/** A playing card. Rank is 2..14 (14 = Ace). */
public record Card(int rank, Suit suit) {
    private static final String RANKS = "23456789TJQKA";

    public Card {
        if (rank < 2 || rank > 14) throw new IllegalArgumentException("Bad rank: " + rank);
        if (suit == null) throw new IllegalArgumentException("Suit required");
    }

    /** Parses short notation such as "As", "Td", "9h". */
    public static Card parse(String s) {
        s = s.trim();
        if (s.length() != 2) throw new IllegalArgumentException("Bad card: " + s);
        int idx = RANKS.indexOf(Character.toUpperCase(s.charAt(0)));
        if (idx < 0) throw new IllegalArgumentException("Bad rank in card: " + s);
        return new Card(idx + 2, Suit.fromChar(s.charAt(1)));
    }

    @Override
    public String toString() {
        return "" + RANKS.charAt(rank - 2) + suit.symbol();
    }
}
