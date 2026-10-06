# Poker server (Java 21 + Spring Boot)

## Run
```bash
./run-tests.sh                 # engine + room tests, JDK 21 only, no Maven needed
mvn spring-boot:run            # starts on :8080 with a file-based H2 database (./data)
# PostgreSQL:  mvn spring-boot:run -Dspring-boot.run.profiles=postgres
export JWT_SECRET='<32+ random bytes>'   # required for anything beyond local dev
```

## REST
| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/register` `{username,password}` | returns `{token,username,chips}` (starts with 10,000 chips) |
| POST | `/api/auth/login` | same response |
| GET | `/api/me` | profile + chip balance (Bearer token) |
| GET | `/api/rooms` | lobby list |
| POST | `/api/rooms` `{name,smallBlind,bigBlind,bots}` | create a table (buy-in is 20-100 big blinds) |
| GET | `/api/leaderboard` | top 20 by total winnings (public) |

## WebSocket (STOMP at `ws://localhost:8080/ws`)
Send `Authorization: Bearer <token>` as a STOMP **CONNECT header**.

Subscribe:
- `/topic/rooms/{id}`        public table state (no hole cards until showdown)
- `/topic/rooms/{id}/chat`   chat
- `/user/queue/table`        YOUR state: your hole cards, `legal` actions on your turn, `turnDeadlineMillis`
- `/user/queue/notices`      `{roomId, message}`
- `/user/queue/errors`       `{message}` for rejected actions

Send (all under `/app`):
- `/app/rooms/{id}/join`    `{"buyIn": 1000}`
- `/app/rooms/{id}/leave`
- `/app/rooms/{id}/state`   request current state (spectators)
- `/app/rooms/{id}/action`  `{"type":"FOLD|CHECK|CALL|RAISE|ALL_IN","amount":0}`  (RAISE amount = total "raise to")
- `/app/rooms/{id}/chat`    `{"text":"hi"}`

## Known limitations
- Chips sitting at a table are held in memory: a server crash loses them. Persist table stacks next.
- A failed cash-out DB write is logged as `CHIP RECONCILIATION NEEDED` rather than retried.
- Bots are simple rule-based players; they use virtual chips that never touch the database.
- Chat text is plain text: render it as text in the client, never as HTML.
