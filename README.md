# Poker_Application
# ♠ Poker Application

A multiplayer **Texas Hold'em** poker game that runs in the browser. The backend is Java 21 and Spring Boot, with real-time play over WebSockets.

## Features

- 👤 **Login / Register** with BCrypt-hashed passwords and JWT authentication
- 🎮 **Multiplayer rooms** with several tables, each with its own blinds and buy-in range
- 💰 **Virtual chips**: every account starts with 10,000 chips, and buy-ins and cash-outs are stored in the database
- 🃏 **Real-time gameplay** over WebSockets (STOMP)
- 🤖 **Bot players** that fill empty seats
- 💬 **Table chat** with length limits and rate limiting
- 🏆 **Leaderboard** ranked by total winnings
- 🎨 **Poker table UI** with seats, cards, pot, action buttons and a turn countdown

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 (Web, WebSocket, Security, Data JPA, Validation) |
| Realtime | STOMP over WebSocket |
| Auth | JWT + BCrypt |
| Database | H2 (default, file-based) or PostgreSQL |
| Frontend | Single-page HTML + JavaScript (served by Spring) |
| Build | Maven |

## Getting started

### Requirements
- JDK 21
- Spring Tools (Eclipse), IntelliJ, or Maven installed

### Run in Spring Tools
1. **File → Import → Maven → Existing Maven Projects** and select this folder.
2. Wait for the Maven dependencies to download.
3. Right-click `src/main/java/com/poker/PokerApplication.java` → **Run As → Spring Boot App**.
4. Open **http://localhost:8080** in your browser.

### Run with Maven
```bash
mvn spring-boot:run
```

No database setup is needed. A local H2 file database is created in `./data` on first run.

### Use PostgreSQL instead
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```
Configure the connection with the `DB_URL`, `DB_USER` and `DB_PASSWORD` environment variables.

### Configuration

| Variable | Purpose | Default |
|---|---|---|
| `JWT_SECRET` | Token signing key (at least 32 bytes). **Set this in production.** | dev-only value |
| `ALLOWED_ORIGIN` | Origin allowed for CORS and WebSocket | `http://localhost:8080` |

## How to play

1. Open http://localhost:8080 and **register** an account.
2. In the lobby, click **Sit** at a table and choose a buy-in. Bots deal in within a few seconds.
3. On your turn, choose **Fold**, **Check/Call**, **Raise** or **All-in** before the 20-second timer runs out.
4. To test multiplayer, open a second private/incognito window and register another user at the same table.

## Project structure

```
src/main/java/com/poker/
├── engine/     Pure game logic: cards, deck, hand evaluator, betting, side pots
├── game/       Table rooms: turn timers, joins/leaves, chat, bots
│   └── bot/    Bot strategy
├── security/   JWT service, request filter, Spring Security config
├── user/       User entity, repository, chip transfers
├── web/        REST controllers (auth, lobby, leaderboard)
└── ws/         WebSocket config, STOMP auth, room manager, message handlers
src/main/resources/static/index.html    Browser client
```

The game engine (`engine/`) and room layer (`game/`) have no Spring dependencies, so they can be tested with a plain JDK.

## Tests

```bash
./run-tests.sh
```

Runs 76 checks with only a JDK, with no Maven or test framework needed:
- Hand ranking, including the A-2-3-4-5 straight, kickers and 7-card hands
- Betting rules: minimum raises and a short all-in that doesn't reopen betting
- Side pots, split pots and uncontested pots
- A 5,000-hand random simulation that checks chips are never created or destroyed
- Room behaviour: timeouts, leaving mid-hand, validation, chat limits, and no hole-card leaks

## API

### REST

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Create an account, returns a token |
| POST | `/api/auth/login` | Log in, returns a token |
| GET | `/api/me` | Your profile and chip balance |
| GET | `/api/rooms` | List tables |
| POST | `/api/rooms` | Create a table |
| GET | `/api/leaderboard` | Top 20 players (public) |

### WebSocket (STOMP at `/ws`)

Send `Authorization: Bearer <token>` as a STOMP CONNECT header.

| Direction | Destination | Purpose |
|---|---|---|
| Subscribe | `/topic/rooms/{id}` | Public table state |
| Subscribe | `/topic/rooms/{id}/chat` | Chat |
| Subscribe | `/user/queue/table` | Your private state: hole cards and legal actions |
| Subscribe | `/user/queue/notices`, `/user/queue/errors` | Messages and rejected actions |
| Send | `/app/rooms/{id}/join` | `{"buyIn": 1000}` |
| Send | `/app/rooms/{id}/action` | `{"type":"RAISE","amount":200}` |
| Send | `/app/rooms/{id}/leave`, `/chat`, `/state` | Leave, chat, request state |

## Security notes

- The server is the single source of truth. Clients only send intents, and every action is validated server-side.
- Each player only receives their own hole cards. Opponents' cards are sent only at showdown.
- Clients cannot publish to broadcast topics, so table state and chat cannot be forged.
- Chip amounts are integers, and balance changes happen in database transactions.

## Known limitations

- Chips sitting at a table are held in memory, so a server crash loses them.
- A failed cash-out is logged for manual reconciliation rather than retried.
- Bots are simple rule-based players using virtual chips that are not stored in the database.
- There is no password reset or account rename yet.

## Roadmap

- [ ] Persist table stacks across restarts
- [ ] Create-table form in the lobby
- [ ] Smarter bots
- [ ] Hand history and replays
- [ ] Sounds and card animations
- [ ] React frontend
- [ ] Docker setup

## Disclaimer

This game uses virtual chips only. It has no real-money gambling and is intended for learning and entertainment.
