package com.poker.web;

import com.poker.security.JwtService;
import com.poker.user.User;
import com.poker.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record AuthRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "3-20 letters, digits or underscores") String username,
            @NotBlank @Size(min = 8, max = 72, message = "Password must be 8-72 characters") String password) {}

    public record AuthResponse(String token, String username, long chips) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final long startingChips;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                          @Value("${poker.starting-chips}") long startingChips) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.startingChips = startingChips;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody AuthRequest req) {
        if (users.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        try {
            User u = users.save(new User(req.username(), encoder.encode(req.password()), startingChips));
            return new AuthResponse(jwt.issue(u.getUsername()), u.getUsername(), u.getChips());
        } catch (DataIntegrityViolationException e) { // lost a race with another registration
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest req) {
        User u = users.findByUsername(req.username())
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        return new AuthResponse(jwt.issue(u.getUsername()), u.getUsername(), u.getChips());
    }
}
