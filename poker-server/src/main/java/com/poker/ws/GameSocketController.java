package com.poker.ws;

import com.poker.engine.IllegalActionException;
import com.poker.engine.PokerTable.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Locale;

/** Client -> server messages. Every destination is under /app. */
@Controller
public class GameSocketController {
    private static final Logger LOG = LoggerFactory.getLogger(GameSocketController.class);

    public record JoinRequest(int buyIn) {}
    public record ActionRequest(String type, int amount) {}
    public record ChatRequest(String text) {}
    public record ErrorMessage(String message) {}

    private final RoomManager rooms;

    public GameSocketController(RoomManager rooms) { this.rooms = rooms; }

    @MessageMapping("/rooms/{roomId}/join")
    public void join(@DestinationVariable String roomId, JoinRequest req, Principal user) {
        rooms.join(user.getName(), roomId, req.buyIn());
    }

    @MessageMapping("/rooms/{roomId}/leave")
    public void leave(@DestinationVariable String roomId, Principal user) {
        rooms.get(roomId).leave(user.getName());
    }

    /** Ask for the current state, e.g. right after subscribing as a spectator. */
    @MessageMapping("/rooms/{roomId}/state")
    public void state(@DestinationVariable String roomId, Principal user) {
        rooms.get(roomId).sendStateTo(user.getName());
    }

    @MessageMapping("/rooms/{roomId}/action")
    public void action(@DestinationVariable String roomId, ActionRequest req, Principal user) {
        if (req == null || req.type() == null) throw new IllegalArgumentException("Missing action type");
        ActionType type = ActionType.valueOf(req.type().strip().toUpperCase(Locale.ROOT));
        rooms.get(roomId).act(user.getName(), type, req.amount());
    }

    @MessageMapping("/rooms/{roomId}/chat")
    public void chat(@DestinationVariable String roomId, ChatRequest req, Principal user) {
        rooms.get(roomId).chat(user.getName(), req == null ? null : req.text());
    }

    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public ErrorMessage onError(Exception e) {
        if (e instanceof ResponseStatusException r) return new ErrorMessage(r.getReason());
        if (e instanceof IllegalActionException || e instanceof IllegalArgumentException
                || e instanceof IllegalStateException) {
            return new ErrorMessage(e.getMessage());
        }
        LOG.error("Unexpected error handling message", e);
        return new ErrorMessage("Internal error");
    }
}
