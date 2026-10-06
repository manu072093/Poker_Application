package com.poker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// We authenticate with our own JWT filter, so skip Spring's generated default user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PokerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PokerApplication.class, args);
    }
}
