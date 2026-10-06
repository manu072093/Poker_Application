package com.poker.engine;

import java.security.SecureRandom;
import java.util.*;

/** A 52-card deck. Shuffled with an unbiased Fisher-Yates using SecureRandom by default. */
public final class Deck {
    private final Deque<Card> cards = new ArrayDeque<>(52);

    public Deck() { this(new SecureRandom()); }

    public Deck(Random rng) {
        List<Card> all = fullDeck();
        for (int i = all.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Collections.swap(all, i, j);
        }
        cards.addAll(all);
    }

    private Deck(List<Card> ordered) { cards.addAll(ordered); }

    /** Deterministic deck for tests: cards are drawn in exactly this order. */
    public static Deck ofOrder(List<Card> ordered) {
        if (new HashSet<>(ordered).size() != ordered.size()) {
            throw new IllegalArgumentException("Duplicate cards in deck order");
        }
        return new Deck(new ArrayList<>(ordered));
    }

    public static List<Card> fullDeck() {
        List<Card> all = new ArrayList<>(52);
        for (Suit s : Suit.values()) {
            for (int r = 2; r <= 14; r++) all.add(new Card(r, s));
        }
        return all;
    }

    public Card draw() {
        Card c = cards.pollFirst();
        if (c == null) throw new IllegalStateException("Deck is empty");
        return c;
    }

    public int remaining() { return cards.size(); }
}
