package com.poker.engine;

import java.util.*;
import java.util.function.Supplier;

/**
 * A single Texas Hold'em table: seats, blinds, betting rounds, side pots and showdown.
 *
 * Pure game logic with no networking. All public methods are synchronized so a caller
 * can safely drive one table from multiple threads (a per-table queue is still recommended).
 * All chip amounts are integers.
 */
public final class PokerTable {

    public enum Phase { WAITING, PREFLOP, FLOP, TURN, RIVER, HAND_OVER }

    public enum ActionType {
        FOLD, CHECK, CALL,
        /** Bet or raise; the amount is the total "raise to" for this betting round. */
        RAISE,
        ALL_IN
    }

    public record LegalActions(String playerId, int toCall, boolean canCheck,
                               boolean canRaise, int minRaiseTo, int maxRaiseTo) {}

    public record PotAward(int amount, List<String> winnerIds, String handName) {}

    public record HandResult(List<PotAward> awards, boolean showdown,
                             Map<String, List<Card>> shownHands) {}

    public record SeatView(String id, String name, int stack, int bet, boolean folded,
                           boolean allIn, boolean dealer, boolean toAct,
                           boolean hasCards, List<String> holeCards) {}

    public record TableView(Phase phase, List<String> board, int pot, int currentBet,
                            List<SeatView> seats, String toActId, HandResult result) {}

    private static final class Pot {
        int amount;
        final List<Seat> eligible;
        Pot(int amount, List<Seat> eligible) { this.amount = amount; this.eligible = eligible; }
    }

    private final List<Seat> seats = new ArrayList<>();
    private final int smallBlind;
    private final int bigBlind;
    private final Supplier<Deck> deckFactory;

    private Phase phase = Phase.WAITING;
    private Deck deck;
    private final List<Card> board = new ArrayList<>(5);
    private int dealer = -1;
    private int toAct = -1;
    private int currentBet;
    private int minRaise;
    private HandResult lastResult;

    public PokerTable(int smallBlind, int bigBlind) {
        this(smallBlind, bigBlind, Deck::new);
    }

    public PokerTable(int smallBlind, int bigBlind, Supplier<Deck> deckFactory) {
        if (smallBlind <= 0 || bigBlind < smallBlind) throw new IllegalArgumentException("Bad blinds");
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.deckFactory = deckFactory;
    }

    // ------------------------------------------------------------------ seating

    public synchronized void addPlayer(String id, String name, int stack) {
        requireBetweenHands();
        if (stack < 0) throw new IllegalArgumentException("Negative stack");
        if (findSeat(id) != null) throw new IllegalArgumentException("Duplicate player id: " + id);
        seats.add(new Seat(id, name, stack));
    }

    public synchronized void removePlayer(String id) {
        requireBetweenHands();
        Seat s = findSeat(id);
        if (s == null) throw new IllegalArgumentException("Unknown player: " + id);
        int idx = seats.indexOf(s);
        seats.remove(idx);
        if (idx <= dealer) dealer--;
    }

    public synchronized void addChips(String id, int amount) {
        requireBetweenHands();
        Seat s = findSeat(id);
        if (s == null || amount < 0) throw new IllegalArgumentException("Bad rebuy");
        s.stack += amount;
    }

    // ------------------------------------------------------------------ hand flow

    public synchronized void startHand() {
        requireBetweenHands();
        int funded = 0;
        for (Seat s : seats) if (s.stack > 0) funded++;
        if (funded < 2) throw new IllegalStateException("Need at least 2 players with chips");

        board.clear();
        lastResult = null;
        for (Seat s : seats) {
            s.hole.clear();
            s.bet = 0;
            s.contributed = 0;
            s.inHand = s.stack > 0;
            s.folded = false;
            s.allIn = false;
            s.hasActed = false;
            s.canRaise = true;
        }

        deck = deckFactory.get();
        dealer = nextWhere(dealer, s -> s.inHand);

        // Deal two hole cards each, starting left of the dealer.
        List<Integer> order = new ArrayList<>();
        int n = seats.size();
        for (int i = 1; i <= n; i++) {
            int idx = (dealer + i) % n;
            if (seats.get(idx).inHand) order.add(idx);
        }
        for (int round = 0; round < 2; round++) {
            for (int idx : order) seats.get(idx).hole.add(deck.draw());
        }

        // Blinds. Heads-up: the dealer posts the small blind.
        int sb, bb;
        if (funded == 2) {
            sb = dealer;
            bb = nextWhere(dealer, s -> s.inHand);
        } else {
            sb = nextWhere(dealer, s -> s.inHand);
            bb = nextWhere(sb, s -> s.inHand);
        }
        commit(seats.get(sb), Math.min(smallBlind, seats.get(sb).stack));
        commit(seats.get(bb), Math.min(bigBlind, seats.get(bb).stack));

        currentBet = bigBlind;
        minRaise = bigBlind;
        phase = Phase.PREFLOP;
        toAct = nextActable(bb);
        if (roundComplete()) advance();
    }

    public synchronized void act(String playerId, ActionType type, int amount) {
        requireInHand();
        Seat s = seats.get(toAct);
        if (!s.getId().equals(playerId)) throw new IllegalActionException("Not your turn");
        int toCall = currentBet - s.bet;

        switch (type) {
            case FOLD -> s.folded = true;
            case CHECK -> {
                if (toCall != 0) throw new IllegalActionException("Cannot check, you must call or fold");
            }
            case CALL -> {
                if (toCall == 0) throw new IllegalActionException("Nothing to call, use check");
                commit(s, Math.min(toCall, s.stack));
            }
            case RAISE -> {
                int maxTo = s.bet + s.stack;
                if (!s.canRaise) throw new IllegalActionException("Betting was not reopened to you");
                if (amount > maxTo) throw new IllegalActionException("Not enough chips");
                if (amount <= currentBet) throw new IllegalActionException("Raise must exceed the current bet");
                if (amount < currentBet + minRaise && amount != maxTo) {
                    throw new IllegalActionException("Minimum raise is to " + (currentBet + minRaise));
                }
                applyRaise(s, amount);
            }
            case ALL_IN -> {
                int total = s.bet + s.stack;
                if (total <= currentBet) {
                    commit(s, s.stack); // all-in for less than (or exactly) a call
                } else {
                    if (!s.canRaise) throw new IllegalActionException("Betting was not reopened to you");
                    applyRaise(s, total);
                }
            }
        }

        s.hasActed = true;
        afterAction();
    }

    public synchronized LegalActions legalActions() {
        requireInHand();
        Seat s = seats.get(toAct);
        int toCall = currentBet - s.bet;
        int maxTo = s.bet + s.stack;
        boolean canRaise = s.canRaise && s.stack > toCall;
        int minTo = Math.min(currentBet + minRaise, maxTo);
        return new LegalActions(s.getId(), Math.min(toCall, s.stack), toCall == 0,
                canRaise, minTo, maxTo);
    }

    // ------------------------------------------------------------------ queries

    public synchronized Phase getPhase() { return phase; }
    public synchronized List<Card> getBoard() { return List.copyOf(board); }
    public synchronized HandResult getLastResult() { return lastResult; }
    public synchronized int getCurrentBet() { return currentBet; }
    public synchronized String getToActId() { return toAct < 0 ? null : seats.get(toAct).getId(); }
    public synchronized List<Seat> getSeats() { return Collections.unmodifiableList(new ArrayList<>(seats)); }
    public synchronized int getPot() {
        int total = 0;
        for (Seat s : seats) total += s.contributed;
        return total;
    }

    /** Builds the state a given player is allowed to see (own hole cards only, plus showdown reveals). */
    public synchronized TableView viewFor(String viewerId) {
        boolean showdownReveal = phase == Phase.HAND_OVER && lastResult != null && lastResult.showdown();
        List<SeatView> views = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            boolean reveal = s.getId().equals(viewerId) || (showdownReveal && s.isActive());
            List<String> cards = new ArrayList<>();
            if (reveal) for (Card c : s.hole) cards.add(c.toString());
            views.add(new SeatView(s.getId(), s.getName(), s.stack, s.bet, s.folded, s.allIn,
                    i == dealer, i == toAct, !s.hole.isEmpty(), cards));
        }
        List<String> boardStr = new ArrayList<>();
        for (Card c : board) boardStr.add(c.toString());
        return new TableView(phase, boardStr, getPot(), currentBet, views,
                getToActId(), phase == Phase.HAND_OVER ? lastResult : null);
    }

    // ------------------------------------------------------------------ internals

    private void applyRaise(Seat s, int raiseTo) {
        int raiseSize = raiseTo - currentBet;
        boolean fullRaise = raiseSize >= minRaise;
        commit(s, raiseTo - s.bet);
        for (Seat o : seats) {
            if (o == s || !o.isActive() || o.allIn) continue;
            if (fullRaise) {
                o.canRaise = true;
            } else if (o.hasActed) {
                o.canRaise = false; // a short all-in does not reopen raising for those who already acted
            }
            o.hasActed = false;
        }
        if (fullRaise) minRaise = raiseSize;
        currentBet = raiseTo;
        s.canRaise = true;
    }

    private void commit(Seat s, int amount) {
        s.stack -= amount;
        s.bet += amount;
        s.contributed += amount;
        if (s.stack == 0) s.allIn = true;
    }

    private void afterAction() {
        List<Seat> active = activeSeats();
        if (active.size() == 1) {
            awardUncontested(active.get(0));
            return;
        }
        if (roundComplete()) advance();
        else toAct = nextActable(toAct);
    }

    private boolean roundComplete() {
        List<Seat> actable = actableSeats();
        if (actable.isEmpty()) return true;
        if (actable.size() == 1 && actable.get(0).bet >= currentBet) return true; // nobody left to bet against
        for (Seat s : actable) {
            if (!s.hasActed || s.bet != currentBet) return false;
        }
        return true;
    }

    private void advance() {
        while (true) {
            for (Seat s : seats) {
                s.bet = 0;
                s.hasActed = false;
                s.canRaise = true;
            }
            currentBet = 0;
            minRaise = bigBlind;

            switch (phase) {
                case PREFLOP -> {
                    deck.draw(); // burn
                    for (int i = 0; i < 3; i++) board.add(deck.draw());
                    phase = Phase.FLOP;
                }
                case FLOP -> {
                    deck.draw();
                    board.add(deck.draw());
                    phase = Phase.TURN;
                }
                case TURN -> {
                    deck.draw();
                    board.add(deck.draw());
                    phase = Phase.RIVER;
                }
                case RIVER -> {
                    showdown();
                    return;
                }
                default -> throw new IllegalStateException("Cannot advance from " + phase);
            }

            if (actableSeats().size() >= 2) {
                toAct = nextActable(dealer);
                return;
            }
            // Fewer than two players can still bet: run out the board.
        }
    }

    private void showdown() {
        Map<String, Integer> scores = new HashMap<>();
        Map<String, List<Card>> shown = new LinkedHashMap<>();
        for (Seat s : activeSeats()) {
            List<Card> seven = new ArrayList<>(s.hole);
            seven.addAll(board);
            scores.put(s.getId(), HandEvaluator.evaluate(seven));
            shown.put(s.getId(), List.copyOf(s.hole));
        }

        // Seats in order starting left of the dealer; odd chips go to the earliest winner in this order.
        List<Seat> orderFromDealer = new ArrayList<>();
        for (int i = 1; i <= seats.size(); i++) orderFromDealer.add(seats.get((dealer + i) % seats.size()));

        List<PotAward> awards = new ArrayList<>();
        for (Pot pot : buildPots()) {
            if (pot.eligible.size() == 1) {
                Seat only = pot.eligible.get(0);
                only.stack += pot.amount;
                awards.add(new PotAward(pot.amount, List.of(only.getId()), "RETURNED_BET"));
                continue;
            }
            int best = -1;
            for (Seat s : pot.eligible) best = Math.max(best, scores.get(s.getId()));
            List<Seat> winners = new ArrayList<>();
            for (Seat s : orderFromDealer) {
                if (pot.eligible.contains(s) && scores.get(s.getId()) == best) winners.add(s);
            }
            int share = pot.amount / winners.size();
            int remainder = pot.amount % winners.size();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < winners.size(); i++) {
                winners.get(i).stack += share + (i < remainder ? 1 : 0);
                ids.add(winners.get(i).getId());
            }
            awards.add(new PotAward(pot.amount, ids, HandEvaluator.category(best).name()));
        }

        lastResult = new HandResult(awards, true, shown);
        phase = Phase.HAND_OVER;
        toAct = -1;
    }

    private void awardUncontested(Seat winner) {
        int total = getPot();
        winner.stack += total;
        lastResult = new HandResult(List.of(new PotAward(total, List.of(winner.getId()), "UNCONTESTED")),
                false, Map.of());
        phase = Phase.HAND_OVER;
        toAct = -1;
    }

    /** Splits contributions into a main pot and side pots by contribution level. */
    private List<Pot> buildPots() {
        TreeSet<Integer> levels = new TreeSet<>();
        for (Seat s : seats) if (s.inHand && s.contributed > 0) levels.add(s.contributed);

        List<Pot> pots = new ArrayList<>();
        int prev = 0;
        for (int level : levels) {
            int amount = 0;
            for (Seat s : seats) {
                if (s.inHand) amount += Math.max(0, Math.min(s.contributed, level) - prev);
            }
            List<Seat> eligible = new ArrayList<>();
            for (Seat s : seats) if (s.isActive() && s.contributed >= level) eligible.add(s);
            prev = level;

            if (eligible.isEmpty()) {
                // Only folded players contributed at this level: fold it into the previous pot.
                if (!pots.isEmpty()) pots.get(pots.size() - 1).amount += amount;
                continue;
            }
            if (!pots.isEmpty() && pots.get(pots.size() - 1).eligible.equals(eligible)) {
                pots.get(pots.size() - 1).amount += amount;
            } else {
                pots.add(new Pot(amount, eligible));
            }
        }
        return pots;
    }

    private List<Seat> activeSeats() {
        List<Seat> list = new ArrayList<>();
        for (Seat s : seats) if (s.isActive()) list.add(s);
        return list;
    }

    private List<Seat> actableSeats() {
        List<Seat> list = new ArrayList<>();
        for (Seat s : seats) if (s.isActive() && !s.allIn) list.add(s);
        return list;
    }

    private int nextActable(int from) {
        return nextWhere(from, s -> s.isActive() && !s.allIn);
    }

    private int nextWhere(int from, java.util.function.Predicate<Seat> pred) {
        int n = seats.size();
        for (int i = 1; i <= n; i++) {
            int idx = Math.floorMod(from + i, n);
            if (pred.test(seats.get(idx))) return idx;
        }
        return -1;
    }

    private Seat findSeat(String id) {
        for (Seat s : seats) if (s.getId().equals(id)) return s;
        return null;
    }

    private void requireBetweenHands() {
        if (phase != Phase.WAITING && phase != Phase.HAND_OVER) {
            throw new IllegalStateException("A hand is in progress");
        }
    }

    private void requireInHand() {
        if (phase == Phase.WAITING || phase == Phase.HAND_OVER || toAct < 0) {
            throw new IllegalStateException("No action is currently expected");
        }
    }
}
