package com.poker.game.bot;

import com.poker.engine.Card;
import com.poker.engine.PokerTable.LegalActions;

import java.util.List;

public record BotContext(List<Card> hole, List<Card> board, int pot, int currentBet,
                         int bigBlind, LegalActions legal) {}
