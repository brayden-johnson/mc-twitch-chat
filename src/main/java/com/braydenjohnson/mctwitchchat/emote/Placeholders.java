package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.chat.Segment;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How emotes travel through Minecraft's chat: each emote becomes one private-use character
 * in the "mctwitchchat:emote" font. That font only contains blank spaces whose width matches
 * the emote, so chat wrapping and fading keep working. The style's insertion holds the emote
 * name (shift-click inserts it), and {@link EmoteRenderer} draws the image into the gap.
 */
public final class Placeholders {
    public static final int BASE = 0xE000;
    public static final int MAX_WIDTH = 128;

    private static final Map<String, EmoteImage> BY_NAME = new ConcurrentHashMap<>();
    static volatile boolean used;

    private Placeholders() {
    }

    public static EmoteImage get(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    public static boolean isPlaceholder(int codePoint, Style style) {
        return codePoint > BASE && codePoint <= BASE + MAX_WIDTH && MCTwitchChat.EMOTE_FONT.equals(style.getFont());
    }

    /** The chat component for one emote, or its plain name if the image could not load. */
    public static Component component(Segment.Emote e, ChatConfig cfg) {
        EmoteImage img = e.image();
        if (img.failed()) return Component.literal(e.name());
        float aspect;
        if (img.hasSize()) aspect = img.aspect();
        else aspect = e.heightHint() > 0 ? (float) e.widthHint() / e.heightHint() : 1f;
        int width = Math.max(1, Math.min(MAX_WIDTH, Math.round(cfg.emoteSize * aspect)));
        BY_NAME.put(e.name(), img);
        used = true;
        Component hover = Component.literal(e.name()).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("\n" + e.provider()).withStyle(ChatFormatting.GRAY));
        Style style = Style.EMPTY
                .withFont(MCTwitchChat.EMOTE_FONT)
                .withInsertion(e.name())
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withBold(false)
                .withItalic(false)
                .withObfuscated(false);
        return Component.literal(new String(Character.toChars(BASE + width))).setStyle(style);
    }
}
