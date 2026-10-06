package com.poker.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {
	@GetMapping("/api/status")
    public Map<String, String> home() {
        return Map.of("status", "Poker server is running",
                "try", "/api/leaderboard");
    }
}