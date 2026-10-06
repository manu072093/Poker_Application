package com.poker.game;

import com.poker.engine.Card;
import com.poker.engine.IllegalActionException;
import com.poker.engine.PokerTable;
import com.poker.engine.PokerTable.ActionType;
import com.poker.engine.PokerTable.LegalActions;
import com.poker.engine.PokerTable.Phase;
import com.poker.engine.Seat;
import com.poker.game.bot.BotContext;
import com.poker.game.bot.BotDecision;
import com.poker.game.bot.BotStrategy;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One game room: a PokerTable plus joins/leaves, bots, turn timers and chat.
 *
 * Concurrency model: ALL mutable state is confined to a single-thread scheduler owned by the room.
 * Public methods hand work to that thread and wait for the result, so actions at a table are
 * processed strictly one at a time and no explicit locking is needed.
 */
public final class TableRoom {
    private static final Logger LOG = Logger.getLogger(TableRoom.class.getName());
    private static final String[] BOT_NAMES = {"Ace", "Bluff", "Chip", "Dealer", "Edge", "Flop", "Gambit", "Hustle"};

    public record Settings(long turnMillis, long nextHandDelayMillis,
                           long botDelayMinMillis, long botDelayMaxMillis) {
        public static Settings defaults() { return new Settings(20_000, 5_000, 700, 1_800); }
    }

    private final String id;
    private final String name;
    private final int smallBlind, bigBlind, minBuyIn, maxBuyIn, maxSeats;
    private final Settings settings;
    private final RoomEvents events;
    private final BotStrategy botStrategy;
    private final Random rng = new Random();
    private final PokerTable table;
    private final ScheduledExecutorService exec;

    // --- state below is touched only on the room thread ---
    private final Set<String> humans = new LinkedHashSet<>();
    private final Set<String> bots = new LinkedHashSet<>();
    private final Map<String, Integer> pendingJoin = new LinkedHashMap<>();
    private final Set<String> pendingLeave = new HashSet<>();
    private final Map<String, Integer> startStacks = new HashMap<>();
    private final Map<String, Integer> timeouts = new HashMap<>();
    private ScheduledFuture<?> timer;
    private long turnDeadline;
    private boolean nextHandScheduled;

    // --- read from other threads (lobby listing) ---
    private volatile int seatedCount;
    private volatile int humanCount;

    // chat rate limiting (own lock)
    private final Map<String, Deque<Long>> chatTimes = new HashMap<>();

    public TableRoom(String id, String name, int smallBlind, int bigBlind, int minBuyIn, int maxBuyIn,
                     int maxSeats, Settings settings, RoomEvents events, BotStrategy botStrategy) {
        this.id = id;
        this.name = name;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.minBuyIn = minBuyIn;
        this.maxBuyIn = maxBuyIn;
        this.maxSeats = maxSeats;
        this.settings = settings;
        this.events = events;
        this.botStrategy = botStrategy;
        this.table = new PokerTable(smallBlind, bigBlind);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    // ================================================================== public API

    public String getId() { return id; }

    public RoomInfo info() {
        return new RoomInfo(id, name, smallBlind, bigBlind, minBuyIn, maxBuyIn, maxSeats, seatedCount, humanCount);
    }

    public void addBot() {
        call(() -> {
            if (inProgress()) throw new IllegalStateException("Cannot add a bot during a hand");
            if (table.getSeats().size() + pendingJoin.size() >= maxSeats) throw new IllegalStateException("Table is full");
            String botName = BOT_NAMES[bots.size() % BOT_NAMES.length];
            String botId = "bot:" + botName;
            table.addPlayer(botId, botName + " (bot)", botStack());
            bots.add(botId);
            broadcast();
            return null;
        });
    }

    /** The caller must already have debited {@code buyIn} from the player's balance. */
    public void join(String user, int buyIn) {
        call(() -> {
            if (buyIn < minBuyIn || buyIn > maxBuyIn) {
                throw new IllegalArgumentException("Buy-in must be between " + minBuyIn + " and " + maxBuyIn);
            }
            if (humans.contains(user) || pendingJoin.containsKey(user)) {
                throw new IllegalStateException("You are already at this table");
            }
            if (table.getSeats().size() + pendingJoin.size() >= maxSeats) {
                throw new IllegalStateException("Table is full");
            }
            if (inProgress()) {
                pendingJoin.put(user, buyIn);
                events.notice(user, id, "You will be seated when the current hand ends");
            } else {
                seat(user, buyIn);
            }
            broadcast();
            try {
                maybeStartHand();
            } catch (RuntimeException e) {
                // The player IS seated by now, so never propagate: the caller would refund their buy-in.
                LOG.log(Level.SEVERE, "Room " + id + " failed to start a hand", e);
            }
            return null;
        });
    }

    public void leave(String user) {
        call(() -> {
            Integer waiting = pendingJoin.remove(user);
            if (waiting != null) {
                events.cashOut(user, waiting);
            } else if (humans.contains(user)) {
                if (!inProgress()) {
                    removeHuman(user);
                } else {
                    pendingLeave.add(user);
                    events.notice(user, id, "You will leave when the current hand ends");
                    if (user.equals(table.getToActId())) autoAction(user, false);
                }
            } else {
                throw new IllegalStateException("You are not at this table");
            }
            broadcast();
            return null;
        });
    }

    public void act(String user, ActionType type, int amount) {
        call(() -> {
            if (!user.equals(table.getToActId())) throw new IllegalActionException("Not your turn");
            table.act(user, type, amount);
            timeouts.put(user, 0);
            afterStateChange();
            return null;
        });
    }

    public void chat(String user, String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) throw new IllegalArgumentException("Empty message");
        if (clean.length() > 200) throw new IllegalArgumentException("Message too long (max 200 characters)");
        long now = System.currentTimeMillis();
        synchronized (chatTimes) {
            Deque<Long> q = chatTimes.computeIfAbsent(user, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > 10_000) q.pollFirst();
            if (q.size() >= 5) throw new IllegalStateException("You are sending messages too quickly");
            q.addLast(now);
        }
        events.chat(id, new ChatMessage(user, clean, now));
    }

    /** Sends the current state to one user (e.g. right after they subscribe). */
    public void sendStateTo(String user) {
        call(() -> {
            if (humans.contains(user)) events.privateState(user, buildState(user));
            else events.privateState(user, buildState(null));
            return null;
        });
    }

    public void close() { exec.shutdownNow(); }

    // ================================================================== room-thread internals

    private boolean inProgress() {
        Phase p = table.getPhase();
        return p == Phase.PREFLOP || p == Phase.FLOP || p == Phase.TURN || p == Phase.RIVER;
    }

    private int botStack() { return Math.min(maxBuyIn, Math.max(minBuyIn, bigBlind * 50)); }

    private void seat(String user, int buyIn) {
        table.addPlayer(user, user, buyIn);
        humans.add(user);
        timeouts.put(user, 0);
    }

    private int stackOf(String playerId) {
        for (Seat s : table.getSeats()) if (s.getId().equals(playerId)) return s.getStack();
        throw new NoSuchElementException(playerId);
    }

    private void removeHuman(String user) {
        int stack = stackOf(user);
        table.removePlayer(user);
        humans.remove(user);
        pendingLeave.remove(user);
        timeouts.remove(user);
        events.cashOut(user, stack);
        events.notice(user, id, "You left the table with " + stack + " chips");
    }

    private void maybeStartHand() {
        if (inProgress() || nextHandScheduled) return;
        if (humans.isEmpty()) { broadcast(); return; } // do not burn CPU on bot-only tables
        int funded = 0;
        for (Seat s : table.getSeats()) if (s.getStack() > 0) funded++;
        if (funded < 2) { broadcast(); return; }

        startStacks.clear();
        for (Seat s : table.getSeats()) startStacks.put(s.getId(), s.getStack());
        table.startHand();
        afterStateChange();
    }

    private void afterStateChange() {
        cancelTimer();
        if (table.getPhase() == Phase.HAND_OVER) {
            turnDeadline = 0;
            broadcast();
            onHandOver();
        } else {
            scheduleTurn();
            broadcast();
        }
    }

    private void scheduleTurn() {
        turnDeadline = 0;
        String who = table.getToActId();
        if (who == null) return;
        if (bots.contains(who)) {
            long span = Math.max(1, settings.botDelayMaxMillis() - settings.botDelayMinMillis() + 1);
            long delay = settings.botDelayMinMillis() + (long) (rng.nextDouble() * span);
            timer = exec.schedule(() -> guard(() -> botAct(who)), delay, TimeUnit.MILLISECONDS);
        } else if (pendingLeave.contains(who)) {
            exec.execute(() -> guard(() -> autoAction(who, false)));
        } else {
            turnDeadline = System.currentTimeMillis() + settings.turnMillis();
            timer = exec.schedule(() -> guard(() -> autoAction(who, true)), settings.turnMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(false); timer = null; }
    }

    /** Check if possible, otherwise fold. Used for timeouts and for players who are leaving. */
    private void autoAction(String who, boolean countTimeout) {
        if (!who.equals(table.getToActId())) return;
        LegalActions la = table.legalActions();
        table.act(who, la.canCheck() ? ActionType.CHECK : ActionType.FOLD, 0);
        if (countTimeout) {
            int n = timeouts.merge(who, 1, Integer::sum);
            if (n >= 2) {
                pendingLeave.add(who);
                events.notice(who, id, "You timed out twice and will be removed from the table");
            } else {
                events.notice(who, id, "You ran out of time");
            }
        }
        afterStateChange();
    }

    private void botAct(String botId) {
        if (!botId.equals(table.getToActId())) return;
        LegalActions la = table.legalActions();
        List<Card> hole = List.of();
        for (Seat s : table.getSeats()) if (s.getId().equals(botId)) hole = s.getHole();
        BotContext ctx = new BotContext(hole, table.getBoard(), table.getPot(), table.getCurrentBet(), bigBlind, la);
        BotDecision d = botStrategy.decide(ctx);
        try {
            table.act(botId, d.type(), d.amount());
        } catch (IllegalActionException e) {
            LOG.log(Level.WARNING, "Bot made an illegal move " + d + ": " + e.getMessage());
            table.act(botId, la.canCheck() ? ActionType.CHECK : ActionType.FOLD, 0);
        }
        afterStateChange();
    }

    private void onHandOver() {
        for (Seat s : table.getSeats()) {
            if (humans.contains(s.getId()) && startStacks.containsKey(s.getId())) {
                events.handFinished(s.getId(), s.getStack() - startStacks.get(s.getId()));
            }
        }
        nextHandScheduled = true;
        timer = exec.schedule(() -> guard(this::cleanupAndStart), settings.nextHandDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void cleanupAndStart() {
        nextHandScheduled = false;

        for (String u : new ArrayList<>(humans)) {
            boolean busted = stackOf(u) == 0;
            if (pendingLeave.contains(u) || busted) {
                if (busted) events.notice(u, id, "You are out of chips");
                removeHuman(u);
            }
        }
        for (String b : bots) {
            int stack = stackOf(b);
            if (stack < bigBlind * 10) table.addChips(b, botStack() - stack); // bots rebuy automatically
        }
        for (Map.Entry<String, Integer> e : new ArrayList<>(pendingJoin.entrySet())) {
            if (table.getSeats().size() < maxSeats) seat(e.getKey(), e.getValue());
            else events.cashOut(e.getKey(), e.getValue());
        }
        pendingJoin.clear();

        broadcast();
        maybeStartHand();
    }

    private void broadcast() {
        seatedCount = table.getSeats().size();
        humanCount = humans.size();
        events.publicState(id, buildState(null));
        for (String u : humans) events.privateState(u, buildState(u));
    }

    private RoomState buildState(String viewer) {
        LegalActions legal = null;
        if (viewer != null && inProgress() && viewer.equals(table.getToActId())) legal = table.legalActions();
        return new RoomState(id, name, table.viewFor(viewer), legal, turnDeadline);
    }

    private void guard(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Room " + id + " task failed", e);
        }
    }

    private <T> T call(Callable<T> task) {
        try {
            return exec.submit(task).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        } catch (TimeoutException e) {
            throw new IllegalStateException("Table is busy, try again");
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Table is closed");
        }
    }
}
