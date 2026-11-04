package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.emote.EmoteImage;

/** A piece of a chat message: plain text or one emote/emoji. */
public sealed interface Segment permits Segment.Text, Segment.Emote {
    record Text(String text) implements Segment {
    }

    /**
     * @param lines 0 for an inline emote; for Twitch GIFs, how many chat lines tall to draw it
     */
    record Emote(String name, String provider, EmoteImage image, int widthHint, int heightHint, int lines) implements Segment {
    }
}
