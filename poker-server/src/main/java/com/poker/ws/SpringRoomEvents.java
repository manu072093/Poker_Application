package com.poker.ws;

import com.poker.game.ChatMessage;
import com.poker.game.RoomEvents;
import com.poker.game.RoomState;
import com.poker.user.ChipService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Delivers table events over STOMP and applies chip movements to the database. */
@Component
public class SpringRoomEvents implements RoomEvents {
    private static final Logger LOG = Logger.getLogger(SpringRoomEvents.class.getName());

    private final SimpMessagingTemplate template;
    private final ChipService chips;

    public SpringRoomEvents(SimpMessagingTemplate template, ChipService chips) {
        this.template = template;
        this.chips = chips;
    }

    @Override
    public void publicState(String roomId, RoomState state) {
        template.convertAndSend("/topic/rooms/" + roomId, state);
    }

    @Override
    public void privateState(String username, RoomState state) {
        template.convertAndSendToUser(username, "/queue/table", state);
    }

    @Override
    public void chat(String roomId, ChatMessage message) {
        template.convertAndSend("/topic/rooms/" + roomId + "/chat", message);
    }

    @Override
    public void cashOut(String username, int amount) {
        try {
            chips.credit(username, amount);
        } catch (RuntimeException e) {
            // Must never be silent: these chips left the table but did not reach the account.
            LOG.log(Level.SEVERE, "CHIP RECONCILIATION NEEDED: user=" + username + " amount=" + amount, e);
        }
    }

    @Override
    public void handFinished(String username, int net) {
        try {
            chips.recordHand(username, net);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not record hand result for " + username, e);
        }
    }

    @Override
    public void notice(String username, String roomId, String message) {
        template.convertAndSendToUser(username, "/queue/notices", Map.of("roomId", roomId, "message", message));
    }
}
