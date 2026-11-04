package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.util.Http;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Third-party ("extension") emotes: FrankerFaceZ, BetterTTV and 7TV, both global and for the
 * joined channel. Channel emotes beat global ones; 7TV beats BTTV beats FFZ.
 * Twitch emotes (including ones from other channels a viewer is subscribed to) come straight
 * from each chat message's tags and need no lookup here.
 */
public final class EmoteRegistry {
    private static volatile Map<String, EmoteDef> ffzGlobal = Map.of();
    private static volatile Map<String, EmoteDef> bttvGlobal = Map.of();
    private static volatile Map<String, EmoteDef> stvGlobal = Map.of();
    private static volatile Map<String, EmoteDef> ffzChannel = Map.of();
    private static volatile Map<String, EmoteDef> bttvChannel = Map.of();
    private static volatile Map<String, EmoteDef> stvChannel = Map.of();
    private static volatile boolean globalsLoaded;
    private static volatile String roomId;

    private EmoteRegistry() {
    }

    public static EmoteDef lookup(String word, ChatConfig cfg) {
        EmoteDef d;
        if (cfg.sevenTvEmotes && (d = stvChannel.get(word)) != null) return d;
        if (cfg.bttvEmotes && (d = bttvChannel.get(word)) != null) return d;
        if (cfg.ffzEmotes && (d = ffzChannel.get(word)) != null) return d;
        if (cfg.sevenTvEmotes && (d = stvGlobal.get(word)) != null) return d;
        if (cfg.bttvEmotes && (d = bttvGlobal.get(word)) != null) return d;
        if (cfg.ffzEmotes && (d = ffzGlobal.get(word)) != null) return d;
        return null;
    }

    public static String summary() {
        return "7TV " + (stvChannel.size() + stvGlobal.size())
                + " · BTTV " + (bttvChannel.size() + bttvGlobal.size())
                + " · FFZ " + (ffzChannel.size() + ffzGlobal.size())
                + " · images " + ImageCache.loadedCount();
    }

    public static void ensureGlobals() {
        if (!globalsLoaded) loadGlobals();
    }

    public static void loadGlobals() {
        globalsLoaded = true;
        Http.POOL.execute(() -> {
            try {
                JsonObject root = Http.getJson("https://api.frankerfacez.com/v1/set/global").getAsJsonObject();
                Set<String> defaults = new HashSet<>();
                JsonArray ds = arr(root, "default_sets");
                if (ds != null) ds.forEach(e -> defaults.add(e.getAsString()));
                ffzGlobal = parseFfz(root, defaults.isEmpty() ? null : defaults);
            } catch (Exception e) {
                MCTwitchChat.LOG.warn("FFZ global emotes failed: {}", e.toString());
            }
        });
        Http.POOL.execute(() -> {
            try {
                Map<String, EmoteDef> out = new HashMap<>();
                parseBttv(Http.getJson("https://api.betterttv.net/3/cached/emotes/global").getAsJsonArray(), out);
                bttvGlobal = out;
            } catch (Exception e) {
                MCTwitchChat.LOG.warn("BTTV global emotes failed: {}", e.toString());
            }
        });
        Http.POOL.execute(() -> {
            try {
                Map<String, EmoteDef> out = new HashMap<>();
                parse7tv(arr(Http.getJson("https://7tv.io/v3/emote-sets/global").getAsJsonObject(), "emotes"), out);
                stvGlobal = out;
            } catch (Exception e) {
                MCTwitchChat.LOG.warn("7TV global emotes failed: {}", e.toString());
            }
        });
    }

    /** Called with the channel's numeric Twitch id from ROOMSTATE. */
    public static void loadChannel(String id) {
        if (id == null || id.isEmpty() || id.equals(roomId)) return;
        roomId = id;
        ffzChannel = Map.of();
        bttvChannel = Map.of();
        stvChannel = Map.of();
        Http.POOL.execute(() -> {
            try {
                ffzChannel = parseFfz(Http.getJson("https://api.frankerfacez.com/v1/room/id/" + id).getAsJsonObject(), null);
            } catch (Exception e) {
                MCTwitchChat.LOG.info("No FFZ channel emotes: {}", e.toString());
            }
        });
        Http.POOL.execute(() -> {
            try {
                JsonObject root = Http.getJson("https://api.betterttv.net/3/cached/users/twitch/" + id).getAsJsonObject();
                Map<String, EmoteDef> out = new HashMap<>();
                parseBttv(arr(root, "sharedEmotes"), out);
                parseBttv(arr(root, "channelEmotes"), out);
                bttvChannel = out;
            } catch (Exception e) {
                MCTwitchChat.LOG.info("No BTTV channel emotes: {}", e.toString());
            }
        });
        Http.POOL.execute(() -> {
            try {
                JsonObject root = Http.getJson("https://7tv.io/v3/users/twitch/" + id).getAsJsonObject();
                JsonObject set = obj(root, "emote_set");
                Map<String, EmoteDef> out = new HashMap<>();
                if (set != null) parse7tv(arr(set, "emotes"), out);
                stvChannel = out;
            } catch (Exception e) {
                MCTwitchChat.LOG.info("No 7TV channel emotes: {}", e.toString());
            }
        });
    }

    /** Re-downloads every emote list. */
    public static void reloadAll() {
        String id = roomId;
        roomId = null;
        loadGlobals();
        if (id != null) loadChannel(id);
    }

    // ---------------------------------------------------------------- parsers

    private static Map<String, EmoteDef> parseFfz(JsonObject root, Set<String> onlySets) {
        Map<String, EmoteDef> out = new HashMap<>();
        JsonObject sets = obj(root, "sets");
        if (sets == null) return out;
        for (Map.Entry<String, JsonElement> set : sets.entrySet()) {
            if (onlySets != null && !onlySets.contains(set.getKey())) continue;
            if (!set.getValue().isJsonObject()) continue;
            JsonArray emotes = arr(set.getValue().getAsJsonObject(), "emoticons");
            if (emotes == null) continue;
            for (JsonElement el : emotes) {
                if (!el.isJsonObject()) continue;
                JsonObject e = el.getAsJsonObject();
                String name = str(e, "name");
                String still = pickFfzUrl(obj(e, "urls"));
                if (name == null || still == null) continue;
                List<String> statics = List.of(still);
                List<String> animated = statics;
                String anim = pickFfzUrl(obj(e, "animated"));
                if (anim != null) animated = List.of(anim + ".gif", still);
                out.put(name, new EmoteDef(name, "FrankerFaceZ", animated, statics, num(e, "width", 28), num(e, "height", 28)));
            }
        }
        return out;
    }

    private static String pickFfzUrl(JsonObject urls) {
        if (urls == null) return null;
        for (String k : new String[]{"2", "1", "4"}) {
            String u = str(urls, k);
            if (u != null) return Http.fixUrl(u);
        }
        return null;
    }

    private static void parseBttv(JsonArray arr, Map<String, EmoteDef> out) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String id = str(e, "id");
            String code = str(e, "code");
            if (id == null || code == null) continue;
            String type = str(e, "imageType");
            boolean animated = bool(e, "animated") || "gif".equals(type);
            String base = "https://cdn.betterttv.net/emote/" + id + "/2x";
            List<String> statics = List.of(base + ".png", base);
            List<String> anim = animated ? List.of(base + ".gif", base) : statics;
            out.put(code, new EmoteDef(code, "BetterTTV", anim, statics, num(e, "width", 28), num(e, "height", 28)));
        }
    }

    private static void parse7tv(JsonArray arr, Map<String, EmoteDef> out) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String name = str(e, "name");
            JsonObject data = obj(e, "data");
            JsonObject host = data == null ? null : obj(data, "host");
            String url = host == null ? null : Http.fixUrl(str(host, "url"));
            if (name == null || url == null) continue;
            int w = 28;
            int h = 28;
            JsonArray files = arr(host, "files");
            if (files != null) {
                for (JsonElement f : files) {
                    if (!f.isJsonObject()) continue;
                    String fn = str(f.getAsJsonObject(), "name");
                    if (fn != null && fn.startsWith("1x")) {
                        w = num(f.getAsJsonObject(), "width", w);
                        h = num(f.getAsJsonObject(), "height", h);
                        break;
                    }
                }
            }
            boolean animated = bool(data, "animated");
            List<String> statics = List.of(url + "/2x.png", url + "/1x.png");
            List<String> anim = animated ? List.of(url + "/2x.gif", url + "/1x.gif", url + "/2x.png") : statics;
            out.put(name, new EmoteDef(name, "7TV", anim, statics, w, h));
        }
    }

    // ---------------------------------------------------------------- json helpers

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    private static int num(JsonObject o, String k, int def) {
        JsonElement e = o == null ? null : o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        int v = e.getAsInt();
        return v > 0 ? v : def;
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
