package com.poker.game;

public record RoomInfo(String id, String name, int smallBlind, int bigBlind,
                       int minBuyIn, int maxBuyIn, int maxSeats, int seated, int humans) {}
