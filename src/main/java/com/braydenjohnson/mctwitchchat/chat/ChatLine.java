package com.braydenjohnson.mctwitchchat.chat;

import java.util.List;

/**
 * One message ready to be formatted.
 *
 * @param color      Twitch name colour like "#1E90FF", or "" when the user never picked one
 * @param notice     system text for subs, raids, announcements and redemptions, or null for a normal message
 * @param redemption true when the notice is a channel point redemption
 * @param reply      what this message replies to, or null
 */
public record ChatLine(String id, String login, String displayName, String color, List<String> badges,
                       List<Segment> segments, boolean action, boolean highlight, String notice, boolean redemption,
                       Reply reply) {

    /** The message being replied to, from Twitch's reply-parent-* tags. */
    public record Reply(String login, String displayName, String body) {
    }

    public ChatLine(String id, String login, String displayName, String color, List<String> badges,
                    List<Segment> segments, boolean action, boolean highlight, String notice, boolean redemption) {
        this(id, login, displayName, color, badges, segments, action, highlight, notice, redemption, null);
    }
}
