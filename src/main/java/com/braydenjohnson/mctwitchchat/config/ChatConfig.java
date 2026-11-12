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

    // --- Rewards
    /** Show channel point redemptions (needs a Twitch login on the Rewards tab). */
    public boolean showRedemptions = true;
    /** Also show automatic rewards like "Unlock a Random Sub Emote" and celebrations. */
    public boolean showAutomaticRewards = true;
    /** Client ID of your own Twitch app (Client Type: Public). Not a secret. */
    public String twitchClientId = "";
    public String redemptionColor = "&b";
    public String noticeColor = "&6";
    /** Show "X is gifting 5 subs" once, then one line with every recipient. */
    public boolean groupGiftBombs = true;

    // --- Sounds
    public boolean chatSound = false;
    public String chatSoundId = "minecraft:block.note_block.bell";
    public int chatSoundVolume = 60;
    /** 100 = normal pitch. */
    public int chatSoundPitch = 100;
    /**
     * Rolling quiet window in seconds: the chat sound only plays if chat was silent this long.
     * Every message restarts the window, so a busy burst makes one sound. 0 = every message.
     */
    public int chatSoundWindow = 5;
    /** Don't play the chat sound for your own messages (and don't let them restart the window). */
    public boolean chatSoundSkipOwn = true;
    /** Mentions and highlight words (the "ping"). The on/off switch is pingSound. */
    public String pingSoundId = "minecraft:entity.experience_orb.pickup";
    public int pingSoundVolume = 100;
    public int pingSoundPitch = 140;
    public boolean redemptionSound = false;
    public String redemptionSoundId = "minecraft:block.amethyst_block.chime";
    public int redemptionSoundVolume = 80;
    public int redemptionSoundPitch = 100;
    /** Same rolling window, separate timer, for redemptions. 0 = every redemption. */
    public int redemptionSoundWindow = 0;

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
        twitchClientId = nz(twitchClientId).trim();
        redemptionColor = nz(redemptionColor);
        noticeColor = nz(noticeColor);
        if (nameColorMode == null) nameColorMode = NameColorMode.TWITCH;
        if (timestamps == null) timestamps = TimestampMode.OFF;
        emoteSize = Math.max(6, Math.min(18, emoteSize));
        maxWaitMs = Math.max(0, Math.min(5000, maxWaitMs));
        gifLines = Math.max(1, Math.min(8, gifLines));
        chatSoundId = nz(chatSoundId).trim();
        if (chatSoundId.isEmpty()) chatSoundId = "minecraft:block.note_block.bell";
        redemptionSoundId = nz(redemptionSoundId).trim();
        if (redemptionSoundId.isEmpty()) redemptionSoundId = "minecraft:block.amethyst_block.chime";
        pingSoundId = nz(pingSoundId).trim();
        if (pingSoundId.isEmpty()) pingSoundId = "minecraft:entity.experience_orb.pickup";
        pingSoundVolume = Math.max(0, Math.min(100, pingSoundVolume));
        pingSoundPitch = Math.max(50, Math.min(200, pingSoundPitch));
        chatSoundVolume = Math.max(0, Math.min(100, chatSoundVolume));
        redemptionSoundVolume = Math.max(0, Math.min(100, redemptionSoundVolume));
        chatSoundPitch = Math.max(50, Math.min(200, chatSoundPitch));
        redemptionSoundPitch = Math.max(50, Math.min(200, redemptionSoundPitch));
        chatSoundWindow = Math.max(0, Math.min(120, chatSoundWindow));
        redemptionSoundWindow = Math.max(0, Math.min(120, redemptionSoundWindow));
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
