package com.poker.game;

/** Outbound side of a table: the Spring layer implements this to push messages and move chips. */
public interface RoomEvents {
    /** State for spectators: no hole cards except those revealed at showdown. */
    void publicState(String roomId, RoomState state);

    /** State for one seated human, including their own hole cards and legal actions. */
    void privateState(String username, RoomState state);

    void chat(String roomId, ChatMessage message);

    /** Return chips from the table to the player's account balance. */
    void cashOut(String username, int chips);

    /** Called once per hand for each human who was dealt in. {@code net} may be negative. */
    void handFinished(String username, int net);

    void notice(String username, String roomId, String message);
}
