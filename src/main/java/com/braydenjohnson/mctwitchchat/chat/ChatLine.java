package com.braydenjohnson.mctwitchchat.chat;

import java.util.List;

/**
 * One message ready to be formatted.
 *
 * @param color  Twitch name colour like "#1E90FF", or "" when the user never picked one
 * @param notice system text for subs, raids and announcements, or null for a normal message
 */
public record ChatLine(String id, String login, String displayName, String color, List<String> badges,
                       List<Segment> segments, boolean action, boolean highlight, String notice) {
}
