package com.poker.game;

import com.poker.engine.PokerTable.LegalActions;
import com.poker.engine.PokerTable.TableView;

/**
 * What a client receives. {@code legal} is non-null only for the player whose turn it is.
 * {@code turnDeadlineMillis} is an epoch-millis deadline for a human turn, or 0 if none.
 */
public record RoomState(String roomId, String roomName, TableView view,
                        LegalActions legal, long turnDeadlineMillis) {}
