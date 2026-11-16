package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmojiParser;
import com.braydenjohnson.mctwitchchat.emote.EmoteDef;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;
import com.braydenjohnson.mctwitchchat.emote.ImageCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Splits a chat message into text, Twitch emotes, Twitch GIFs, extension emotes and emoji. */
public final class MessageParser {
    /** GIFs are decoded bigger than emotes because they are shown several lines tall. */
    public static final int GIF_MAX_WIDTH = 256;
    public static final int GIF_MAX_HEIGHT = 112;

    /** @param gifUrl null for an emote range, the GIPHY URL for a GIF range */
    private record Range(int start, int end, String id, String gifUrl) {
    }

    /**
     * Twitch emotes seen in chat this session, name → id. Quoted reply text has no emote
     * positions, so this lets quotes still show Twitch emotes (including other channels' sub
     * emotes) that have appeared in chat before.
     */
    private static final Map<String, String> TWITCH_SEEN = new ConcurrentHashMap<>();

    private MessageParser() {
    }

    /**
     * Parses text that came without Twitch's emote positions (a reply's quoted original).
     * Twitch emotes are matched by name against ones already seen in chat; GIFs aren't possible here.
     */
    public static List<Segment> parseQuoted(String text, ChatConfig cfg) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        addText(out, text, cfg, true);
        return merge(out);
    }

    /** Replaces GIFs with their title, for places too small for a GIF (reply quotes). */
    public static List<Segment> condenseGifs(List<Segment> segments) {
        List<Segment> out = new ArrayList<>(segments.size());
        for (Segment s : segments) {
            if (s instanceof Segment.Emote e && e.lines() > 0) out.add(new Segment.Text(e.name()));
            else out.add(s);
        }
        return merge(out);
    }

    /**
     * @param emotesTag the IRC "emotes" tag, e.g. "25:0-4,12-16/1902:6-10" (code point positions)
     * @param gifsTag   the IRC "gifs" tag, e.g. "0-33|gifId|https://media4.giphy.com/media/gifId/giphy.gif?..."
     */
    public static List<Segment> parse(String text, String emotesTag, String gifsTag, ChatConfig cfg) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int[] cps = text.codePoints().toArray();

        List<Range> ranges = new ArrayList<>();
        if (cfg.twitchEmotes && emotesTag != null && !emotesTag.isEmpty()) {
            for (String part : emotesTag.split("/")) {
                int colon = part.indexOf(':');
                if (colon <= 0) continue;
                String id = part.substring(0, colon);
                for (String r : part.substring(colon + 1).split(",")) {
                    addRange(ranges, r, id, null, cps.length);
                }
            }
        }
        if (cfg.twitchGifs && gifsTag != null && !gifsTag.isEmpty()) {
            // Entries are comma separated; only split on a comma that starts a new "N-N|" entry,
            // so a comma inside a URL can't break it.
            for (String entry : gifsTag.split(",(?=\\d+-\\d+\\|)")) {
                String[] parts = entry.split("\\|", 3);
                if (parts.length < 3 || parts[2].isEmpty()) continue;
                // Twitch requires the URL to be used exactly as given.
                addRange(ranges, parts[0], parts[1], parts[2], cps.length);
            }
        }
        ranges.sort(Comparator.comparingInt(Range::start));

        int pos = 0;
        for (Range r : ranges) {
            if (r.start() < pos) continue;
            if (r.start() > pos) addText(out, new String(cps, pos, r.start() - pos), cfg, false);
            String name = new String(cps, r.start(), r.end() - r.start() + 1);
            if (r.gifUrl() != null) {
                out.add(new Segment.Emote(name, "Twitch GIF (GIPHY)",
                        ImageCache.get(List.of(r.gifUrl()), GIF_MAX_WIDTH, GIF_MAX_HEIGHT),
                        16, 9, cfg.gifLines));
            } else {
                out.add(emote(EmoteDef.twitch(r.id(), name), cfg));
                if (TWITCH_SEEN.size() < 20000) TWITCH_SEEN.put(name, r.id());
            }
            pos = r.end() + 1;
        }
        if (pos < cps.length) addText(out, new String(cps, pos, cps.length - pos), cfg, false);
        return merge(out);
    }

    private static void addRange(List<Range> ranges, String r, String id, String gifUrl, int length) {
        int dash = r.indexOf('-');
        if (dash <= 0) return;
        try {
            int s = Integer.parseInt(r.substring(0, dash).trim());
            int e = Integer.parseInt(r.substring(dash + 1).trim());
            if (s >= 0 && e >= s && e < length) ranges.add(new Range(s, e, id, gifUrl));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void addText(List<Segment> out, String chunk, ChatConfig cfg, boolean matchTwitchByName) {
        int n = chunk.length();
        int i = 0;
        while (i < n) {
            boolean space = chunk.charAt(i) == ' ';
            int j = i;
            while (j < n && (chunk.charAt(j) == ' ') == space) j++;
            String token = chunk.substring(i, j);
            i = j;
            if (space) {
                out.add(new Segment.Text(token));
                continue;
            }
            String twitchId = matchTwitchByName && cfg.twitchEmotes ? TWITCH_SEEN.get(token) : null;
            EmoteDef def = twitchId != null ? EmoteDef.twitch(twitchId, token) : EmoteRegistry.lookup(token, cfg);
            if (def != null) {
                out.add(emote(def, cfg));
                continue;
            }
            if (cfg.emoji) {
                for (EmojiParser.Piece p : EmojiParser.split(token)) {
                    out.add(p.emoji() ? emote(EmojiParser.def(p), cfg) : new Segment.Text(p.text()));
                }
            } else {
                out.add(new Segment.Text(token));
            }
        }
    }

    private static Segment emote(EmoteDef def, ChatConfig cfg) {
        return new Segment.Emote(def.name(), def.provider(), def.image(cfg), def.width(), def.height(), 0);
    }

    private static List<Segment> merge(List<Segment> in) {
        List<Segment> out = new ArrayList<>(in.size());
        StringBuilder text = new StringBuilder();
        for (Segment s : in) {
            if (s instanceof Segment.Text t) {
                text.append(t.text());
            } else {
                if (!text.isEmpty()) {
                    out.add(new Segment.Text(text.toString()));
                    text.setLength(0);
                }
                out.add(s);
            }
        }
        if (!text.isEmpty()) out.add(new Segment.Text(text.toString()));
        return out;
    }

    /** How many extra blank chat lines a message needs below it so its GIFs have room. */
    public static int extraLines(List<Segment> segments) {
        int lines = 0;
        for (Segment s : segments) {
            if (s instanceof Segment.Emote e && e.lines() > 1 && !e.image().failed()) lines = Math.max(lines, e.lines() - 1);
        }
        return lines;
    }
}
