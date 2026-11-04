package com.braydenjohnson.mctwitchchat.irc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One parsed line of Twitch IRC (IRCv3 tags included). */
public final class IrcMessage {
    public final Map<String, String> tags;
    public final String prefix;
    public final String command;
    public final List<String> params;
    public final String trailing;

    private IrcMessage(Map<String, String> tags, String prefix, String command, List<String> params, String trailing) {
        this.tags = tags;
        this.prefix = prefix;
        this.command = command;
        this.params = params;
        this.trailing = trailing;
    }

    /** Tag value, or "" when missing. */
    public String tag(String key) {
        String v = tags.get(key);
        return v == null ? "" : v;
    }

    /** The sender's login from the prefix (nick!user@host). */
    public String login() {
        if (prefix == null) return "";
        int i = prefix.indexOf('!');
        return (i > 0 ? prefix.substring(0, i) : prefix).toLowerCase(Locale.ROOT);
    }

    public static IrcMessage parse(String line) {
        if (line == null || line.isEmpty()) return null;
        int len = line.length();
        int pos = 0;
        Map<String, String> tags = Collections.emptyMap();
        if (line.charAt(0) == '@') {
            int sp = line.indexOf(' ');
            if (sp < 0) return null;
            tags = parseTags(line.substring(1, sp));
            pos = sp + 1;
        }
        while (pos < len && line.charAt(pos) == ' ') pos++;
        String prefix = null;
        if (pos < len && line.charAt(pos) == ':') {
            int sp = line.indexOf(' ', pos);
            if (sp < 0) return null;
            prefix = line.substring(pos + 1, sp);
            pos = sp + 1;
        }
        while (pos < len && line.charAt(pos) == ' ') pos++;
        if (pos >= len) return null;
        int sp = line.indexOf(' ', pos);
        String command = sp < 0 ? line.substring(pos) : line.substring(pos, sp);
        pos = sp < 0 ? len : sp + 1;
        List<String> params = new ArrayList<>();
        String trailing = null;
        while (pos < len) {
            if (line.charAt(pos) == ':') {
                trailing = line.substring(pos + 1);
                break;
            }
            int next = line.indexOf(' ', pos);
            if (next < 0) {
                params.add(line.substring(pos));
                break;
            }
            if (next > pos) params.add(line.substring(pos, next));
            pos = next + 1;
        }
        return new IrcMessage(tags, prefix, command.toUpperCase(Locale.ROOT), params, trailing);
    }

    private static Map<String, String> parseTags(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) out.put(part, "");
            else out.put(part.substring(0, eq), unescape(part.substring(eq + 1)));
        }
        return out;
    }

    private static String unescape(String v) {
        if (v.indexOf('\\') < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= v.length()) break;
            char n = v.charAt(i);
            switch (n) {
                case 's' -> sb.append(' ');
                case ':' -> sb.append(';');
                case 'r' -> sb.append('\r');
                case 'n' -> sb.append('\n');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
