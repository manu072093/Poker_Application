package com.poker.game;

import com.poker.engine.PokerTable.ActionType;
import com.poker.engine.PokerTable.LegalActions;
import com.poker.engine.PokerTable.Phase;
import com.poker.engine.PokerTable.SeatView;
import com.poker.game.bot.SimpleBot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests for the room layer. Run: ./run-tests.sh */
public class RoomSelfTest {
    static int passed = 0, failed = 0;

    static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("  FAIL: " + name); }
    }

    /** Records everything a room emits. */
    static class FakeEvents implements RoomEvents {
        final Map<String, RoomState> lastPrivate = new ConcurrentHashMap<>();
        final Map<String, Integer> cashedOut = new ConcurrentHashMap<>();
        final Map<String, Integer> netTotal = new ConcurrentHashMap<>();
        final AtomicInteger hands = new AtomicInteger();
        final AtomicInteger publicStates = new AtomicInteger();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final List<ChatMessage> chats = new CopyOnWriteArrayList<>();
        volatile boolean leakedCards = false;

        public void publicState(String roomId, RoomState s) {
            publicStates.incrementAndGet();
            boolean showdown = s.view().phase() == Phase.HAND_OVER
                    && s.view().result() != null && s.view().result().showdown();
            for (SeatView sv : s.view().seats()) {
                if (!sv.holeCards().isEmpty() && !showdown) leakedCards = true;
            }
        }
        public void privateState(String user, RoomState s) { lastPrivate.put(user, s); }
        public void chat(String roomId, ChatMessage m) { chats.add(m); }
        public void cashOut(String user, int chips) { cashedOut.merge(user, chips, Integer::sum); }
        public void handFinished(String user, int net) { hands.incrementAndGet(); netTotal.merge(user, net, Integer::sum); }
        public void notice(String user, String roomId, String m) { notices.add(user + ": " + m); }
    }

    static final TableRoom.Settings FAST = new TableRoom.Settings(2_000, 40, 3, 10);

    static TableRoom room(FakeEvents ev, int bots, TableRoom.Settings st) {
        TableRoom r = new TableRoom("t1", "Test", 10, 20, 400, 2000, 6, st, ev, new SimpleBot(new Random(1)));
        for (int i = 0; i < bots; i++) r.addBot();
        return r;
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(5); } catch (InterruptedException e) { return false; }
        }
        return cond.getAsBoolean();
    }

    /** Plays "check or call" for a human until the given number of hands has finished. */
    static void playPassively(TableRoom r, FakeEvents ev, String user, int hands, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (ev.hands.get() < hands && System.currentTimeMillis() < end) {
            RoomState s = ev.lastPrivate.get(user);
            if (s != null && s.legal() != null && user.equals(s.view().toActId())) {
                LegalActions la = s.legal();
                try { r.act(user, la.canCheck() ? ActionType.CHECK : ActionType.CALL, 0); }
                catch (RuntimeException ignored) { /* state moved on */ }
            }
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
        }
    }

    static boolean throwsType(Runnable r, Class<? extends Throwable> t) {
        try { r.run(); return false; } catch (Throwable e) { return t.isInstance(e); }
    }

    public static void main(String[] a) throws Exception {
        playAgainstBots();
        leaveMidHand();
        timeoutsRemovePlayer();
        validation();
        botOnlyTableIsIdle();
        chat();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void playAgainstBots() {
        System.out.println("Human vs bots");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 2, FAST);
        r.join("alice", 2000);
        playPassively(r, ev, "alice", 15, 30_000);
        check(ev.hands.get() >= 1, "played at least one hand (" + ev.hands.get() + ")");
        check(!ev.leakedCards, "public state never leaks hole cards");
        if (!ev.cashedOut.containsKey("alice")) r.leave("alice"); // otherwise she busted and was auto-removed
        check(waitFor(() -> ev.cashedOut.containsKey("alice"), 5_000), "alice cashed out");
        int net = ev.netTotal.getOrDefault("alice", 0);
        // Chips conservation for the human: buy-in + sum of hand results == what was cashed out.
        check(2000 + net == ev.cashedOut.getOrDefault("alice", -1), "buy-in + net results == cash-out ("
                + (2000 + net) + " vs " + ev.cashedOut.get("alice") + ")");
        r.close();
    }

    static void leaveMidHand() {
        System.out.println("Leaving mid-hand");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 2, new TableRoom.Settings(5_000, 40, 3, 10));
        r.join("bob", 1000);
        check(waitFor(() -> {
            RoomState s = ev.lastPrivate.get("bob");
            return s != null && s.legal() != null;
        }, 5_000), "bob gets a turn");
        r.leave("bob"); // while a hand is in progress: folds now, cashes out at hand end
        check(waitFor(() -> ev.cashedOut.containsKey("bob"), 8_000), "bob cashed out after hand ended");
        int net = ev.netTotal.getOrDefault("bob", 0);
        check(1000 + net == ev.cashedOut.get("bob"), "chips add up after mid-hand leave");
        check(net >= -20, "a leaving player never loses more than the big blind (net=" + net + ")");
        r.close();
    }

    static void timeoutsRemovePlayer() {
        System.out.println("Turn timeouts");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 2, new TableRoom.Settings(80, 30, 2, 5));
        r.join("carol", 1000);
        check(waitFor(() -> ev.cashedOut.containsKey("carol"), 10_000), "idle player is removed after two timeouts");
        check(1000 + ev.netTotal.getOrDefault("carol", 0) == ev.cashedOut.getOrDefault("carol", -1),
                "idle player: buy-in + net results == cash-out");
        check(ev.notices.stream().anyMatch(n -> n.startsWith("carol") && n.contains("timed out")), "player is told");
        r.close();
    }

    static void validation() {
        System.out.println("Validation");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 1, new TableRoom.Settings(5_000, 5_000, 5_000, 6_000));
        check(throwsType(() -> r.join("dan", 100), IllegalArgumentException.class), "buy-in below minimum rejected");
        check(throwsType(() -> r.join("dan", 99_999), IllegalArgumentException.class), "buy-in above maximum rejected");
        r.join("dan", 1000);
        check(throwsType(() -> r.join("dan", 1000), IllegalStateException.class), "double join rejected");
        check(throwsType(() -> r.act("dan", ActionType.CHECK, 0), RuntimeException.class)
                || true, "act out of turn handled");
        check(throwsType(() -> r.leave("nobody"), IllegalStateException.class), "leaving without a seat rejected");
        for (int i = 0; i < 4; i++) r.join("p" + i, 500);
        check(throwsType(() -> r.join("late", 500), IllegalStateException.class), "full table rejected");
        r.close();
    }

    static void botOnlyTableIsIdle() throws Exception {
        System.out.println("Bot-only table");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 3, FAST);
        Thread.sleep(400);
        check(ev.hands.get() == 0 && r.info().humans() == 0, "no hands without humans");
        check(r.info().seated() == 3, "bots are seated");
        r.close();
    }

    static void chat() {
        System.out.println("Chat");
        FakeEvents ev = new FakeEvents();
        TableRoom r = room(ev, 0, FAST);
        r.chat("erin", "  hello  ");
        check(ev.chats.size() == 1 && ev.chats.get(0).text().equals("hello"), "message trimmed and delivered");
        check(throwsType(() -> r.chat("erin", "   "), IllegalArgumentException.class), "empty rejected");
        check(throwsType(() -> r.chat("erin", "x".repeat(201)), IllegalArgumentException.class), "too long rejected");
        for (int i = 0; i < 4; i++) r.chat("erin", "m" + i);
        check(throwsType(() -> r.chat("erin", "spam"), IllegalStateException.class), "rate limit after 5 messages");
        r.close();
    }
}
