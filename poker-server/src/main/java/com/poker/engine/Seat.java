package com.poker.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A player's seat at a table. Mutation is restricted to the engine package. */
public final class Seat {
    private final String id;
    private final String name;

    int stack;
    int bet;          // chips committed in the current betting round
    int contributed;  // chips committed in the whole hand
    boolean inHand;   // dealt into the current hand
    boolean folded;
    boolean allIn;
    boolean hasActed; // acted since the last (full) raise
    boolean canRaise; // false if action was not reopened by a short all-in
    final List<Card> hole = new ArrayList<>(2);

    Seat(String id, String name, int stack) {
        this.id = id;
        this.name = name;
        this.stack = stack;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getStack() { return stack; }
    public int getBet() { return bet; }
    public boolean isFolded() { return folded; }
    public boolean isAllIn() { return allIn; }
    public boolean isActive() { return inHand && !folded; }
    public List<Card> getHole() { return Collections.unmodifiableList(hole); }
}
