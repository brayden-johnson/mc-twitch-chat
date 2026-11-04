package com.braydenjohnson.mctwitchchat.config;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Every setting of the mod. Saved as config/mctwitchchat.json, so it survives game restarts
 * and can also be edited by hand.
 */
public final class ChatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile ChatConfig current = new ChatConfig();

    public enum NameColorMode { TWITCH, FIXED }

    public enum TimestampMode { OFF, H24, H12 }

    // --- Connection
    public boolean enabled = true;
    public String channel = "slayyyden";
    public boolean statusMessages = true;

    // --- Look
    /** Text in front of every stream message. Supports &-colour codes (&d, &l, &r ...) and &#RRGGBB. */
    public String prefix = "&d[Stream]&r ";
    public String separator = "&7: ";
    /** &-code or #RRGGBB for the message text. */
    public String messageColor = "&f";
    public NameColorMode nameColorMode = NameColorMode.TWITCH;
    public String fixedNameColor = "#C9A0FF";
    public boolean brightenDarkNames = true;
    public boolean showBadges = true;
    public TimestampMode timestamps = TimestampMode.OFF;
    public boolean indicatorBar = true;
    public String indicatorColor = "#9146FF";
    public boolean showNotices = true;
    public boolean actionItalic = true;

    // --- Emotes
    public boolean twitchEmotes = true;
    public boolean ffzEmotes = true;
    public boolean bttvEmotes = true;
    public boolean sevenTvEmotes = true;
    public boolean emoji = true;
    public boolean animated = true;
    /** Emote height in GUI pixels. Chat lines are 9 px tall. */
    public int emoteSize = 9;
    /** Twitch's GIPHY GIFs (the Tier 2 / Tier 3 sub GIF keyboard). */
    public boolean twitchGifs = true;
    /** How many chat lines tall a GIF is. 1 = same size as an emote. */
    public int gifLines = 4;
    /** How long a message may wait for its emote images before it is shown anyway. */
    public int maxWaitMs = 1500;

    // --- Filters
    public boolean hideCommands = false;
    /** Comma separated Twitch logins whose messages are never shown. */
    public String ignoredUsers = "";
    public boolean highlightMentions = true;
    /** Comma separated extra words that highlight a message. */
    public String highlightWords = "";
    public String highlightColor = "&e";
    public boolean pingSound = true;
    public boolean respectModDeletes = true;

    public static ChatConfig get() {
        return current;
    }

    public static void set(ChatConfig config) {
        config.sanitize();
        current = config;
    }

    public ChatConfig copy() {
        ChatConfig c = GSON.fromJson(GSON.toJson(this), ChatConfig.class);
        c.sanitize();
        return c;
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(MCTwitchChat.MODID + ".json");
    }

    public static void load() {
        Path file = file();
        if (Files.exists(file)) {
            try {
                ChatConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ChatConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    current = loaded;
                    save();
                    return;
                }
            } catch (Exception e) {
                MCTwitchChat.LOG.warn("Could not read {}, keeping a backup and using defaults", file, e);
                try {
                    Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        current = new ChatConfig();
        save();
    }

    public static void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(current), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MCTwitchChat.LOG.error("Could not save {}", file, e);
        }
    }

    /** Fixes nulls and out-of-range values from hand edits or older files. */
    public void sanitize() {
        channel = normalizeChannel(channel);
        prefix = nz(prefix);
        separator = nz(separator);
        messageColor = nz(messageColor);
        fixedNameColor = nz(fixedNameColor);
        indicatorColor = nz(indicatorColor);
        ignoredUsers = nz(ignoredUsers);
        highlightWords = nz(highlightWords);
        highlightColor = nz(highlightColor);
        if (nameColorMode == null) nameColorMode = NameColorMode.TWITCH;
        if (timestamps == null) timestamps = TimestampMode.OFF;
        emoteSize = Math.max(6, Math.min(18, emoteSize));
        maxWaitMs = Math.max(0, Math.min(5000, maxWaitMs));
        gifLines = Math.max(1, Math.min(8, gifLines));
    }

    /** Accepts "slayyyden", "#slayyyden", "@slayyyden" or a twitch.tv link. */
    public static String normalizeChannel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int slash = s.lastIndexOf("twitch.tv/");
        if (slash >= 0) s = s.substring(slash + "twitch.tv/".length());
        int q = s.indexOf('/');
        if (q >= 0) s = s.substring(0, q);
        q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        s = s.replace("#", "").replace("@", "");
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') out.append(c);
        }
        return out.length() > 25 ? out.substring(0, 25) : out.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
