package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.emote.EmoteImage;

/** A piece of a chat message: plain text or one emote/emoji. */
public sealed interface Segment permits Segment.Text, Segment.Emote {
    record Text(String text) implements Segment {
    }

    record Emote(String name, String provider, EmoteImage image, int widthHint, int heightHint) implements Segment {
    }
}
