package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmojiParser;
import com.braydenjohnson.mctwitchchat.emote.EmoteDef;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Splits a chat message into text, Twitch emotes, extension emotes and emoji. */
public final class MessageParser {
    private record Range(int start, int end, String id) {
    }

    private MessageParser() {
    }

    /**
     * @param emotesTag the IRC "emotes" tag, e.g. "25:0-4,12-16/1902:6-10" (code point positions)
     */
    public static List<Segment> parse(String text, String emotesTag, ChatConfig cfg) {
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
                    int dash = r.indexOf('-');
                    if (dash <= 0) continue;
                    try {
                        int s = Integer.parseInt(r.substring(0, dash));
                        int e = Integer.parseInt(r.substring(dash + 1));
                        if (s >= 0 && e >= s && e < cps.length) ranges.add(new Range(s, e, id));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            ranges.sort(Comparator.comparingInt(Range::start));
        }

        int pos = 0;
        for (Range r : ranges) {
            if (r.start() < pos) continue;
            if (r.start() > pos) addText(out, new String(cps, pos, r.start() - pos), cfg);
            String name = new String(cps, r.start(), r.end() - r.start() + 1);
            out.add(emote(EmoteDef.twitch(r.id(), name), cfg));
            pos = r.end() + 1;
        }
        if (pos < cps.length) addText(out, new String(cps, pos, cps.length - pos), cfg);
        return merge(out);
    }

    private static void addText(List<Segment> out, String chunk, ChatConfig cfg) {
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
            EmoteDef def = EmoteRegistry.lookup(token, cfg);
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
        return new Segment.Emote(def.name(), def.provider(), def.image(cfg), def.width(), def.height());
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
}
