package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.Placeholders;
import com.braydenjohnson.mctwitchchat.util.LegacyText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Builds the Minecraft chat line for a Twitch message from the current settings. */
public final class Formatter {
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm");
    /** Twitch's palette for users who never picked a colour. */
    private static final int[] DEFAULT_COLORS = {
            0xFF0000, 0x0000FF, 0x008000, 0xB22222, 0xFF7F50, 0x9ACD32, 0xFF4500, 0x2E8B57,
            0xDAA520, 0xD2691E, 0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F};

    private Formatter() {
    }

    public static MutableComponent format(ChatConfig cfg, ChatLine line) {
        MutableComponent root = Component.empty();
        if (cfg.timestamps != ChatConfig.TimestampMode.OFF) {
            String time = LocalTime.now().format(cfg.timestamps == ChatConfig.TimestampMode.H24 ? H24 : H12);
            root.append(Component.literal("[" + time + "] ").withStyle(ChatFormatting.DARK_GRAY));
        }
        root.append(LegacyText.parse(cfg.prefix));

        if (line.notice() != null) {
            Style noticeStyle = LegacyText.parseStyle(line.redemption() ? cfg.redemptionColor : cfg.noticeColor);
            if (noticeStyle.getColor() == null) noticeStyle = noticeStyle.withColor(line.redemption() ? ChatFormatting.AQUA : ChatFormatting.GOLD);
            root.append(Component.literal((line.redemption() ? "◆ " : "✦ ") + line.notice()).setStyle(noticeStyle));
            if (line.segments().isEmpty()) return root;
            root.append(Component.literal(" "));
        }

        Style nameStyle = nameStyle(cfg, line);
        if (cfg.showBadges) appendBadges(root, line.badges());
        if (line.displayName() != null && !line.displayName().isEmpty()) {
            String shown = line.displayName();
            Component hover = Component.literal(shown).setStyle(nameStyle)
                    .append(Component.literal("\ntwitch.tv/" + line.login()).withStyle(ChatFormatting.GRAY));
            root.append(Component.literal(shown).setStyle(nameStyle.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
            ChatLine.Reply reply = line.reply();
            boolean compact = reply != null && (cfg.replyStyle == ChatConfig.ReplyStyle.COMPACT
                    || (cfg.replyStyle == ChatConfig.ReplyStyle.CONTEXT && reply.body().isEmpty()));
            if (compact) {
                root.append(Component.literal(" ↩ " + reply.displayName()).setStyle(Style.EMPTY
                        .withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, replyHover(reply)))));
            }
            root.append(line.action() ? Component.literal(" ") : LegacyText.parse(cfg.separator));
        }

        Style bodyStyle;
        if (line.action()) {
            bodyStyle = Style.EMPTY.withColor(nameStyle.getColor()).withItalic(cfg.actionItalic);
        } else {
            bodyStyle = LegacyText.parseStyle(line.highlight() ? cfg.highlightColor : cfg.messageColor);
        }
        root.append(body(cfg, line.segments(), bodyStyle));
        return root;
    }

    public static MutableComponent body(ChatConfig cfg, List<Segment> segments, Style style) {
        MutableComponent body = Component.empty().setStyle(style);
        for (Segment s : segments) {
            if (s instanceof Segment.Text t) body.append(Component.literal(t.text()));
            else if (s instanceof Segment.Emote e) body.append(Placeholders.component(e, cfg));
        }
        return body;
    }

    private static Component replyHover(ChatLine.Reply reply) {
        return Component.literal("Replying to ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(reply.displayName()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(reply.body().isEmpty() ? "" : "\n" + reply.body()).withStyle(ChatFormatting.GRAY));
    }

    /**
     * The grey line shown above a reply: "↱ name: original message", indented to line up with the
     * names under the prefix and cut with "…" so it never wraps onto a second line.
     */
    public static MutableComponent replyContext(ChatConfig cfg, ChatLine line, int maxWidth) {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        ChatLine.Reply reply = line.reply();
        MutableComponent lead = Component.empty();
        if (cfg.timestamps != ChatConfig.TimestampMode.OFF) lead.append(Component.literal("[00:00] "));
        lead.append(LegacyText.parse(cfg.prefix));
        int indent = font.width(lead);
        int space = Math.max(1, font.width(" "));
        String pad = " ".repeat(Math.max(0, indent / space));
        String head = pad + "↱ " + reply.displayName() + ": ";
        Style grey = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, replyHover(reply)));
        MutableComponent out = Component.literal(head).setStyle(grey);
        MutableComponent body = Component.empty().setStyle(grey);
        out.append(body);

        // Fill the line with the original's text and emotes, then cut with "…" so it never wraps.
        int limit = maxWidth - 2;
        int ellipsis = font.width("…");
        int used = font.width(head);
        List<Segment> segs = reply.segments();
        for (int i = 0; i < segs.size(); i++) {
            boolean last = i == segs.size() - 1;
            Segment s = segs.get(i);
            if (s instanceof Segment.Text t) {
                String text = t.text().replace('\n', ' ');
                int w = font.width(text);
                // Leave room for "…" unless this is the last piece.
                if (used + w <= (last ? limit : limit - ellipsis)) {
                    body.append(Component.literal(text));
                    used += w;
                    continue;
                }
                String cut = font.plainSubstrByWidth(text, Math.max(0, limit - used - ellipsis));
                body.append(Component.literal(cut + "…"));
                break;
            } else if (s instanceof Segment.Emote e) {
                Component c = Placeholders.component(e, cfg);
                int w = font.width(c);
                if (used + w <= (last ? limit : limit - ellipsis)) {
                    body.append(c);
                    used += w;
                    continue;
                }
                body.append(Component.literal("…"));
                break;
            }
        }
        return out;
    }

    /** The same status-line style used for "Connected to #channel" etc. */
    public static MutableComponent status(ChatConfig cfg, String text) {
        return LegacyText.parse(cfg.prefix).append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private static Style nameStyle(ChatConfig cfg, ChatLine line) {
        if (cfg.nameColorMode == ChatConfig.NameColorMode.FIXED) {
            Style s = LegacyText.parseStyle(cfg.fixedNameColor);
            return s.getColor() == null ? s.withColor(ChatFormatting.LIGHT_PURPLE) : s;
        }
        int rgb = parseHex(line.color());
        if (rgb < 0) rgb = DEFAULT_COLORS[Math.floorMod(line.login().hashCode(), DEFAULT_COLORS.length)];
        if (cfg.brightenDarkNames) rgb = brighten(rgb);
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    private static int parseHex(String s) {
        if (s == null) return -1;
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.length() != 6) return -1;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Lightens colours that would be hard to read on the dark chat background. */
    static int brighten(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (lum >= 0.4) return rgb;
        double t = Math.min(0.6, (0.4 - lum) * 1.5 + 0.2);
        r += (1 - r) * t;
        g += (1 - g) * t;
        b += (1 - b) * t;
        return ((int) Math.round(r * 255) << 16) | ((int) Math.round(g * 255) << 8) | (int) Math.round(b * 255);
    }

    private static void appendBadges(MutableComponent root, List<String> badges) {
        for (String raw : badges) {
            int slash = raw.indexOf('/');
            String name = slash > 0 ? raw.substring(0, slash) : raw;
            switch (name) {
                case "broadcaster" -> badge(root, "★", 0xE91916, "Broadcaster");
                case "moderator" -> badge(root, "⚔", 0x00AD03, "Moderator");
                case "vip" -> badge(root, "♦", 0xE005B9, "VIP");
                case "subscriber" -> badge(root, "✦", 0x9146FF, "Subscriber");
                case "founder" -> badge(root, "✦", 0xF5A623, "Founder");
                case "staff", "admin", "global_mod" -> badge(root, "⚙", 0xAAAAAA, "Twitch staff");
                default -> {
                }
            }
        }
    }

    private static void badge(MutableComponent root, String symbol, int color, String label) {
        root.append(Component.literal(symbol).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(label)))));
        root.append(Component.literal(" "));
    }
}
