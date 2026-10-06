package com.poker.ws;

import com.poker.game.RoomInfo;
import com.poker.game.TableRoom;
import com.poker.game.bot.SimpleBot;
import com.poker.user.ChipService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomManager {
    private static final int MAX_ROOMS = 50;
    private static final int MAX_SEATS = 6;

    private final Map<String, TableRoom> rooms = new ConcurrentHashMap<>();
    private final SpringRoomEvents events;
    private final ChipService chips;
    private final TableRoom.Settings settings;

    public RoomManager(SpringRoomEvents events, ChipService chips,
                       @Value("${poker.turn-seconds}") long turnSeconds,
                       @Value("${poker.next-hand-delay-seconds}") long nextHandSeconds) {
        this.events = events;
        this.chips = chips;
        this.settings = new TableRoom.Settings(turnSeconds * 1000, nextHandSeconds * 1000, 700, 1800);
    }

    @PostConstruct
    void seedDefaultRooms() {
        create("Practice Table", 10, 20, 2);
        create("Low Stakes", 25, 50, 1);
        create("High Rollers", 100, 200, 2);
    }

    @PreDestroy
    void shutdown() { rooms.values().forEach(TableRoom::close); }

    public RoomInfo create(String name, int smallBlind, int bigBlind, int bots) {
        if (rooms.size() >= MAX_ROOMS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many rooms");
        if (bots > MAX_SEATS - 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many bots");
        String id = UUID.randomUUID().toString().substring(0, 8);
        TableRoom room = new TableRoom(id, name, smallBlind, bigBlind, bigBlind * 20, bigBlind * 100,
                MAX_SEATS, settings, events, new SimpleBot());
        for (int i = 0; i < bots; i++) room.addBot();
        rooms.put(id, room);
        return room.info();
    }

    public List<RoomInfo> list() {
        return rooms.values().stream().map(TableRoom::info)
                .sorted(Comparator.comparingInt(RoomInfo::bigBlind).thenComparing(RoomInfo::name))
                .toList();
    }

    public TableRoom get(String id) {
        TableRoom r = rooms.get(id);
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such room");
        return r;
    }

    /** Takes the buy-in from the account first; refunds it if the table refuses the player. */
    public void join(String username, String roomId, int buyIn) {
        TableRoom room = get(roomId);
        chips.debit(username, buyIn);
        try {
            room.join(username, buyIn);
        } catch (RuntimeException e) {
            chips.credit(username, buyIn);
            throw e;
        }
    }
}
