package com.poker.game.bot;

public interface BotStrategy {
    BotDecision decide(BotContext context);
}
