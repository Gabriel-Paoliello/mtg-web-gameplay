# MTG Gameplay — Developer Guide

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Browser (React)                      │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Vite / React SPA  (useGameSocket hook)              │  │
│  │  ws://localhost:8080/ws/game/{gameId}  (dev)         │  │
│  │  wss://{host}/ws/game/{gameId}         (production)  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────┬───────────────────────────────┘
                              │ WebSocket / HTTP
             ┌────────────────┘
             │  (dev: direct)    (prod: nginx proxy at /ws/)
             ▼
┌────────────────────────────────────────────┐
│  XMage Server  (port 8080)                 │
│                                            │
│  ApiServerModule  (Jetty embedded)         │
│  └─ WebSocketGameServer  /ws/game/*        │
│     └─ GameStateSerializer (Gson)          │
│                                            │
│  XMage Core (GameManager, GameController)  │
│  JBoss remoting (port 17003, internal)     │
└────────────────────────────────────────────┘
             │ (production only)
             ▼
┌──────────────────────────────┐
│  nginx  (port 80)            │
│  /ws/* → xmage-server:8080   │
│  /*    → React SPA dist      │
└──────────────────────────────┘
```

---

## Starting the Development Environment

### Prerequisites
- Java 21 (Homebrew: `brew install openjdk@21`)
- Maven 3.9+
- Node.js 20+

### One-command start

```bash
./start-dev.sh
```

This script:
1. Builds the XMage server JAR with `mvn package -pl Mage.Server -am -DskipTests`
2. Starts `Mage.Server-*.jar` on port 8080
3. Starts the Vite dev server (typically port 5173)

Press **Ctrl+C** once to stop both processes cleanly.

### Manual start (two terminals)

**Terminal 1 — XMage server:**
```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
mvn package -pl Mage.Server -am -DskipTests -q
java -jar Mage.Server/target/Mage.Server-*.jar
```

**Terminal 2 — React dev server:**
```bash
cd frontend
npm install
npm run dev
```

---

## Running with Docker

### Prerequisites
- Docker 24+ with the Compose plugin (`docker compose`)

### Build and start all services

```bash
docker compose up --build
```

- Frontend: http://localhost
- XMage WebSocket: ws://localhost:8080/ws/game/{gameId}

### Individual service rebuild

```bash
docker compose build xmage-server
docker compose build frontend
```

### Stop services

```bash
docker compose down
```

---

## WebSocket API Reference

**Endpoint:** `ws://{host}/ws/game/{gameId}`

All messages are JSON objects with a `type` field.

---

### Client → Server messages

#### `CREATE_GAME`
Create a new game session (returns a placeholder gameId; actual match must be started via XMage lobby).

```json
{
  "type": "CREATE_GAME",
  "format": "STANDARD",
  "deckCards": [
    { "name": "Lightning Bolt", "rarity": "COMMON" }
  ]
}
```

- `format`: `"STANDARD"` | `"COMMANDER"` | `"PAUPER"` (default: `"STANDARD"`)
- `deckCards`: required for Pauper validation; each entry needs `name` and `rarity`

**Response:** `GAME_CREATED` or `ERROR`

---

#### `JOIN_GAME`
Attach this WebSocket session to an active game.

```json
{
  "type": "JOIN_GAME",
  "gameId": "uuid",
  "userId": "uuid"
}
```

**Response:** `JOINED_GAME`

---

#### `PASS_PRIORITY`
Pass priority, cancelling all pending auto-actions.

```json
{ "type": "PASS_PRIORITY" }
```

Variants (same shape, different auto-stop point):

| Type | Stops at |
|---|---|
| `PASS_PRIORITY` | Immediately (cancel all) |
| `PASS_PRIORITY_UNTIL_TURN_END` | End of current turn |
| `PASS_PRIORITY_UNTIL_NEXT_MAIN` | Next main phase |
| `PASS_PRIORITY_UNTIL_NEXT_TURN` | Start of next turn |
| `PASS_PRIORITY_UNTIL_STACK_RESOLVED` | When stack is empty |

---

#### `CONCEDE`
Concede the game.

```json
{ "type": "CONCEDE" }
```

---

#### `UNDO`
Undo the last action (if supported by game state).

```json
{ "type": "UNDO" }
```

---

#### `PLAY_CARD` / `ACTIVATE_ABILITY`
Play a card or activate an ability by its UUID.

```json
{
  "type": "PLAY_CARD",
  "data": "card-or-ability-uuid"
}
```

---

#### `DECLARE_ATTACKERS` / `DECLARE_BLOCKERS`
Declare an attacker or blocker by UUID.

```json
{
  "type": "DECLARE_ATTACKERS",
  "data": "creature-uuid"
}
```

---

#### `CHOOSE` / `SEND_UUID`
Send a UUID choice in response to a game prompt.

```json
{
  "type": "CHOOSE",
  "data": "target-uuid"
}
```

---

#### `SEND_BOOLEAN`
Respond to a yes/no prompt.

```json
{
  "type": "SEND_BOOLEAN",
  "data": true
}
```

---

#### `SEND_STRING`
Send a string choice (e.g. naming a card, choosing a mode label).

```json
{
  "type": "SEND_STRING",
  "data": "Island"
}
```

---

#### `SEND_INTEGER`
Send a numeric choice (e.g. X value, number of targets).

```json
{
  "type": "SEND_INTEGER",
  "data": 3
}
```

---

### Server → Client messages

#### `GAME_STATE`
Full game state snapshot. Sent on every state change.

```json
{
  "type": "GAME_STATE",
  "payload": {
    "gameId": "uuid",
    "format": "STANDARD",
    "turn": 3,
    "phase": "COMBAT",
    "step": "DECLARE_ATTACKERS",
    "activePlayerId": "uuid",
    "priorityPlayerName": "Alice",
    "players": [
      {
        "playerId": "uuid",
        "name": "Alice",
        "life": 20,
        "manaPool": { "W": 0, "U": 1, "B": 0, "R": 0, "G": 0, "C": 0 },
        "battlefield": [ /* CardView objects */ ],
        "graveyard": [],
        "exile": [],
        "commandZone": [],
        "counters": []
      }
    ],
    "stack": [],
    "myHand": [ /* CardView objects — only populated for the connected player */ ]
  }
}
```

---

#### `GAME_CREATED`
Confirmation after `CREATE_GAME`.

```json
{
  "type": "GAME_CREATED",
  "gameId": "uuid",
  "format": "STANDARD",
  "note": "Use JOIN_GAME with this gameId once the XMage match is started via the lobby API."
}
```

---

#### `JOINED_GAME`
Confirmation after `JOIN_GAME`.

```json
{
  "type": "JOINED_GAME",
  "gameId": "uuid",
  "userId": "uuid"
}
```

---

#### `PRIORITY`
Lightweight priority update (no full state resend).

```json
{
  "type": "PRIORITY",
  "payload": { "playerId": "uuid" }
}
```

---

#### `WAITING_ACTION`
The server is waiting for a specific player action (prompt).

```json
{
  "type": "WAITING_ACTION",
  "payload": {
    "actionType": "CHOOSE_TARGET",
    "message": "Choose a target"
  }
}
```

---

#### `GAME_OVER`
Game has ended.

```json
{
  "type": "GAME_OVER",
  "payload": {
    "winnerId": "uuid",
    "winnerName": "Alice",
    "reason": "CONCEDE"
  }
}
```

---

#### `ERROR`
An error occurred processing the last message.

```json
{
  "type": "ERROR",
  "message": "Pauper deck contains non-common card: Serra Angel (rarity=RARE)"
}
```

---

## Game Formats

### Standard
- Default format.
- No additional deck validation performed by the WebSocket layer (XMage enforces legality internally).

### Commander (EDH)
- 100-card singleton decks.
- Each player has a Commander card; it starts in the `commandZone` and is always present in the serialized player state.
- Commander damage is tracked via `counters` (named `COMMANDER_DAMAGE`) on each player.
- Players start at 40 life (enforced by XMage).

### Pauper
- Only **common** and **basic land** cards are legal.
- The WebSocket layer validates legality on `CREATE_GAME`: if any card in `deckCards` has a rarity other than `COMMON` or `LAND`, the server replies with an `ERROR` and does not create the game.
- Standard 60-card minimum / 20-card sideboard rules apply (enforced by XMage).

---

## Notes

- All UUIDs are standard RFC 4122 strings (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
- If `gameId` is omitted from a message, the server uses the gameId registered via the most recent `JOIN_GAME` for that connection.
- The XMage server runs Jetty on port **8080** (WebSocket API) and JBoss remoting on port **17003** (internal lobby protocol — not exposed in the Docker setup).
