package com.poker.web;

import com.poker.game.RoomInfo;
import com.poker.user.User;
import com.poker.user.UserRepository;
import com.poker.ws.RoomManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class LobbyController {

    public record CreateRoomRequest(@NotBlank @Size(max = 30) String name,
                                    @Min(1) @Max(100_000) int smallBlind,
                                    @Min(1) @Max(200_000) int bigBlind,
                                    @Min(0) @Max(5) int bots) {}

    public record Profile(String username, long chips, long totalWinnings, int handsPlayed, int handsWon) {}

    public record LeaderboardEntry(int rank, String username, long totalWinnings, long chips,
                                   int handsWon, int handsPlayed) {}

    private final RoomManager rooms;
    private final UserRepository users;

    public LobbyController(RoomManager rooms, UserRepository users) {
        this.rooms = rooms;
        this.users = users;
    }

    @GetMapping("/rooms")
    public List<RoomInfo> listRooms() { return rooms.list(); }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomInfo createRoom(@Valid @RequestBody CreateRoomRequest req) {
        if (req.bigBlind() < req.smallBlind()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Big blind must be at least the small blind");
        }
        return rooms.create(req.name().strip(), req.smallBlind(), req.bigBlind(), req.bots());
    }

    @GetMapping("/me")
    public Profile me(Principal principal) {
        User u = users.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        return new Profile(u.getUsername(), u.getChips(), u.getTotalWinnings(), u.getHandsPlayed(), u.getHandsWon());
    }

    @GetMapping("/leaderboard")
    public List<LeaderboardEntry> leaderboard() {
        List<LeaderboardEntry> out = new ArrayList<>();
        int rank = 1;
        for (User u : users.findTop20ByOrderByTotalWinningsDesc()) {
            out.add(new LeaderboardEntry(rank++, u.getUsername(), u.getTotalWinnings(), u.getChips(),
                    u.getHandsWon(), u.getHandsPlayed()));
        }
        return out;
    }
}
