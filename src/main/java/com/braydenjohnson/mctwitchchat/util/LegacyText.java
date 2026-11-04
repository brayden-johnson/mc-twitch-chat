package com.braydenjohnson.mctwitchchat.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Locale;

/** Turns "&d[Stream]&r " style strings (and &#RRGGBB) into styled components. */
public final class LegacyText {
    private LegacyText() {
    }

    public static MutableComponent parse(String s) {
        return parse(s, Style.EMPTY);
    }

    public static MutableComponent parse(String s, Style base) {
        MutableComponent root = Component.empty();
        if (s == null || s.isEmpty()) return root;
        Style style = base;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                char n = Character.toLowerCase(s.charAt(i + 1));
                if (n == '#' && i + 8 <= s.length() && isHex(s.substring(i + 2, i + 8))) {
                    flush(root, sb, style);
                    style = base.withColor(TextColor.fromRgb(Integer.parseInt(s.substring(i + 2, i + 8), 16)));
                    i += 7;
                    continue;
                }
                ChatFormatting f = ChatFormatting.getByCode(n);
                if (f != null) {
                    flush(root, sb, style);
                    if (f == ChatFormatting.RESET) style = base;
                    else if (f.isColor()) style = base.withColor(f);
                    else style = style.applyFormat(f);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        flush(root, sb, style);
        return root;
    }

    /**
     * A style from a colour setting. Accepts "&e", "&6&l", "#FFAA00", "FFAA00", "&#FFAA00" or a name like "gold".
     */
    public static Style parseStyle(String s) {
        if (s == null) return Style.EMPTY;
        String t = s.trim();
        String hex = t.startsWith("#") ? t.substring(1) : t;
        if (hex.length() == 6 && isHex(hex)) return Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
        ChatFormatting named = ChatFormatting.getByName(t.toLowerCase(Locale.ROOT));
        if (named != null && named.isColor()) return Style.EMPTY.withColor(named);
        Style style = Style.EMPTY;
        for (int i = 0; i + 1 < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '&' && c != '§') continue;
            char n = Character.toLowerCase(t.charAt(i + 1));
            if (n == '#' && i + 8 <= t.length() && isHex(t.substring(i + 2, i + 8))) {
                style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(t.substring(i + 2, i + 8), 16)));
                i += 7;
                continue;
            }
            ChatFormatting f = ChatFormatting.getByCode(n);
            if (f == null) continue;
            if (f == ChatFormatting.RESET) style = Style.EMPTY;
            else if (f.isColor()) style = Style.EMPTY.withColor(f);
            else style = style.applyFormat(f);
            i++;
        }
        return style;
    }

    /** Text with all colour codes removed. */
    public static String strip(String s) {
        return parse(s).getString();
    }

    private static void flush(MutableComponent root, StringBuilder sb, Style style) {
        if (sb.isEmpty()) return;
        root.append(Component.literal(sb.toString()).setStyle(style));
        sb.setLength(0);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return !s.isEmpty();
    }
}
