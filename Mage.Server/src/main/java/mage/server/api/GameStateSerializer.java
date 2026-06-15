package mage.server.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mage.view.*;

import java.util.*;

/**
 * Serializes XMage GameView / PlayerView objects to a JSON structure suitable
 * for WebSocket clients.  Only view objects are read – the live Game object is
 * never touched here.
 */
public final class GameStateSerializer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private GameStateSerializer() {}

    /**
     * Build a plain Map tree from a GameView so it can be serialized to JSON
     * without pulling in every Serializable implementation detail.
     */
    public static String serialize(UUID gameId, String format, GameView view) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("gameId", gameId != null ? gameId.toString() : null);
        root.put("format", format);
        root.put("turn", view.getTurn());
        root.put("phase", view.getPhase() != null ? view.getPhase().toString() : null);
        root.put("step", view.getStep() != null ? view.getStep().toString() : null);
        root.put("activePlayerId", view.getActivePlayerId() != null ? view.getActivePlayerId().toString() : null);
        root.put("activePlayerName", view.getActivePlayerName());
        root.put("priorityPlayerName", view.getPriorityPlayerName());

        // players
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerView pv : view.getPlayers()) {
            players.add(serializePlayer(pv, format));
        }
        root.put("players", players);

        // stack
        List<Map<String, Object>> stack = new ArrayList<>();
        if (view.getStack() != null) {
            for (Map.Entry<UUID, CardView> entry : view.getStack().entrySet()) {
                stack.add(serializeStackCard(entry.getValue()));
            }
        }
        root.put("stack", stack);

        // local player's full hand (only visible to them)
        root.put("myHand", serializeCardsView(view.getMyHand()));

        return GSON.toJson(root);
    }

    private static Map<String, Object> serializePlayer(PlayerView pv, String format) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", pv.getPlayerId() != null ? pv.getPlayerId().toString() : null);
        p.put("name", pv.getName());
        p.put("life", pv.getLife());
        p.put("handCount", pv.getHandCount());
        p.put("library", Collections.singletonMap("count", pv.getLibraryCount()));
        p.put("isActive", pv.isActive());
        p.put("hasPriority", pv.hasPriority());

        // mana pool
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

        // battlefield
        List<Map<String, Object>> battlefield = new ArrayList<>();
        if (pv.getBattlefield() != null) {
            for (Map.Entry<UUID, PermanentView> entry : pv.getBattlefield().entrySet()) {
                battlefield.add(serializePermanent(entry.getValue()));
            }
        }
        p.put("battlefield", battlefield);

        // graveyard
        p.put("graveyard", serializeCardsView(pv.getGraveyard()));

        // exile
        p.put("exile", serializeCardsView(pv.getExile()));

        // commander-specific fields (present for all formats; empty for non-Commander)
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

        // commanderDamage is stored per-player in PlayerView counters; expose raw
        // counter list so clients can extract COMMANDER_DAMAGE counters by name.
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

    private static Map<String, Object> serializeStackCard(CardView cv) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", cv.getId() != null ? cv.getId().toString() : null);
        s.put("name", cv.getName());
        // targets are embedded in the card's rules; expose them as a list via rules
        List<String> rules = cv.getRules();
        s.put("rules", rules != null ? rules : Collections.emptyList());
        return s;
    }

    private static List<Map<String, Object>> serializeCardsView(CardsView cards) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (cards == null) {
            return list;
        }
        for (Map.Entry<UUID, CardView> entry : cards.entrySet()) {
            CardView cv = entry.getValue();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", cv.getId() != null ? cv.getId().toString() : null);
            card.put("name", cv.getName());
            card.put("rarity", cv.getRarity() != null ? cv.getRarity().toString() : null);
            list.add(card);
        }
        return list;
    }

    private static Map<String, Object> serializePermanent(PermanentView pv) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", pv.getId() != null ? pv.getId().toString() : null);
        p.put("name", pv.getName());
        p.put("rarity", pv.getRarity() != null ? pv.getRarity().toString() : null);
        p.put("tapped", pv.isTapped());
        p.put("power", pv.getPower());
        p.put("toughness", pv.getToughness());
        p.put("loyalty", pv.getLoyalty());
        return p;
    }
}
