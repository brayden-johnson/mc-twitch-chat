package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.config.ChatConfig;

import java.util.List;

/**
 * An emote that can appear in chat. Each URL list is tried in order until one decodes.
 *
 * @param width  natural width at 1x, used for the aspect ratio before the image has loaded
 * @param height natural height at 1x
 */
public record EmoteDef(String name, String provider, List<String> animatedUrls, List<String> staticUrls, int width, int height) {

    public static EmoteDef twitch(String id, String name) {
        String base = "https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/";
        // "default" serves the animated GIF for animated emotes and a PNG otherwise
        return new EmoteDef(name, "Twitch emote",
                List.of(base + "default/dark/2.0", base + "static/dark/2.0"),
                List.of(base + "static/dark/2.0", base + "default/dark/2.0"),
                28, 28);
    }

    public List<String> urls(ChatConfig cfg) {
        return cfg.animated ? animatedUrls : staticUrls;
    }

    public EmoteImage image(ChatConfig cfg) {
        return ImageCache.get(urls(cfg));
    }
}
