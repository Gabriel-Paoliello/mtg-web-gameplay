package mage.server.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mage.view.*;

import java.util.*;

/**
 * Serializes XMage GameView / PlayerView objects to a JSON structure suitable
 * for WebSocket clients.
 *
 * serialize() now accepts the local player UUID so that:
 *  - The local player's hand is returned as full card objects.
 *  - Opponents' hands are returned as face-down placeholder objects.
 *  - The root includes myPlayerId, priorityPlayerId, and canPlayIds.
 */
public final class GameStateSerializer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private GameStateSerializer() {}

    public static String serialize(UUID gameId, String format, GameView view, UUID localPlayerId) {
        return serialize(gameId, format, view, localPlayerId, null);
    }

    public static String serialize(UUID gameId, String format, GameView view,
                                   UUID localPlayerId, Map<String, Object> pendingQuery) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("gameId", gameId != null ? gameId.toString() : null);
        root.put("format", format);
        root.put("turn", view.getTurn());
        root.put("phase", view.getPhase() != null ? view.getPhase().toString() : null);
        root.put("step", view.getStep() != null ? view.getStep().toString() : null);
        root.put("activePlayerId", view.getActivePlayerId() != null ? view.getActivePlayerId().toString() : null);
        root.put("activePlayerName", view.getActivePlayerName());
        root.put("priorityPlayerName", view.getPriorityPlayerName());
        root.put("myPlayerId", localPlayerId != null ? localPlayerId.toString() : null);

        // Priority player UUID (derived from hasPriority flag on players)
        UUID priorityPlayerId = null;
        for (PlayerView pv : view.getPlayers()) {
            if (pv.hasPriority()) {
                priorityPlayerId = pv.getPlayerId();
                break;
            }
        }
        root.put("priorityPlayerId", priorityPlayerId != null ? priorityPlayerId.toString() : null);

        // Playable card/ability UUIDs for the local player
        List<String> canPlayIds = new ArrayList<>();
        if (view.getCanPlayObjects() != null) {
            for (UUID uid : view.getCanPlayObjects().getObjects().keySet()) {
                canPlayIds.add(uid.toString());
            }
        }
        root.put("canPlayIds", canPlayIds);

        // Hand cards from this player's perspective (full details for local player)
        CardsView myHand = view.getMyHand();

        // Players
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerView pv : view.getPlayers()) {
            boolean isLocal = localPlayerId != null && localPlayerId.equals(pv.getPlayerId());
            players.add(serializePlayer(pv, format, isLocal ? myHand : null));
        }
        root.put("players", players);

        // Stack
        List<Map<String, Object>> stack = new ArrayList<>();
        if (view.getStack() != null) {
            for (Map.Entry<UUID, CardView> entry : view.getStack().entrySet()) {
                stack.add(serializeCard(entry.getValue()));
            }
        }
        root.put("stack", stack);

        // Pending query (set by WebSocket bridge when game waits for player input)
        root.put("pendingQuery", pendingQuery);

        return GSON.toJson(root);
    }

    /** @deprecated Use {@link #serialize(UUID, String, GameView, UUID)} instead. */
    @Deprecated
    public static String serialize(UUID gameId, String format, GameView view) {
        return serialize(gameId, format, view, null, null);
    }

    // -----------------------------------------------------------------------
    // Player
    // -----------------------------------------------------------------------

    private static Map<String, Object> serializePlayer(PlayerView pv, String format,
                                                        CardsView handCards) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", pv.getPlayerId() != null ? pv.getPlayerId().toString() : null);
        p.put("name", pv.getName());
        p.put("life", pv.getLife());
        p.put("handCount", pv.getHandCount());
        p.put("library", Collections.singletonMap("count", pv.getLibraryCount()));
        p.put("isActive", pv.isActive());
        p.put("hasPriority", pv.hasPriority());

        // Mana pool
        ManaPoolView mp = pv.getManaPool();
        if (mp != null) {
            Map<String, Integer> mana = new LinkedHashMap<>();
            mana.put("W", mp.getWhite());
            mana.put("U", mp.getBlue());
            mana.put("B", mp.getBlack());
            mana.put("R", mp.getRed());
            mana.put("G", mp.getGreen());
            mana.put("C", mp.getColorless());
            p.put("manaPool", mana);
        }

        // Hand — full cards for local player, face-down placeholders for opponents
        if (handCards != null) {
            p.put("hand", serializeCardsView(handCards));
        } else {
            List<Map<String, Object>> faceDown = new ArrayList<>();
            for (int i = 0; i < pv.getHandCount(); i++) {
                Map<String, Object> fd = new LinkedHashMap<>();
                fd.put("id", null);
                fd.put("name", "Unknown");
                fd.put("faceDown", true);
                faceDown.add(fd);
            }
            p.put("hand", faceDown);
        }

        // Battlefield
        List<Map<String, Object>> battlefield = new ArrayList<>();
        if (pv.getBattlefield() != null) {
            for (Map.Entry<UUID, PermanentView> entry : pv.getBattlefield().entrySet()) {
                battlefield.add(serializePermanent(entry.getValue()));
            }
        }
        p.put("battlefield", battlefield);

        // Graveyard / Exile
        p.put("graveyard", serializeCardsView(pv.getGraveyard()));
        p.put("exile", serializeCardsView(pv.getExile()));

        // Commander zone
        List<Map<String, Object>> commandZone = new ArrayList<>();
        if (pv.getCommandObjectList() != null) {
            for (CommandObjectView cov : pv.getCommandObjectList()) {
                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("id", cov.getId() != null ? cov.getId().toString() : null);
                cmd.put("name", cov.getName());
                commandZone.add(cmd);
            }
        }
        p.put("commandZone", commandZone);

        // Counters (includes commander damage)
        List<Map<String, Object>> counters = new ArrayList<>();
        if (pv.getCounters() != null) {
            for (CounterView cv : pv.getCounters()) {
                Map<String, Object> counter = new LinkedHashMap<>();
                counter.put("name", cv.getName());
                counter.put("count", cv.getCount());
                counters.add(counter);
            }
        }
        p.put("counters", counters);

        return p;
    }

    // -----------------------------------------------------------------------
    // Cards (hand / graveyard / exile)
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> serializeCardsView(CardsView cards) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (cards == null) return list;
        for (Map.Entry<UUID, CardView> entry : cards.entrySet()) {
            list.add(serializeCard(entry.getValue()));
        }
        return list;
    }

    static Map<String, Object> serializeCard(CardView cv) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", cv.getId() != null ? cv.getId().toString() : null);
        card.put("name", cv.getName());
        card.put("manaCost", cv.getManaCostStr());

        // Card types (Creature, Instant, Land, …)
        List<String> types = new ArrayList<>();
        if (cv.getCardTypes() != null) {
            for (mage.constants.CardType t : cv.getCardTypes()) types.add(t.toString());
        }
        card.put("types", types);

        // Super types (Legendary, Basic, Snow, …)
        List<String> superTypes = new ArrayList<>();
        if (cv.getSuperTypes() != null) {
            for (mage.constants.SuperType st : cv.getSuperTypes()) superTypes.add(st.toString());
        }
        card.put("superTypes", superTypes);

        // Sub types (Goblin, Island, Vampire, …)
        List<String> subTypes = new ArrayList<>();
        if (cv.getSubTypes() != null) {
            for (mage.constants.SubType st : cv.getSubTypes()) subTypes.add(st.toString());
        }
        card.put("subTypes", subTypes);

        card.put("power", cv.getPower());
        card.put("toughness", cv.getToughness());
        card.put("loyalty", cv.getLoyalty());
        card.put("rules", cv.getRules() != null ? cv.getRules() : Collections.emptyList());
        card.put("setCode", cv.getExpansionSetCode());
        card.put("cardNumber", cv.getCardNumber());
        card.put("rarity", cv.getRarity() != null ? cv.getRarity().toString() : null);
        return card;
    }

    // -----------------------------------------------------------------------
    // Permanents (battlefield)
    // -----------------------------------------------------------------------

    private static Map<String, Object> serializePermanent(PermanentView pv) {
        Map<String, Object> p = serializeCard(pv);
        p.put("tapped", pv.isTapped());
        p.put("summoningSick", pv.hasSummoningSickness());

        // Counters on the permanent (+1/+1, loyalty, etc.)
        Map<String, Integer> counters = new LinkedHashMap<>();
        if (pv.getCounters() != null) {
            for (CounterView cv : pv.getCounters()) {
                counters.merge(cv.getName(), cv.getCount(), Integer::sum);
            }
        }
        if (!counters.isEmpty()) p.put("counters", counters);

        return p;
    }
}
