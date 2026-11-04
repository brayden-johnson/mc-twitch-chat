package com.braydenjohnson.mctwitchchat.emote;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds Unicode emoji (including skin tones, ZWJ families, flags and keycaps) and maps them
 * to Twemoji image file names.
 */
public final class EmojiParser {
    public record Piece(String text, boolean emoji, String file) {
    }

    private static final String TWEMOJI = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/";
    private static final String TWEMOJI_PINNED = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/";

    /** BMP characters that show as emoji even without U+FE0F. */
    private static final String BMP_PRESENTATION =
            "⌚⌛⏩⏪⏫⏬⏰⏳◽◾☔☕"
                    + "♈♉♊♋♌♍♎♏♐♑♒♓"
                    + "♿⚓⚡⚪⚫⚽⚾⛄⛅⛎⛔⛪"
                    + "⛲⛳⛵⛺⛽✅✊✋✨❌❎❓"
                    + "❔❕❗➕➖➗➰➿⬛⬜⭐⭕❤";

    private EmojiParser() {
    }

    public static EmoteDef def(Piece p) {
        return new EmoteDef(p.text(), "Emoji",
                List.of(TWEMOJI + p.file() + ".png", TWEMOJI_PINNED + p.file() + ".png"),
                List.of(TWEMOJI + p.file() + ".png", TWEMOJI_PINNED + p.file() + ".png"),
                72, 72);
    }

    public static boolean mayContainEmoji(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x2000 || Character.isSurrogate(c) || c == 0xA9 || c == 0xAE || c == '#' || c == '*' || (c >= '0' && c <= '9')) {
                return true;
            }
        }
        return false;
    }

    public static List<Piece> split(String s) {
        List<Piece> out = new ArrayList<>();
        if (!mayContainEmoji(s)) {
            out.add(new Piece(s, false, null));
            return out;
        }
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int end = match(s, i);
            if (end > i) {
                if (!text.isEmpty()) {
                    out.add(new Piece(text.toString(), false, null));
                    text.setLength(0);
                }
                String seq = s.substring(i, end);
                out.add(new Piece(seq, true, fileName(seq)));
                i = end;
            } else {
                int cp = s.codePointAt(i);
                text.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
        }
        if (!text.isEmpty()) out.add(new Piece(text.toString(), false, null));
        return out;
    }

    private static int match(String s, int i) {
        int cp = s.codePointAt(i);
        int j = i + Character.charCount(cp);
        int next = j < s.length() ? s.codePointAt(j) : -1;

        if ((cp >= '0' && cp <= '9') || cp == '#' || cp == '*') {
            int k = j;
            if (next == 0xFE0F) {
                k++;
                next = k < s.length() ? s.codePointAt(k) : -1;
            }
            return next == 0x20E3 ? k + 1 : i;
        }
        if (isRegional(cp)) {
            if (next != -1 && isRegional(next)) return j + Character.charCount(next);
            return i;
        }
        boolean base = isPresentation(cp) || (next == 0xFE0F && isCapable(cp));
        if (!base) return i;
        j = consumeModifiers(s, j);
        while (j < s.length() && s.codePointAt(j) == 0x200D) {
            int k = j + 1;
            if (k >= s.length()) break;
            int c2 = s.codePointAt(k);
            if (!isCapable(c2)) break;
            j = consumeModifiers(s, k + Character.charCount(c2));
        }
        return j;
    }

    private static int consumeModifiers(String s, int j) {
        while (j < s.length()) {
            int c = s.codePointAt(j);
            if (c == 0xFE0F || c == 0x20E3 || (c >= 0x1F3FB && c <= 0x1F3FF) || (c >= 0xE0020 && c <= 0xE007F)) {
                j += Character.charCount(c);
            } else {
                break;
            }
        }
        return j;
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isPresentation(int cp) {
        if (cp >= 0x1F300 && cp <= 0x1FAFF) return !(cp >= 0x1F3FB && cp <= 0x1F3FF);
        if (cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E || (cp >= 0x1F191 && cp <= 0x1F19A)) return true;
        if (cp == 0x1F201 || cp == 0x1F21A || cp == 0x1F22F || (cp >= 0x1F232 && cp <= 0x1F236)) return true;
        if ((cp >= 0x1F238 && cp <= 0x1F23A) || cp == 0x1F250 || cp == 0x1F251) return true;
        return cp < 0x10000 && BMP_PRESENTATION.indexOf(cp) >= 0;
    }

    private static boolean isCapable(int cp) {
        if (isPresentation(cp)) return true;
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;
        if (cp >= 0x2000 && cp <= 0x2BFF) return true;
        return cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp == 0xA9 || cp == 0xAE;
    }

    /** Twemoji naming: hex code points joined by '-', dropping FE0F unless the sequence has a ZWJ. */
    static String fileName(String seq) {
        boolean zwj = seq.indexOf('‍') >= 0;
        StringBuilder sb = new StringBuilder();
        seq.codePoints().forEach(cp -> {
            if (cp == 0xFE0F && !zwj) return;
            if (!sb.isEmpty()) sb.append('-');
            sb.append(Integer.toHexString(cp));
        });
        return sb.toString();
    }
}
