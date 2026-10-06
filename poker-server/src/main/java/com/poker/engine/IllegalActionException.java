package com.poker.engine;

/** Thrown when a player attempts an action that is not legal right now. */
public class IllegalActionException extends RuntimeException {
    public IllegalActionException(String message) { super(message); }
}
