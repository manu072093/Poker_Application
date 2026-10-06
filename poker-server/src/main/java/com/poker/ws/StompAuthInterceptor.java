package com.poker.ws;

import com.poker.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates STOMP sessions and locks down what clients may send and subscribe to.
 *
 * Without the SEND/SUBSCRIBE checks a client could publish straight to "/topic/rooms/..."
 * and forge table state or chat messages, because the simple broker accepts such frames.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {
    private final JwtService jwt;

    public StompAuthInterceptor(JwtService jwt) { this.jwt = jwt; }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;

        StompCommand cmd = acc.getCommand();
        if (StompCommand.CONNECT.equals(cmd)) {
            String header = acc.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new MessagingException("Missing Authorization header");
            }
            String username = jwt.validate(header.substring(7))
                    .orElseThrow(() -> new MessagingException("Invalid or expired token"));
            acc.setUser(new UsernamePasswordAuthenticationToken(username, null, List.of()));
        } else if (StompCommand.SEND.equals(cmd)) {
            requireUser(acc);
            String dest = acc.getDestination();
            if (dest == null || !dest.startsWith("/app/")) {
                throw new MessagingException("Clients may only send to /app/**");
            }
        } else if (StompCommand.SUBSCRIBE.equals(cmd)) {
            requireUser(acc);
            String dest = acc.getDestination();
            if (dest == null || !(dest.startsWith("/topic/rooms/") || dest.startsWith("/user/queue/"))) {
                throw new MessagingException("Subscription not allowed: " + dest);
            }
        }
        return message;
    }

    private static void requireUser(StompHeaderAccessor acc) {
        if (acc.getUser() == null) throw new MessagingException("Not authenticated");
    }
}
