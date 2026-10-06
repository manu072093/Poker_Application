package com.poker.engine;

import com.poker.engine.HandEvaluator.Category;
import com.poker.engine.PokerTable.ActionType;
import com.poker.engine.PokerTable.LegalActions;
import com.poker.engine.PokerTable.Phase;

import java.util.*;

/** Dependency-free test runner. Run: ./run-tests.sh */
public class EngineSelfTest {
    static int passed = 0, failed = 0;

    static void check(boolean ok, String name) {
        if (ok) { passed++; } else { failed++; System.out.println("  FAIL: " + name); }
    }

    static int ev(String cards) {
        List<Card> list = new ArrayList<>();
        for (String s : cards.split(" ")) list.add(Card.parse(s));
        return HandEvaluator.evaluate(list);
    }

    static Category cat(String cards) { return HandEvaluator.category(ev(cards)); }

    static Deck stacked(String... top) {
        List<Card> order = new ArrayList<>();
        Set<Card> used = new HashSet<>();
        for (String s : top) { Card c = Card.parse(s); order.add(c); used.add(c); }
        for (Card c : Deck.fullDeck()) if (!used.contains(c)) order.add(c);
        return Deck.ofOrder(order);
    }

    static PokerTable table(int sb, int bb, Deck d) { return new PokerTable(sb, bb, () -> d); }

    static boolean throwsIllegal(Runnable r) {
        try { r.run(); return false; } catch (IllegalActionException e) { return true; }
    }

    static int stackOf(PokerTable t, String id) {
        for (Seat s : t.getSeats()) if (s.getId().equals(id)) return s.getStack();
        throw new NoSuchElementException(id);
    }

    public static void main(String[] args) {
        evaluatorTests();
        deckTests();
        headsUpAllIn();
        sidePots();
        uncontestedPot();
        splitPot();
        shortAllInDoesNotReopen();
        illegalActions();
        hiddenCards();
        simulation();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void evaluatorTests() {
        System.out.println("Hand evaluator");
        check(cat("As Ks Qs Js Ts 2c 3d") == Category.STRAIGHT_FLUSH, "royal flush is straight flush");
        check(cat("Ac 2c 3c 4c 5c Kd 9h") == Category.STRAIGHT_FLUSH, "steel wheel");
        check(ev("As Ks Qs Js Ts 2c 3d") > ev("9s 8s 7s 6s 5s Ac Kd"), "royal > lower straight flush");
        check(ev("9s 8s 7s 6s 5s Ac Kd") > ev("9c 9d 9h 9s Ac 2d 3h"), "straight flush > quads");
        check(ev("9c 9d 9h 9s Ac 2d 3h") > ev("Kc Kd Kh 2s 2c 7d 9h"), "quads > full house");
        check(ev("Kc Kd Kh 2s 2c 7d 9h") > ev("Ah 9h 7h 4h 2h Kc Qd"), "full house > flush");
        check(ev("Ah 9h 7h 4h 2h Kc Qd") > ev("9c 8d 7h 6s 5c 2d 2h"), "flush > straight");
        check(ev("9c 8d 7h 6s 5c 2d 2h") > ev("7c 7d 7h Ks 2c 4d 9h"), "straight > trips");
        check(ev("7c 7d 7h Ks 2c 4d 9h") > ev("Ac Ad Kh Ks 2c 4d 9h"), "trips > two pair");
        check(ev("Ac Ad Kh Ks 2c 4d 9h") > ev("Ac Ad Kh 9s 2c 4d 7h"), "two pair > pair");
        check(ev("Ac Ad Kh 9s 2c 4d 7h") > ev("Ac Kd Qh 9s 2c 4d 7h"), "pair > high card");
        check(cat("Ac 2d 3h 4s 5c 9d Kh") == Category.STRAIGHT, "wheel is a straight");
        check(ev("2c 3d 4h 5s 6c 9d Kh") > ev("Ac 2d 3h 4s 5c 9d Kh"), "wheel loses to 6-high straight");
        check(ev("Ac Ad Kh 9s 2c 4d 7h") > ev("Ac Ad Qh 9s 2c 4d 7h"), "kicker decides pair");
        check(ev("Ac Ad Kh Ks Qc Qd 2h") > ev("Ac Ad Kh Ks 2c 3d 4h"), "three pairs uses best kicker");
        check(cat("Ac Ad Kh Ks Qc Qd 2h") == Category.TWO_PAIR, "three pairs is two pair");
        check(cat("Kc Kd Kh 2s 2c 2d 9h") == Category.FULL_HOUSE, "two trips make a full house");
        check(ev("As Ks Qs Js Ts 2c 3d") == ev("As Ks Qs Js Ts 4c 5d"), "board plays: equal scores tie");
        check(cat("Ah 9h 7h 4h 2h 3c 3d") == Category.FLUSH, "flush beats pair on 7 cards");
    }

    static void deckTests() {
        System.out.println("Deck");
        Deck d = new Deck();
        Set<Card> seen = new HashSet<>();
        for (int i = 0; i < 52; i++) seen.add(d.draw());
        check(seen.size() == 52, "52 unique cards");
        boolean threw = false;
        try { d.draw(); } catch (IllegalStateException e) { threw = true; }
        check(threw, "empty deck throws");
    }

    // Seat order A(0), B(1). Dealer = A (SB, acts first preflop). Deal order: B, A, B, A.
    static void headsUpAllIn() {
        System.out.println("Heads-up all-in");
        Deck d = stacked("Ks", "As", "Kh", "Ah", "2c", "7d", "9h", "Jc", "3c", "4d", "3d", "2d");
        PokerTable t = table(10, 20, d);
        t.addPlayer("A", "Alice", 1000);
        t.addPlayer("B", "Bob", 1000);
        t.startHand();
        check(t.getToActId().equals("A"), "dealer acts first heads-up");
        t.act("A", ActionType.ALL_IN, 0);
        t.act("B", ActionType.CALL, 0);
        check(t.getPhase() == Phase.HAND_OVER, "board ran out");
        check(t.getBoard().size() == 5, "five board cards");
        check(stackOf(t, "A") == 2000 && stackOf(t, "B") == 0, "aces win everything");
    }

    // Dealer A(100), B(300) SB, C(500) BB. Deal order: B, C, A.
    static void sidePots() {
        System.out.println("Side pots");
        Deck d = stacked("Ks", "Qs", "As", "Kh", "Qh", "Ah", "2c", "7d", "9h", "Jc", "3c", "4d", "3d", "2d");
        PokerTable t = table(10, 20, d);
        t.addPlayer("A", "A", 100);
        t.addPlayer("B", "B", 300);
        t.addPlayer("C", "C", 500);
        t.startHand();
        check(t.getToActId().equals("A"), "UTG acts first with 3 players");
        t.act("A", ActionType.ALL_IN, 0);
        t.act("B", ActionType.ALL_IN, 0);
        t.act("C", ActionType.CALL, 0);
        check(t.getPhase() == Phase.HAND_OVER, "hand finished");
        check(stackOf(t, "A") == 300, "A (aces) wins main pot of 300");
        check(stackOf(t, "B") == 400, "B (kings) wins side pot of 400");
        check(stackOf(t, "C") == 200, "C keeps uncontested remainder");
        check(t.getLastResult().awards().size() == 2, "two pots awarded");
    }

    static void uncontestedPot() {
        System.out.println("Uncontested pot");
        PokerTable t = table(10, 20, new Deck(new Random(1)));
        t.addPlayer("A", "A", 1000);
        t.addPlayer("B", "B", 1000);
        t.addPlayer("C", "C", 1000);
        t.startHand(); // dealer A, SB B, BB C
        t.act("A", ActionType.FOLD, 0);
        t.act("B", ActionType.FOLD, 0);
        check(t.getPhase() == Phase.HAND_OVER, "hand over after folds");
        check(stackOf(t, "C") == 1010 && stackOf(t, "B") == 990 && stackOf(t, "A") == 1000, "BB wins the blinds");
        check(!t.getLastResult().showdown(), "no showdown");
    }

    // Both play the board (royal flush): split.
    static void splitPot() {
        System.out.println("Split pot");
        Deck d = stacked("2c", "4d", "3c", "5d", "2h", "As", "Ks", "Qs", "2d", "Js", "3h", "Ts");
        PokerTable t = table(10, 20, d);
        t.addPlayer("A", "A", 500);
        t.addPlayer("B", "B", 500);
        t.startHand();
        t.act("A", ActionType.ALL_IN, 0);
        t.act("B", ActionType.CALL, 0);
        check(stackOf(t, "A") == 500 && stackOf(t, "B") == 500, "pot split evenly");
        check(t.getLastResult().awards().get(0).winnerIds().size() == 2, "two winners");
    }

    // A raises to 100, B (130 total) shoves for a short raise, C calls: A may only call/fold.
    static void shortAllInDoesNotReopen() {
        System.out.println("Short all-in");
        PokerTable t = table(10, 20, new Deck(new Random(7)));
        t.addPlayer("A", "A", 1000);
        t.addPlayer("B", "B", 130);
        t.addPlayer("C", "C", 1000);
        t.startHand();
        t.act("A", ActionType.RAISE, 100);
        t.act("B", ActionType.ALL_IN, 0);
        t.act("C", ActionType.CALL, 0);
        check(t.getToActId().equals("A"), "action returns to A");
        LegalActions la = t.legalActions();
        check(!la.canRaise(), "A cannot re-raise a short all-in");
        check(throwsIllegal(() -> t.act("A", ActionType.RAISE, 500)), "raise rejected");
        t.act("A", ActionType.CALL, 0);
        check(t.getPot() == 390, "pot is 130 x 3");
    }

    static void illegalActions() {
        System.out.println("Illegal actions");
        PokerTable t = table(10, 20, new Deck(new Random(3)));
        t.addPlayer("A", "A", 1000);
        t.addPlayer("B", "B", 1000);
        t.addPlayer("C", "C", 1000);
        t.startHand();
        check(throwsIllegal(() -> t.act("B", ActionType.FOLD, 0)), "out of turn rejected");
        check(throwsIllegal(() -> t.act("A", ActionType.CHECK, 0)), "cannot check facing a bet");
        check(throwsIllegal(() -> t.act("A", ActionType.RAISE, 30)), "below min raise rejected");
        check(throwsIllegal(() -> t.act("A", ActionType.RAISE, 5000)), "more than stack rejected");
        t.act("A", ActionType.CALL, 0);
        t.act("B", ActionType.CALL, 0);
        check(t.getToActId().equals("C"), "BB gets the option");
        t.act("C", ActionType.CHECK, 0);
        check(t.getPhase() == Phase.FLOP, "flop dealt");
        check(t.getToActId().equals("B"), "first to act postflop is left of dealer");
    }

    static void hiddenCards() {
        System.out.println("Hidden cards");
        PokerTable t = table(10, 20, new Deck(new Random(5)));
        t.addPlayer("A", "A", 1000);
        t.addPlayer("B", "B", 1000);
        t.startHand();
        PokerTable.TableView va = t.viewFor("A");
        boolean ownVisible = false, otherHidden = false;
        for (PokerTable.SeatView sv : va.seats()) {
            if (sv.id().equals("A")) ownVisible = sv.holeCards().size() == 2;
            if (sv.id().equals("B")) otherHidden = sv.holeCards().isEmpty() && sv.hasCards();
        }
        check(ownVisible, "viewer sees own cards");
        check(otherHidden, "viewer does not see opponent cards");
    }

    static void simulation() {
        System.out.println("Random simulation (5000 hands, 6 players)");
        Random rng = new Random(42);
        PokerTable t = new PokerTable(10, 20, () -> new Deck(rng));
        long[] total = {0};
        for (int i = 0; i < 6; i++) { t.addPlayer("P" + i, "P" + i, 1000); total[0] += 1000; }

        int showdowns = 0, multiPot = 0, hands = 0;
        boolean conserved = true, noNegative = true, finished = true;

        for (int h = 0; h < 5000; h++) {
            int funded = 0;
            for (Seat s : t.getSeats()) if (s.getStack() > 0) funded++;
            if (funded < 2) {
                for (Seat s : t.getSeats()) {
                    int add = 1000 - s.getStack();
                    if (add > 0) { t.addChips(s.getId(), add); total[0] += add; }
                }
            }
            t.startHand();
            int guard = 0;
            while (t.getPhase() != Phase.HAND_OVER && guard++ < 400) {
                LegalActions la = t.legalActions();
                double p = rng.nextDouble();
                if (p < 0.12 && la.toCall() > 0) t.act(la.playerId(), ActionType.FOLD, 0);
                else if (p < 0.32 && la.canRaise()) {
                    if (rng.nextInt(4) == 0) t.act(la.playerId(), ActionType.ALL_IN, 0);
                    else {
                        int amt = la.minRaiseTo() + rng.nextInt(la.maxRaiseTo() - la.minRaiseTo() + 1);
                        t.act(la.playerId(), ActionType.RAISE, amt);
                    }
                } else if (la.canCheck()) t.act(la.playerId(), ActionType.CHECK, 0);
                else t.act(la.playerId(), ActionType.CALL, 0);
            }
            if (t.getPhase() != Phase.HAND_OVER) { finished = false; break; }
            hands++;
            long sum = 0;
            for (Seat s : t.getSeats()) { sum += s.getStack(); if (s.getStack() < 0) noNegative = false; }
            if (sum != total[0]) { conserved = false; break; }
            if (t.getLastResult().showdown()) showdowns++;
            if (t.getLastResult().awards().size() > 1) multiPot++;
        }
        System.out.println("  hands=" + hands + " showdowns=" + showdowns + " handsWithSidePots=" + multiPot);
        check(finished, "every hand terminates");
        check(conserved, "chips conserved after every hand");
        check(noNegative, "no negative stacks");
        check(showdowns > 500, "plenty of showdowns exercised");
    }
}
