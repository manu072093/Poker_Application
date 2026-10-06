package com.poker.game.bot;

import com.poker.engine.Card;
import com.poker.engine.HandEvaluator;
import com.poker.engine.HandEvaluator.Category;
import com.poker.engine.PokerTable.ActionType;
import com.poker.engine.PokerTable.LegalActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simple rule-based opponent: estimates hand strength in [0,1], compares it with pot odds,
 * and adds a little randomness so it is not perfectly predictable.
 */
public final class SimpleBot implements BotStrategy {
    private final Random rng;

    public SimpleBot() { this(new Random()); }

    public SimpleBot(Random rng) { this.rng = rng; }

    @Override
    public BotDecision decide(BotContext c) {
        LegalActions la = c.legal();
        double strength = strength(c);

        if (la.toCall() == 0) {
            if (la.canRaise() && strength > 0.6 && rng.nextDouble() < 0.6) return raise(c, strength);
            if (la.canRaise() && strength < 0.3 && rng.nextDouble() < 0.07) return raise(c, 0.3); // bluff
            return new BotDecision(ActionType.CHECK, 0);
        }

        double potOdds = (double) la.toCall() / (c.pot() + la.toCall());
        if (strength > potOdds + 0.25 && la.canRaise() && rng.nextDouble() < 0.4) return raise(c, strength);
        if (strength + 0.05 * rng.nextDouble() >= potOdds) return new BotDecision(ActionType.CALL, 0);
        return new BotDecision(rng.nextDouble() < 0.05 ? ActionType.CALL : ActionType.FOLD, 0);
    }

    private BotDecision raise(BotContext c, double strength) {
        LegalActions la = c.legal();
        int target = c.currentBet() + (int) (Math.max(c.pot(), c.bigBlind()) * (0.4 + 0.6 * Math.min(1.0, strength)));
        target = Math.max(la.minRaiseTo(), Math.min(la.maxRaiseTo(), target));
        return new BotDecision(ActionType.RAISE, target);
    }

    static double strength(BotContext c) {
        return c.board().isEmpty() ? preflop(c.hole()) : postflop(c.hole(), c.board());
    }

    private static double preflop(List<Card> hole) {
        Card a = hole.get(0), b = hole.get(1);
        int hi = Math.max(a.rank(), b.rank()), lo = Math.min(a.rank(), b.rank());
        if (hi == lo) return 0.5 + (hi - 2) / 24.0; // 22 = 0.5 ... AA = 1.0
        double s = 0.15 + (hi - 2) / 14.0 * 0.3 + (lo - 2) / 14.0 * 0.2;
        if (a.suit() == b.suit()) s += 0.05;
        if (hi - lo <= 2) s += 0.05;
        return Math.min(s, 0.7);
    }

    private static double postflop(List<Card> hole, List<Card> board) {
        List<Card> all = new ArrayList<>(hole);
        all.addAll(board);
        Category cat = HandEvaluator.category(HandEvaluator.evaluate(all));
        switch (cat) {
            case HIGH_CARD:
                return 0.12 + (Math.max(hole.get(0).rank(), hole.get(1).rank()) - 2) / 14.0 * 0.1;
            case PAIR: {
                int topBoard = 0;
                for (Card c : board) topBoard = Math.max(topBoard, c.rank());
                int mine = 0;
                if (hole.get(0).rank() == hole.get(1).rank()) mine = hole.get(0).rank();
                for (Card h : hole) {
                    for (Card c : board) if (h.rank() == c.rank()) mine = Math.max(mine, h.rank());
                }
                if (mine == 0) return 0.2; // the pair is on the board
                return mine >= topBoard ? 0.58 : 0.42;
            }
            case TWO_PAIR: return 0.72;
            case THREE_OF_A_KIND: return 0.8;
            case STRAIGHT: return 0.88;
            case FLUSH: return 0.9;
            case FULL_HOUSE: return 0.95;
            case FOUR_OF_A_KIND: return 0.98;
            default: return 1.0;
        }
    }
}
