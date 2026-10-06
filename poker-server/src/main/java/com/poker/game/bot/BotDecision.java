package com.poker.game.bot;

import com.poker.engine.PokerTable.ActionType;

public record BotDecision(ActionType type, int amount) {}
