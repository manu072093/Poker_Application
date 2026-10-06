package com.poker.engine;

import java.util.List;

/**
 * Evaluates the best 5-card poker hand from 5 to 7 cards.
 * The returned score is an int: a higher score always means a stronger hand,
 * and equal scores mean an exact tie (split pot).
 */
public final class HandEvaluator {
    private HandEvaluator() {}

    public enum Category {
        HIGH_CARD, PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT,
        FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH
    }

    public static Category category(int score) {
        return Category.values()[score >>> 20];
    }

    public static int evaluate(List<Card> cards) {
        int n = cards.size();
        if (n < 5 || n > 7) throw new IllegalArgumentException("Need 5-7 cards, got " + n);
        int best = -1;
        Card[] five = new Card[5];
        for (int mask = 0; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) != 5) continue;
            int k = 0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) five[k++] = cards.get(i);
            }
            best = Math.max(best, score5(five));
        }
        return best;
    }

    private static int score5(Card[] five) {
        int[] cnt = new int[15];
        boolean flush = true;
        for (Card c : five) {
            cnt[c.rank()]++;
            if (c.suit() != five[0].suit()) flush = false;
        }

        // Order ranks by (group size desc, rank desc): e.g. 9 9 K Q J for a pair of nines.
        int[] ord = new int[5];
        int k = 0, fours = 0, threes = 0, pairs = 0;
        for (int size = 4; size >= 1; size--) {
            for (int r = 14; r >= 2; r--) {
                if (cnt[r] != size) continue;
                for (int i = 0; i < size; i++) ord[k++] = r;
                if (size == 4) fours++;
                else if (size == 3) threes++;
                else if (size == 2) pairs++;
            }
        }

        boolean straight = false;
        if (fours + threes + pairs == 0) {
            if (ord[0] - ord[4] == 4) {
                straight = true;
            } else if (ord[0] == 14 && ord[1] == 5 && ord[2] == 4 && ord[3] == 3 && ord[4] == 2) {
                straight = true; // wheel: A-2-3-4-5, five-high
                ord = new int[]{5, 4, 3, 2, 1};
            }
        }

        Category cat;
        if (straight && flush) cat = Category.STRAIGHT_FLUSH;
        else if (fours == 1) cat = Category.FOUR_OF_A_KIND;
        else if (threes == 1 && pairs == 1) cat = Category.FULL_HOUSE;
        else if (flush) cat = Category.FLUSH;
        else if (straight) cat = Category.STRAIGHT;
        else if (threes == 1) cat = Category.THREE_OF_A_KIND;
        else if (pairs == 2) cat = Category.TWO_PAIR;
        else if (pairs == 1) cat = Category.PAIR;
        else cat = Category.HIGH_CARD;

        return (cat.ordinal() << 20)
                | (ord[0] << 16) | (ord[1] << 12) | (ord[2] << 8) | (ord[3] << 4) | ord[4];
    }
}
