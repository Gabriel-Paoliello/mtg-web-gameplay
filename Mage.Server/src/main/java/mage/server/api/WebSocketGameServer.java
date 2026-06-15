package mage.server.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PlayerAction;
import mage.game.Table;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;
import mage.server.User;
import mage.server.game.GameController;
import mage.server.game.GameSessionPlayer;
import mage.server.managers.ManagerFactory;
import mage.view.GameView;
import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * WebSocket bridge that exposes XMage game state as JSON over a WebSocket endpoint.
 *
 * Endpoint: /ws/game/{gameId}
 *
 * Incoming message types (JSON):
 *   CREATE_GAME, JOIN_GAME, PASS_PRIORITY, CONCEDE, UNDO,
 *   SEND_UUID, SEND_BOOLEAN, SEND_STRING, SEND_INTEGER
 *
 * The server sends back GAME_STATE messages whenever state changes.
 *
 * Game creation flow:
 *   1. Client sends CREATE_GAME (with deckList, format, playerName)
 *   2. Server creates XMage table + registers synthetic user, returns GAME_CREATED with gameId + userId
 *   3. Second client sends JOIN_GAME (with gameId + playerName + deckList)
 *   4. Server adds second player, starts match, starts polling loop
 *   5. Both clients receive GAME_STATE pushes every 500 ms
 */
@WebServlet(name = "WebSocketGameServer", urlPatterns = {"/ws/game/*"})
public class WebSocketGameServer extends WebSocketServlet {

    private static final Logger logger = Logger.getLogger(WebSocketGameServer.class);
    private static final int POLL_INTERVAL_MS = 500;

    // ---- per-game state -------------------------------------------------------

    /** gameId (WS) -> XMage internal game UUID (after startMatch) */
    private static final ConcurrentHashMap<UUID, UUID> wsGameToXmageGame = new ConcurrentHashMap<>();

    /** gameId (WS) -> XMage tableId */
    private static final ConcurrentHashMap<UUID, UUID> wsGameToTable = new ConcurrentHashMap<>();

    /** gameId (WS) -> format string (e.g. "COMMANDER") */
    private static final ConcurrentHashMap<UUID, String> gameFormats = new ConcurrentHashMap<>();

    /** gameId (WS) -> number of players who have joined (0, 1, or 2) */
    private static final ConcurrentHashMap<UUID, Integer> gamePlayerCount = new ConcurrentHashMap<>();

    // ---- per-user state -------------------------------------------------------

    /** userId (XMage) -> open WebSocket session */
    private static final ConcurrentHashMap<UUID, Session> userSessions = new ConcurrentHashMap<>();

    /** userId (XMage) -> gameId (WS) */
    private static final ConcurrentHashMap<UUID, UUID> userGameMap = new ConcurrentHashMap<>();

    /** userId (XMage) -> XMage playerId inside the game */
    private static final ConcurrentHashMap<UUID, UUID> userPlayerIdMap = new ConcurrentHashMap<>();

    /** gameId (WS) -> polling executor */
    private static final ConcurrentHashMap<UUID, ScheduledExecutorService> gamePollers = new ConcurrentHashMap<>();

    /** xmage playerId -> pending query info (last PlayerQueryEvent not yet answered) */
    private static final ConcurrentHashMap<UUID, Map<String, Object>> pendingPlayerQuery = new ConcurrentHashMap<>();

    /** xmage playerId -> xmage userId (reverse map for query notifications) */
    private static final ConcurrentHashMap<UUID, UUID> xmagePlayerToUserId = new ConcurrentHashMap<>();

    /** short code (e.g. "AB12CD") -> wsGameId (UUID) */
    private static final ConcurrentHashMap<String, UUID> shortCodeToGame = new ConcurrentHashMap<>();
    /** wsGameId (UUID) -> short code */
    private static final ConcurrentHashMap<UUID, String> gameToShortCode = new ConcurrentHashMap<>();

    private static final String SHORT_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SHORT_CODE_LEN = 6;

    private static String generateShortCode() {
        StringBuilder sb = new StringBuilder(SHORT_CODE_LEN);
        for (int i = 0; i < SHORT_CODE_LEN; i++) {
            sb.append(SHORT_CODE_CHARS.charAt(ThreadLocalRandom.current().nextInt(SHORT_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String allocateShortCode(UUID wsGameId) {
        String code;
        do {
            code = generateShortCode();
        } while (shortCodeToGame.putIfAbsent(code, wsGameId) != null);
        gameToShortCode.put(wsGameId, code);
        return code;
    }

    @Override
    public void configure(WebSocketServletFactory factory) {
        factory.register(GameWebSocket.class);
    }

    // ------------------------------------------------------------------
    // Push helpers (called from outside to notify connected clients)
    // ------------------------------------------------------------------

    /**
     * Push a serialized game state to a connected client.
     *
     * @param gameId    the WS game UUID
     * @param gameView  fresh GameView for this user (one per player)
     * @param userId    the XMage userId to push to
     * @param playerId  the XMage player UUID (identifies this player inside the game)
     */
    public static void pushGameState(UUID gameId, GameView gameView, UUID userId, UUID playerId) {
        Session wsSession = userSessions.get(userId);
        if (wsSession != null && wsSession.isOpen()) {
            String format = gameFormats.getOrDefault(gameId, "UNKNOWN");
            Map<String, Object> query = (playerId != null) ? pendingPlayerQuery.get(playerId) : null;
            String json = GameStateSerializer.serialize(gameId, format, gameView, playerId, query);
            try {
                wsSession.getRemote().sendString(
                        "{\"type\":\"GAME_STATE\",\"payload\":" + json + "}"
                );
            } catch (IOException e) {
                logger.error("Failed to push game state to userId=" + userId, e);
            }
        }
    }

    public static void registerGameFormat(UUID gameId, String format) {
        gameFormats.put(gameId, format);
    }

    // ------------------------------------------------------------------
    // Inner WebSocket handler
    // ------------------------------------------------------------------

    @WebSocket
    public static class GameWebSocket {

        private static final Gson GSON = new GsonBuilder().create();

        private UUID userId;
        private Session session;

        @OnWebSocketConnect
        public void onConnect(Session session) {
            this.session = session;
            logger.info("WebSocket client connected: " + session.getRemoteAddress());
        }

        @OnWebSocketMessage
        public void onMessage(String rawMessage) {
            try {
                JsonObject msg = JsonParser.parseString(rawMessage).getAsJsonObject();
                String type = msg.get("type").getAsString();

                switch (type) {
                    case "CREATE_GAME":
                        handleCreateGame(msg);
                        break;
                    case "JOIN_GAME":
                        handleJoinGame(msg);
                        break;
                    case "PASS_PRIORITY":
                        handlePassPriority(msg, PlayerAction.PASS_PRIORITY_CANCEL_ALL_ACTIONS);
                        break;
                    case "PASS_PRIORITY_UNTIL_TURN_END":
                        handlePassPriority(msg, PlayerAction.PASS_PRIORITY_UNTIL_TURN_END_STEP);
                        break;
                    case "PASS_PRIORITY_UNTIL_NEXT_MAIN":
                        handlePassPriority(msg, PlayerAction.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE);
                        break;
                    case "PASS_PRIORITY_UNTIL_NEXT_TURN":
                        handlePassPriority(msg, PlayerAction.PASS_PRIORITY_UNTIL_NEXT_TURN);
                        break;
                    case "PASS_PRIORITY_UNTIL_STACK_RESOLVED":
                        handlePassPriority(msg, PlayerAction.PASS_PRIORITY_UNTIL_STACK_RESOLVED);
                        break;
                    case "CONCEDE":
                        handlePlayerAction(msg, PlayerAction.CONCEDE, null);
                        break;
                    case "UNDO":
                        handlePlayerAction(msg, PlayerAction.UNDO, null);
                        break;
                    case "CHOOSE":
                    case "SEND_UUID":
                        handleSendUUID(msg);
                        break;
                    case "SEND_BOOLEAN":
                        handleSendBoolean(msg);
                        break;
                    case "SEND_STRING":
                        handleSendString(msg);
                        break;
                    case "SEND_INTEGER":
                        handleSendInteger(msg);
                        break;
                    case "ACTIVATE_ABILITY":
                    case "PLAY_CARD":
                        // Playing a card or activating an ability means the player picks the object
                        // by UUID — delegate to sendPlayerUUID so the game loop unblocks.
                        handleSendUUID(msg);
                        break;
                    case "DECLARE_ATTACKERS":
                    case "DECLARE_BLOCKERS":
                        // Attacker/blocker declarations are UUID choices in XMage
                        handleSendUUID(msg);
                        break;
                    default:
                        sendError("Unknown message type: " + type);
                }
            } catch (Exception e) {
                logger.error("Error handling WebSocket message: " + rawMessage, e);
                sendError("Error processing message: " + e.getMessage());
            }
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            logger.info("WebSocket closed userId=" + userId
                    + " code=" + statusCode + " reason=" + reason);
            if (userId != null) {
                userSessions.remove(userId);
                userGameMap.remove(userId);
                userPlayerIdMap.remove(userId);
            }
        }

        @OnWebSocketError
        public void onError(Throwable cause) {
            logger.error("WebSocket error userId=" + userId, cause);
        }

        // ------------------------------------------------------------------
        // Message handlers
        // ------------------------------------------------------------------

        /**
         * CREATE_GAME message:
         * {
         *   "type": "CREATE_GAME",
         *   "format": "STANDARD",       // STANDARD | PAUPER | COMMANDER
         *   "deckList": "4 Lightning Bolt\n20 Mountain\n...",
         *   "playerName": "Gabriel"
         * }
         *
         * Returns:
         * { "type": "GAME_CREATED", "gameId": "<ws-uuid>", "userId": "<xmage-uuid>",
         *   "status": "waiting_for_player" }
         */
        private void handleCreateGame(JsonObject msg) throws IOException {
            String format = msg.has("format") ? msg.get("format").getAsString().toUpperCase() : "STANDARD";
            String deckText = msg.has("deckList") ? msg.get("deckList").getAsString() : "";
            String playerName = msg.has("playerName") ? msg.get("playerName").getAsString() : "Player1";

            ManagerFactory mf = ApiServerModule.getManagerFactory();
            if (mf == null) {
                sendError("Server not fully initialized — ManagerFactory not available");
                return;
            }

            // Validate pauper decks if deckCards list was provided (legacy path)
            if ("PAUPER".equalsIgnoreCase(format) && msg.has("deckCards")) {
                if (msg.get("deckCards").isJsonArray()) {
                    for (com.google.gson.JsonElement cardEl : msg.get("deckCards").getAsJsonArray()) {
                        JsonObject card = cardEl.getAsJsonObject();
                        String rarity = card.has("rarity") ? card.get("rarity").getAsString() : "";
                        String name = card.has("name") ? card.get("name").getAsString() : "unknown";
                        if (!"COMMON".equalsIgnoreCase(rarity) && !"LAND".equalsIgnoreCase(rarity)) {
                            sendError("Pauper deck contains non-common card: " + name
                                    + " (rarity=" + rarity + ")");
                            return;
                        }
                    }
                }
            }

            // 1. Create a synthetic User in XMage's UserManager
            User xmageUser = createSyntheticUser(mf, playerName);
            if (xmageUser == null) {
                sendError("Failed to create user '" + playerName + "' — name may already be in use");
                return;
            }
            UUID userId1 = xmageUser.getId();

            // 2. Build deckList
            DeckCardLists deck1;
            try {
                deck1 = parseDeckText(deckText);
            } catch (Exception e) {
                sendError("Failed to parse deckList: " + e.getMessage());
                mf.userManager().removeUser(userId1);
                return;
            }

            // 3. Build MatchOptions
            MatchOptions options = buildMatchOptions(format, playerName);

            // 4. Create table
            UUID roomId = mf.gamesRoomManager().getMainRoomId();
            Table table;
            try {
                table = mf.tableManager().createTable(roomId, userId1, options);
            } catch (Exception e) {
                sendError("Failed to create table: " + e.getMessage());
                mf.userManager().removeUser(userId1);
                return;
            }
            UUID tableId = table.getId();

            // 5. Owner joins table (seat 0)
            try {
                boolean joined = mf.tableManager().joinTable(
                        userId1, tableId, playerName, PlayerType.HUMAN, 1, deck1, "");
                if (!joined) {
                    sendError("Player failed to join table");
                    mf.tableManager().removeTable(userId1, tableId);
                    mf.userManager().removeUser(userId1);
                    return;
                }
            } catch (Exception e) {
                sendError("Failed to join table: " + e.getMessage());
                mf.tableManager().removeTable(userId1, tableId);
                mf.userManager().removeUser(userId1);
                return;
            }

            // 6. Register WS-level game ID and state
            UUID wsGameId = UUID.randomUUID();
            String shortCode = allocateShortCode(wsGameId);
            wsGameToTable.put(wsGameId, tableId);
            gameFormats.put(wsGameId, format);
            gamePlayerCount.put(wsGameId, 1);

            // 7. Register this WS session for this user
            this.userId = userId1;
            userSessions.put(userId1, session);
            userGameMap.put(userId1, wsGameId);

            // 8. Respond to client
            JsonObject response = new JsonObject();
            response.addProperty("type", "GAME_CREATED");
            response.addProperty("gameId", shortCode);
            response.addProperty("userId", userId1.toString());
            response.addProperty("format", format);
            response.addProperty("status", "waiting_for_player");
            sendJson(response.toString());

            logger.info("Game created shortCode=" + shortCode + " wsGameId=" + wsGameId + " tableId=" + tableId
                    + " owner=" + playerName + " userId=" + userId1);
        }

        /**
         * JOIN_GAME message:
         * {
         *   "type": "JOIN_GAME",
         *   "gameId": "<ws-game-uuid>",
         *   "deckList": "4 Counterspell\n20 Island\n...",
         *   "playerName": "Opponent"
         * }
         *
         * Returns:
         * { "type": "JOINED_GAME", "gameId": "<ws-uuid>", "userId": "<xmage-uuid>" }
         */
        private void handleJoinGame(JsonObject msg) throws IOException {
            if (!msg.has("gameId")) {
                sendError("JOIN_GAME requires gameId");
                return;
            }

            UUID wsGameId;
            String gameIdStr = msg.get("gameId").getAsString().trim().toUpperCase();
            // Accept 6-char short code or full UUID
            if (shortCodeToGame.containsKey(gameIdStr)) {
                wsGameId = shortCodeToGame.get(gameIdStr);
            } else {
                try {
                    wsGameId = UUID.fromString(msg.get("gameId").getAsString());
                } catch (IllegalArgumentException e) {
                    sendError("Unknown game code: " + gameIdStr);
                    return;
                }
            }

            ManagerFactory mf = ApiServerModule.getManagerFactory();

            // Legacy path: if there is no ManagerFactory (old init overload), fall back to
            // registering only the WS session (no real XMage game join).
            if (mf == null) {
                // Legacy behavior: just register the userId+session mapping if provided
                if (!msg.has("userId")) {
                    sendError("JOIN_GAME in legacy mode requires userId");
                    return;
                }
                UUID legacyUserId;
                try {
                    legacyUserId = UUID.fromString(msg.get("userId").getAsString());
                } catch (IllegalArgumentException e) {
                    sendError("Invalid UUID in JOIN_GAME");
                    return;
                }
                this.userId = legacyUserId;
                userSessions.put(userId, session);
                userGameMap.put(userId, wsGameId);

                JsonObject response = new JsonObject();
                response.addProperty("type", "JOINED_GAME");
                response.addProperty("gameId", gameToShortCode.getOrDefault(wsGameId, wsGameId.toString()));
                response.addProperty("userId", userId.toString());
                sendJson(response.toString());
                return;
            }

            UUID tableId = wsGameToTable.get(wsGameId);
            if (tableId == null) {
                sendError("Unknown gameId — send CREATE_GAME first");
                return;
            }

            String deckText = msg.has("deckList") ? msg.get("deckList").getAsString() : "";
            String playerName = msg.has("playerName") ? msg.get("playerName").getAsString() : "Player2";

            // 1. Create second synthetic user
            User xmageUser2 = createSyntheticUser(mf, playerName);
            if (xmageUser2 == null) {
                sendError("Failed to create user '" + playerName + "' — name may already be in use");
                return;
            }
            UUID userId2 = xmageUser2.getId();

            // 2. Parse deck
            DeckCardLists deck2;
            try {
                deck2 = parseDeckText(deckText);
            } catch (Exception e) {
                sendError("Failed to parse deckList: " + e.getMessage());
                mf.userManager().removeUser(userId2);
                return;
            }

            // 3. Join table
            try {
                boolean joined = mf.tableManager().joinTable(
                        userId2, tableId, playerName, PlayerType.HUMAN, 1, deck2, "");
                if (!joined) {
                    sendError("Second player failed to join table");
                    mf.userManager().removeUser(userId2);
                    return;
                }
            } catch (Exception e) {
                sendError("Second player failed to join table: " + e.getMessage());
                mf.userManager().removeUser(userId2);
                return;
            }

            // 4. Register second player WS session
            this.userId = userId2;
            userSessions.put(userId2, session);
            userGameMap.put(userId2, wsGameId);
            gamePlayerCount.put(wsGameId, 2);

            // 5. Start the match (owner is userId1 — find it from userGameMap)
            UUID userId1 = findOwnerUserId(wsGameId, userId2);
            try {
                mage.server.TableController controller = mf.tableManager()
                        .getController(tableId).orElse(null);
                if (controller == null) {
                    sendError("Table controller not found");
                    return;
                }
                boolean canStart = controller.changeTableStateToStarting();
                if (!canStart) {
                    sendError("Table is not ready to start — seats may not all be filled");
                    return;
                }
                // startMatch checks isOwner internally; pass userId1 (the owner)
                if (userId1 != null) {
                    mf.tableManager().startMatch(userId1, mf.gamesRoomManager().getMainRoomId(), tableId);
                } else {
                    // Fallback: call startMatch directly on controller
                    controller.startMatch();
                }
            } catch (Exception e) {
                sendError("Failed to start match: " + e.getMessage());
                return;
            }

            // 6. Respond to joining client
            JsonObject response = new JsonObject();
            response.addProperty("type", "JOINED_GAME");
            response.addProperty("gameId", gameToShortCode.getOrDefault(wsGameId, wsGameId.toString()));
            response.addProperty("userId", userId2.toString());
            sendJson(response.toString());

            // 7. Wait briefly for game to initialize, then join game sessions and start polling
            scheduleGameJoinAndPoll(mf, wsGameId, tableId, userId1, userId2);

            logger.info("Game joined wsGameId=" + wsGameId + " player2=" + playerName + " userId=" + userId2);
        }

        private void handlePassPriority(JsonObject msg, PlayerAction action) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            gc.sendPlayerAction(action, userId, null);
        }

        private void handlePlayerAction(JsonObject msg, PlayerAction action, Object data) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            gc.sendPlayerAction(action, userId, data);
        }

        private void handleSendUUID(JsonObject msg) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            // Accept "data" at top level OR "payload.cardId" / "payload.id" (from PLAY_CARD)
            String rawUuid = null;
            if (msg.has("data") && !msg.get("data").isJsonNull()) {
                rawUuid = msg.get("data").getAsString();
            } else if (msg.has("payload") && msg.get("payload").isJsonObject()) {
                com.google.gson.JsonObject payload = msg.getAsJsonObject("payload");
                if (payload.has("cardId")) rawUuid = payload.get("cardId").getAsString();
                else if (payload.has("id"))  rawUuid = payload.get("id").getAsString();
            }
            if (rawUuid == null) { sendError("Missing 'data' or 'payload.cardId' field"); return; }
            try {
                UUID data = UUID.fromString(rawUuid);
                gc.sendPlayerUUID(userId, data);
                // Clear any pending query for this player when they respond
                UUID pid = userPlayerIdMap.get(userId);
                if (pid != null) pendingPlayerQuery.remove(pid);
            } catch (IllegalArgumentException e) {
                sendError("Invalid UUID: " + e.getMessage());
            }
        }

        private void handleSendBoolean(JsonObject msg) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            if (!msg.has("data")) { sendError("Missing 'data' field"); return; }
            boolean data = msg.get("data").getAsBoolean();
            gc.sendPlayerBoolean(userId, data);
            UUID pid = userPlayerIdMap.get(userId);
            if (pid != null) pendingPlayerQuery.remove(pid);
        }

        private void handleSendString(JsonObject msg) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            if (!msg.has("data")) { sendError("Missing 'data' field"); return; }
            String data = msg.get("data").getAsString();
            gc.sendPlayerString(userId, data);
        }

        private void handleSendInteger(JsonObject msg) {
            GameController gc = resolveGameController(msg);
            if (gc == null) return;
            if (!msg.has("data")) { sendError("Missing 'data' field"); return; }
            int data = msg.get("data").getAsInt();
            gc.sendPlayerInteger(userId, data);
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private GameController resolveGameController(JsonObject msg) {
            UUID xmageGameId = resolveXmageGameId(msg);
            if (xmageGameId == null) {
                sendError("Cannot determine gameId — send JOIN_GAME first or include 'gameId' in message");
                return null;
            }

            GameController gc = ApiServerModule.getGameController(xmageGameId);
            if (gc == null) {
                sendError("No active game found for gameId=" + xmageGameId);
            }
            return gc;
        }

        /**
         * Resolve the *XMage internal* game UUID from a WS message.
         * The message may carry a WS-level gameId; we translate it via wsGameToXmageGame.
         * If the XMage game UUID is not yet known (game not started), returns null.
         */
        private UUID resolveXmageGameId(JsonObject msg) {
            UUID wsGameId = null;
            if (msg.has("gameId")) {
                try {
                    wsGameId = UUID.fromString(msg.get("gameId").getAsString());
                } catch (IllegalArgumentException ignored) {}
            }
            if (wsGameId == null && userId != null) {
                wsGameId = userGameMap.get(userId);
            }
            if (wsGameId == null) return null;
            return wsGameToXmageGame.get(wsGameId);
        }

        private void sendError(String message) {
            JsonObject err = new JsonObject();
            err.addProperty("type", "ERROR");
            err.addProperty("message", message);
            sendJson(err.toString());
        }

        private void sendJson(String json) {
            if (session != null && session.isOpen()) {
                try {
                    session.getRemote().sendString(json);
                } catch (IOException e) {
                    logger.error("Failed to send WebSocket message", e);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Static helpers (shared between GameWebSocket instances)
    // ------------------------------------------------------------------

    /**
     * Create a User in XMage's UserManager and mark it as Connected.
     * Returns null if a user with that name already exists.
     */
    private static User createSyntheticUser(ManagerFactory mf, String name) {
        java.util.Optional<User> existing = mf.userManager().getUserByName(name);
        if (existing.isPresent()) {
            // Reuse existing user if already created (idempotent reconnect)
            User u = existing.get();
            u.setUserState(User.UserState.Connected);
            return u;
        }
        java.util.Optional<User> opt = mf.userManager().createUser(name, "ws-bridge", null);
        if (!opt.isPresent()) {
            return null;
        }
        User user = opt.get();
        // Mark as connected so isAllJoined() passes
        user.setUserState(User.UserState.Connected);
        return user;
    }

    /**
     * Build MatchOptions appropriate for the given format.
     */
    private static MatchOptions buildMatchOptions(String format, String ownerName) {
        String gameType = "Two Player Duel";
        String deckType = "Constructed - Freeform";

        switch (format.toUpperCase()) {
            case "COMMANDER":
                gameType = "Commander Two Player Duel";
                deckType = "Constructed - Commander";
                break;
            case "PAUPER":
                deckType = "Constructed - Pauper";
                break;
            case "VINTAGE":
                deckType = "Constructed - Vintage";
                break;
            case "LEGACY":
                deckType = "Constructed - Legacy";
                break;
            case "MODERN":
                deckType = "Constructed - Modern";
                break;
            case "PIONEER":
                deckType = "Constructed - Pioneer";
                break;
            case "STANDARD":
            default:
                deckType = "Constructed - Freeform";
                break;
        }

        MatchOptions options = new MatchOptions(ownerName + "'s Game", gameType, false);
        options.setDeckType(deckType);
        options.getPlayerTypes().add(PlayerType.HUMAN); // seat 0
        options.getPlayerTypes().add(PlayerType.HUMAN); // seat 1
        options.setWinsNeeded(1);
        options.setAttackOption(MultiplayerAttackOption.LEFT);
        return options;
    }

    /**
     * Parse a deck text (one card per line: "N CardName") into DeckCardLists.
     * Uses CardRepository to look up set codes.
     */
    static DeckCardLists parseDeckText(String deckText) {
        DeckCardLists deckLists = new DeckCardLists();
        deckLists.setName("WS Deck");
        boolean inSideboard = false;

        for (String rawLine : deckText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                // empty line separates main deck from sideboard in MTGO format
                inSideboard = true;
                continue;
            }
            if (line.startsWith("//") || line.startsWith("#")) {
                continue; // comment
            }
            // Parse "N CardName" or "N [SETCODE] CardName"
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx < 0) continue;
            int count;
            try {
                count = Integer.parseInt(line.substring(0, spaceIdx).trim());
            } catch (NumberFormatException e) {
                continue; // skip unparseable lines
            }
            String cardName = line.substring(spaceIdx + 1).trim();
            // Strip inline set code like "[M21] Lightning Bolt"
            if (cardName.startsWith("[")) {
                int closeBracket = cardName.indexOf(']');
                if (closeBracket >= 0) {
                    cardName = cardName.substring(closeBracket + 1).trim();
                }
            }
            if (cardName.isEmpty()) continue;

            // For DFC names like "Front // Back", look up by front face only
            String lookupName = cardName.contains(" // ")
                    ? cardName.split(" // ")[0].trim()
                    : cardName;
            CardInfo info = CardRepository.instance.findPreferredCoreExpansionCard(lookupName);
            if (info == null) {
                info = CardRepository.instance.findCard(lookupName, true);
            }
            // If still not found, retry with the full name (some sets store the full DFC name)
            if (info == null && !lookupName.equals(cardName)) {
                info = CardRepository.instance.findPreferredCoreExpansionCard(cardName);
                if (info == null) {
                    info = CardRepository.instance.findCard(cardName, true);
                }
            }

            for (int i = 0; i < count; i++) {
                DeckCardInfo dci;
                if (info != null) {
                    dci = new DeckCardInfo(info.getName(), info.getCardNumber(), info.getSetCode());
                } else {
                    // Unknown card — add by name with placeholder set info; validation may fail
                    dci = new DeckCardInfo(cardName, "0", "UNK");
                }
                if (inSideboard) {
                    deckLists.getSideboard().add(dci);
                } else {
                    deckLists.getCards().add(dci);
                }
            }
        }
        return deckLists;
    }

    /**
     * Find the userId of the first player (game owner) for a WS game ID.
     * Excludes {@code excludeUserId} (the second player who just joined).
     */
    private static UUID findOwnerUserId(UUID wsGameId, UUID excludeUserId) {
        for (Map.Entry<UUID, UUID> entry : userGameMap.entrySet()) {
            if (wsGameId.equals(entry.getValue()) && !entry.getKey().equals(excludeUserId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Schedule a background task that:
     *   1. Waits 1 s for the game engine to create the GameController
     *   2. Calls joinGame for both human players
     *   3. Starts a 500 ms polling loop to push GAME_STATE to all connected clients
     */
    private static void scheduleGameJoinAndPoll(
            ManagerFactory mf, UUID wsGameId, UUID tableId, UUID userId1, UUID userId2) {

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-game-poller-" + wsGameId);
            t.setDaemon(true);
            return t;
        });
        gamePollers.put(wsGameId, executor);

        executor.schedule(() -> {
            try {
                logger.info("[WS] scheduleGameJoinAndPoll: lambda started wsGameId=" + wsGameId + " tableId=" + tableId);

                // Locate the XMage game UUID via the TableController -> Match -> Game
                mage.server.TableController tc = mf.tableManager().getController(tableId).orElse(null);
                if (tc == null) {
                    logger.error("[WS] TableController not found for tableId=" + tableId);
                    return;
                }
                mage.game.match.Match match = tc.getMatch();
                if (match == null || match.getGame() == null) {
                    logger.error("[WS] Match/Game not started for tableId=" + tableId + " match=" + match);
                    return;
                }
                UUID xmageGameId = match.getGame().getId();
                wsGameToXmageGame.put(wsGameId, xmageGameId);
                logger.info("[WS] xmageGameId=" + xmageGameId + " for wsGameId=" + wsGameId);

                // Join the game session for each human player (triggers GameController.join)
                mf.gameManager().joinGame(xmageGameId, userId1);
                logger.info("[WS] joinGame called for userId1=" + userId1);
                if (userId2 != null) {
                    mf.gameManager().joinGame(xmageGameId, userId2);
                    logger.info("[WS] joinGame called for userId2=" + userId2);
                }

                // Wait for GameController to be created (startGame runs in callExecutor)
                Thread.sleep(1500);

                GameController gc = ApiServerModule.getGameController(xmageGameId);
                logger.info("[WS] GameController=" + (gc != null ? "FOUND" : "NULL") + " xmageGameId=" + xmageGameId);
                if (gc == null) {
                    logger.warn("[WS] GameController not found — skipping auto-start responses");
                } else {
                    // Resolve XMage player UUIDs (needed for "choose starting player" response)
                    UUID playerId1 = resolvePlayerIdForUser(gc, userId1, mf);
                    UUID playerId2 = (userId2 != null) ? resolvePlayerIdForUser(gc, userId2, mf) : null;
                    logger.info("[WS] playerId1=" + playerId1 + " playerId2=" + playerId2);

                    // Auto-respond to game startup prompts using two parallel threads, one per
                    // player. Each thread first sends a UUID (for "choose starting player") then
                    // a Boolean false (for mulligan keep).
                    //
                    // The game asks only ONE player to choose the starting player (UUID prompt)
                    // then asks BOTH players for mulligan (Boolean prompt).
                    //
                    // Both threads call sendPlayerUUIDDirect first. For the non-choosing player
                    // the UUID call's waitResponseOpen() will return when the mulligan window
                    // opens (any response-window triggers it). The game ignores the UUID on a
                    // Boolean question and asks again; the thread immediately follows with the
                    // Boolean, answering the repeated question correctly.
                    final UUID startingPlayerId = (playerId1 != null) ? playerId1
                            : (playerId2 != null ? playerId2 : null);

                    final UUID finalUserId1 = userId1;
                    final UUID finalUserId2 = userId2;
                    final GameController finalGc = gc;

                    Thread t1 = new Thread(() -> {
                        try {
                            logger.info("[WS-t1] sending UUID for starting player");
                            finalGc.sendPlayerUUIDDirect(finalUserId1, startingPlayerId);
                            logger.info("[WS-t1] UUID sent; sending keep (false) for mulligan");
                            finalGc.sendPlayerBooleanDirect(finalUserId1, false);
                            logger.info("[WS-t1] done");
                        } catch (Exception e) {
                            logger.warn("[WS-t1] error: " + e.getMessage(), e);
                        }
                    }, "ws-auto-p1-" + wsGameId);
                    t1.setDaemon(true);

                    Thread t2 = (finalUserId2 != null) ? new Thread(() -> {
                        try {
                            logger.info("[WS-t2] sending UUID for starting player");
                            finalGc.sendPlayerUUIDDirect(finalUserId2, startingPlayerId);
                            logger.info("[WS-t2] UUID sent; sending keep (false) for mulligan");
                            finalGc.sendPlayerBooleanDirect(finalUserId2, false);
                            logger.info("[WS-t2] done");
                        } catch (Exception e) {
                            logger.warn("[WS-t2] error: " + e.getMessage(), e);
                        }
                    }, "ws-auto-p2-" + wsGameId) : null;
                    if (t2 != null) t2.setDaemon(true);

                    t1.start();
                    if (t2 != null) t2.start();

                    // Wait up to 65 s for both threads (30s UUID wait + 30s Boolean wait + 5s buffer)
                    t1.join(65000);
                    if (t2 != null) t2.join(65000);
                    logger.info("[WS] auto-start threads finished for wsGameId=" + wsGameId);

                    // Register reverse player→user maps and PlayerQueryListener so the bridge
                    // can forward "waiting for input" events to the correct WS client.
                    if (playerId1 != null) xmagePlayerToUserId.put(playerId1, userId1);
                    if (playerId2 != null && userId2 != null) xmagePlayerToUserId.put(playerId2, userId2);

                    finalGc.addPlayerQueryListener((qPlayerId, event) -> {
                        // Record the pending query so the next GAME_STATE push includes it
                        Map<String, Object> q = new java.util.LinkedHashMap<>();
                        q.put("queryType", event.getQueryType().toString());
                        q.put("message", event.getMessage());

                        java.util.List<String> validTargets = new java.util.ArrayList<>();
                        if (event.getTargets() != null) {
                            for (UUID t : event.getTargets()) validTargets.add(t.toString());
                        }
                        if (event.getPerms() != null) {
                            for (mage.game.permanent.Permanent perm : event.getPerms())
                                validTargets.add(perm.getId().toString());
                        }
                        if (!validTargets.isEmpty()) q.put("validTargets", validTargets);

                        pendingPlayerQuery.put(qPlayerId, q);

                        // Also push a lightweight WAITING_FOR_INPUT notification immediately
                        UUID qUserId = xmagePlayerToUserId.get(qPlayerId);
                        if (qUserId != null) {
                            Session wsSession = userSessions.get(qUserId);
                            if (wsSession != null && wsSession.isOpen()) {
                                try {
                                    com.google.gson.JsonObject notif = new com.google.gson.JsonObject();
                                    notif.addProperty("type", "WAITING_FOR_INPUT");
                                    notif.addProperty("queryType", event.getQueryType().toString());
                                    notif.addProperty("message", event.getMessage() != null ? event.getMessage() : "");
                                    wsSession.getRemote().sendString(notif.toString());
                                } catch (Exception ignored) {}
                            }
                        }
                    });
                }

                logger.info("[WS] Starting polling loop for wsGameId=" + wsGameId);

                // Start polling loop
                final UUID finalXmageGameId = xmageGameId;
                executor.scheduleAtFixedRate(() -> {
                    try {
                        pollAndPush(mf, wsGameId, finalXmageGameId);
                    } catch (Exception e) {
                        logger.warn("Polling error for wsGameId=" + wsGameId + ": " + e.getMessage(), e);
                    }
                }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                logger.error("[WS] Error during game join/poll setup for wsGameId=" + wsGameId, e);
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Poll game state for all players of a given game and push to their WS sessions.
     */
    // Throttle pollAndPush debug logs to avoid log spam (log every ~10 seconds)
    private static final java.util.concurrent.atomic.AtomicLong pollLogCounter = new java.util.concurrent.atomic.AtomicLong();

    private static void pollAndPush(ManagerFactory mf, UUID wsGameId, UUID xmageGameId) {
        GameController gc = ApiServerModule.getGameController(xmageGameId);
        long pollCount = pollLogCounter.incrementAndGet();
        if (pollCount <= 3 || pollCount % 20 == 0) {
            logger.info("[WS] pollAndPush #" + pollCount + " wsGameId=" + wsGameId
                    + " gc=" + (gc != null ? "FOUND" : "NULL"));
        }
        if (gc == null) {
            // Game ended or not yet started
            return;
        }

        for (Map.Entry<UUID, UUID> entry : userGameMap.entrySet()) {
            UUID userId = entry.getKey();
            if (!wsGameId.equals(entry.getValue())) continue;

            Session wsSession = userSessions.get(userId);
            if (wsSession == null || !wsSession.isOpen()) continue;

            // Get the player UUID for this user
            UUID playerId = userPlayerIdMap.get(userId);
            if (playerId == null) {
                playerId = resolvePlayerIdForUser(gc, userId, mf);
                if (playerId != null) {
                    userPlayerIdMap.put(userId, playerId);
                    logger.info("Resolved playerId=" + playerId + " for userId=" + userId);
                } else {
                    logger.warn("Cannot resolve playerId for userId=" + userId + " wsGameId=" + wsGameId);
                }
            }
            if (playerId == null) continue;

            try {
                GameView view = mf.gameManager().getGameView(xmageGameId, playerId);
                if (view != null) {
                    pushGameState(wsGameId, view, userId, playerId);
                }
            } catch (Exception e) {
                logger.warn("Failed to get/push GameView for userId=" + userId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Resolve the XMage playerId for a given userId by inspecting the GameController's
     * userPlayerMap (not directly accessible, but readable via game.getPlayer chain).
     */
    private static UUID resolvePlayerIdForUser(GameController gc, UUID userId, ManagerFactory mf) {
        // We can get the game object via getGameView for any valid playerId,
        // but there's no direct API. Instead, iterate through all players in the match
        // by finding the Table and using userPlayerMap stored in TableController.
        // Since TableController.userPlayerMap is package-private, we use the tableId stored in gc.

        // Approach: iterate game players and find the one whose userId maps to this user
        // GameController doesn't expose userPlayerMap directly, but we can find the
        // matching TableController via tableId.
        try {
            java.lang.reflect.Field tableIdField = gc.getClass().getDeclaredField("tableId");
            tableIdField.setAccessible(true);
            UUID tableId = (UUID) tableIdField.get(gc);

            mage.server.TableController tc = mf.tableManager().getController(tableId).orElse(null);
            if (tc == null) return null;

            java.lang.reflect.Field userPlayerMapField = tc.getClass().getDeclaredField("userPlayerMap");
            userPlayerMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, UUID> userPlayerMap =
                    (java.util.concurrent.ConcurrentHashMap<UUID, UUID>) userPlayerMapField.get(tc);
            return userPlayerMap.get(userId);
        } catch (Exception e) {
            logger.debug("Could not resolve playerId via reflection: " + e.getMessage());
            return null;
        }
    }
}
