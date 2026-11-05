package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmoteDef;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;
import com.braydenjohnson.mctwitchchat.irc.IrcMessage;
import com.braydenjohnson.mctwitchchat.irc.TwitchIrcClient;
import com.braydenjohnson.mctwitchchat.twitch.EventSubClient;
import com.braydenjohnson.mctwitchchat.twitch.TwitchAuth;
import com.braydenjohnson.mctwitchchat.util.LegacyText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Connects Twitch chat to the Minecraft chat window. */
public final class StreamChatManager {
    private static final StreamChatManager INSTANCE = new StreamChatManager();

    /** Automatic rewards whose message already shows up in IRC chat. */
    private static final Set<String> AUTO_REWARDS_IN_CHAT = Set.of(
            "send_highlighted_message", "single_message_bypass_sub_mode", "message_effect", "gigantify_an_emote");
    private static final Map<String, String> AUTO_REWARD_TITLES = Map.of(
            "random_sub_emote_unlock", "Unlock a Random Sub Emote",
            "chosen_sub_emote_unlock", "Unlock a Sub Emote",
            "chosen_modified_sub_emote_unlock", "Modify a Single Emote",
            "celebration", "On-Screen Celebration");

    private TwitchIrcClient client;
    private EventSubClient eventSub;
    private String eventSubUser = "";
    private volatile String rewardsStatus = "Not logged in";

    private static final class GiftBomb {
        final String gifter;
        int remaining;
        final List<String> recipients = new ArrayList<>();

        GiftBomb(String gifter, int count) {
            this.gifter = gifter;
            this.remaining = count;
        }
    }

    private final Map<String, GiftBomb> bombs = new ConcurrentHashMap<>();
    private volatile String status = "Not connected";
    private volatile boolean paused;
    private final Object chainLock = new Object();
    private CompletableFuture<Void> postChain = CompletableFuture.completedFuture(null);

    // Render thread only: lets mod deletions and timeouts remove messages from Minecraft chat.
    private final Map<String, List<MessageSignature>> byId = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<MessageSignature>> eldest) {
            return size() > 1000;
        }
    };
    private final Map<String, Deque<MessageSignature>> byUser = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<MessageSignature>> eldest) {
            return size() > 500;
        }
    };

    private StreamChatManager() {
    }

    public static StreamChatManager get() {
        return INSTANCE;
    }

    public String status() {
        return paused ? status + " (paused)" : status;
    }

    public boolean isPaused() {
        return paused;
    }

    public String rewardsStatus() {
        return rewardsStatus;
    }

    // ------------------------------------------------------------ connection

    /** Starts, stops or switches channel to match the saved settings. */
    public synchronized void applyConfig() {
        ChatConfig cfg = ChatConfig.get();
        EmoteRegistry.ensureGlobals();
        syncRewards();
        if (!cfg.enabled || cfg.channel.isEmpty()) {
            stopClient();
            status = cfg.channel.isEmpty() ? "No channel set" : "Turned off";
            return;
        }
        if (client != null && client.channel().equals(cfg.channel)) return;
        restart();
    }

    /** Starts or stops the channel point listener to match the login and settings. */
    public synchronized void syncRewards() {
        ChatConfig cfg = ChatConfig.get();
        TwitchAuth.Tokens t = TwitchAuth.tokens();
        String reason = null;
        if (t == null || t.userId.isEmpty()) reason = "Not logged in";
        else if (!cfg.showRedemptions) reason = "Logged in as " + t.login + " · redemptions turned off";
        else if (!cfg.enabled) reason = "Logged in as " + t.login + " · stream chat is off";
        else if (!t.login.equalsIgnoreCase(cfg.channel)) {
            // Twitch only lets the channel owner read redemptions.
            reason = "Logged in as " + t.login + ", not #" + cfg.channel + ": no redemptions";
        }
        if (reason != null) {
            stopRewards();
            rewardsStatus = reason;
            return;
        }
        if (eventSub != null && eventSubUser.equals(t.userId)) return;
        stopRewards();
        eventSubUser = t.userId;
        rewardsStatus = "Logged in as " + t.login + " · connecting…";
        eventSub = new EventSubClient(t.userId, this::onRedemption, s -> rewardsStatus = s.replace("Rewards: ", "Logged in as " + t.login + " · "));
        eventSub.start();
    }

    private void stopRewards() {
        if (eventSub != null) {
            eventSub.stop();
            eventSub = null;
        }
        eventSubUser = "";
    }

    public synchronized void restart() {
        stopClient();
        syncRewards();
        ChatConfig cfg = ChatConfig.get();
        if (cfg.channel.isEmpty()) {
            status = "No channel set";
            return;
        }
        client = new TwitchIrcClient(cfg.channel, this::onIrc, this::onStatus);
        client.start();
    }

    public synchronized void disconnect() {
        stopClient();
        status = "Disconnected";
        postStatus("Disconnected from Twitch chat");
    }

    public void connectTo(String channel) {
        ChatConfig cfg = ChatConfig.get().copy();
        cfg.channel = ChatConfig.normalizeChannel(channel);
        cfg.enabled = true;
        ChatConfig.set(cfg);
        ChatConfig.save();
        restart();
    }

    public void togglePause() {
        paused = !paused;
        postStatus(paused ? "Stream chat paused" : "Stream chat resumed");
    }

    private void stopClient() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    private void onStatus(String text, boolean announce) {
        status = text;
        if (announce) postStatus(text);
    }

    // ------------------------------------------------------------ incoming

    private void onIrc(IrcMessage m) {
        ChatConfig cfg = ChatConfig.get();
        switch (m.command) {
            case "PRIVMSG" -> onPrivmsg(m, cfg);
            case "USERNOTICE" -> onUserNotice(m, cfg);
            case "ROOMSTATE" -> EmoteRegistry.loadChannel(m.tag("room-id"));
            case "CLEARMSG" -> {
                if (!cfg.respectModDeletes) return;
                String target = m.tag("target-msg-id");
                Minecraft.getInstance().execute(() -> {
                    List<MessageSignature> sigs = byId.remove(target);
                    if (sigs != null) sigs.forEach(sig -> chat().deleteMessage(sig));
                });
            }
            case "CLEARCHAT" -> {
                if (!cfg.respectModDeletes || m.trailing == null || m.trailing.isEmpty()) return;
                String user = m.trailing.toLowerCase(Locale.ROOT);
                Minecraft.getInstance().execute(() -> {
                    Deque<MessageSignature> sigs = byUser.remove(user);
                    if (sigs != null) sigs.forEach(sig -> chat().deleteMessage(sig));
                });
            }
            case "NOTICE" -> {
                if (m.trailing != null && cfg.statusMessages) postStatus(m.trailing);
            }
            default -> {
            }
        }
    }

    private void onPrivmsg(IrcMessage m, ChatConfig cfg) {
        if (paused) return;
        String login = m.login();
        if (ignored(cfg).contains(login)) return;
        String text = m.trailing == null ? "" : m.trailing;
        boolean action = false;
        if (text.startsWith("\u0001ACTION ")) {
            action = true;
            text = text.substring(8);
            if (text.endsWith("\u0001")) text = text.substring(0, text.length() - 1);
        }
        if (cfg.hideCommands && text.startsWith("!")) return;

        List<Segment> segments = MessageParser.parse(text, m.tag("emotes"), m.tag("gifs"), cfg);
        String display = m.tag("display-name");
        if (display.isEmpty()) display = login;

        if (!m.tag("custom-reward-id").isEmpty()) {
            // A reward that asks for text. With the Twitch login the redemption line already
            // includes this text (and the reward's name), so skip the duplicate.
            EventSubClient es = eventSub;
            if (cfg.showRedemptions && es != null && es.connected()) return;
            if (cfg.showRedemptions) {
                enqueue(new ChatLine(m.tag("id"), login, "", m.tag("color"), List.of(), segments, false, false,
                        display + " redeemed a reward:", true));
                return;
            }
        }
        boolean highlight = isHighlight(text, login, cfg) || "highlighted-message".equals(m.tag("msg-id"));
        ChatLine line = new ChatLine(m.tag("id"), login, display, m.tag("color"), badges(m), segments, action, highlight, null, false);
        enqueue(line);
    }

    private void onUserNotice(IrcMessage m, ChatConfig cfg) {
        if (paused || !cfg.showNotices) return;
        // USERNOTICE comes from tmi.twitch.tv; the user is in the "login" tag.
        String login = m.tag("login");
        if (login.isEmpty()) login = m.login();
        String name = m.tag("display-name").isEmpty() ? login : m.tag("display-name");
        String type = m.tag("msg-id");
        String recipient = m.tag("msg-param-recipient-display-name");
        if (recipient.isEmpty()) recipient = m.tag("msg-param-recipient-user-name");

        if (cfg.groupGiftBombs) {
            if ("submysterygift".equals(type)) {
                int count = parseInt(m.tag("msg-param-mass-gift-count"));
                if (count > 1) {
                    GiftBomb bomb = new GiftBomb(name, count);
                    bombs.put(login, bomb);
                    String key = login;
                    // If Twitch doesn't send every single gift, still show who got the ones that came.
                    CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> flushBomb(key, bomb));
                }
            } else if ("subgift".equals(type) && !recipient.isEmpty()) {
                GiftBomb bomb = bombs.get(login);
                if (bomb != null) {
                    boolean done;
                    synchronized (bomb) {
                        bomb.recipients.add(recipient);
                        bomb.remaining--;
                        done = bomb.remaining <= 0;
                    }
                    if (done) flushBomb(login, bomb);
                    return;
                }
            }
        }

        // Twitch's system-msg already names the gifter and recipient ("X gifted a Tier 1 sub to Y!").
        // These fallbacks only matter if Twitch ever leaves it out.
        String system = m.tag("system-msg");
        if (system.isEmpty()) {
            system = switch (type) {
                case "subgift", "anonsubgift" -> name + " gifted a sub to " + (recipient.isEmpty() ? "someone" : recipient) + "!";
                case "submysterygift", "anonsubmysterygift" -> name + " is gifting " + m.tag("msg-param-mass-gift-count") + " subs to the community!";
                case "sub" -> name + " subscribed!";
                case "resub" -> name + " resubscribed for " + m.tag("msg-param-cumulative-months") + " months!";
                case "raid" -> m.tag("msg-param-displayName") + " is raiding with " + m.tag("msg-param-viewerCount") + " viewers!";
                case "announcement" -> "Announcement";
                default -> type;
            };
        }
        String text = m.trailing == null ? "" : m.trailing;
        List<Segment> segments = MessageParser.parse(text, m.tag("emotes"), m.tag("gifs"), cfg);
        String display = text.isEmpty() ? "" : name;
        enqueue(new ChatLine(m.tag("id"), login, display, m.tag("color"), badges(m), segments, false, false, system, false));
    }

    private void flushBomb(String login, GiftBomb bomb) {
        if (!bombs.remove(login, bomb)) return;
        List<String> names;
        synchronized (bomb) {
            names = new ArrayList<>(bomb.recipients);
        }
        if (names.isEmpty()) return;
        String list = names.size() > 20
                ? String.join(", ", names.subList(0, 20)) + " and " + (names.size() - 20) + " more"
                : String.join(", ", names);
        enqueue(new ChatLine("", login, "", "", List.of(), List.of(), false, false,
                "Gift subs from " + bomb.gifter + " went to " + list, false));
    }

    // ------------------------------------------------------------ channel points (EventSub)

    private void onRedemption(String type, JsonObject e) {
        ChatConfig cfg = ChatConfig.get();
        if (paused || !cfg.showRedemptions) return;
        String login = str(e, "user_login");
        if (ignored(cfg).contains(login)) return;
        String user = str(e, "user_name").isEmpty() ? login : str(e, "user_name");
        JsonObject reward = e.has("reward") && e.get("reward").isJsonObject() ? e.getAsJsonObject("reward") : new JsonObject();

        String notice;
        List<Segment> segments;
        if (type.endsWith("custom_reward_redemption.add")) {
            String input = str(e, "user_input");
            int cost = num(reward, "cost");
            notice = user + " redeemed " + str(reward, "title") + (cost > 0 ? " (" + cost + ")" : "") + (input.isEmpty() ? "" : ":");
            segments = MessageParser.parse(input, null, null, cfg);
        } else {
            String rewardType = str(reward, "type");
            if (AUTO_REWARDS_IN_CHAT.contains(rewardType) || !cfg.showAutomaticRewards) return;
            int cost = num(reward, "channel_points");
            if (cost == 0) cost = num(reward, "cost");
            String title = AUTO_REWARD_TITLES.getOrDefault(rewardType, pretty(rewardType));
            JsonObject emote = obj(reward, "emote");
            if (emote == null) emote = obj(reward, "unlocked_emote");
            segments = new ArrayList<>();
            if (emote != null && !str(emote, "id").isEmpty()) {
                EmoteDef def = EmoteDef.twitch(str(emote, "id"), str(emote, "name"));
                segments.add(new Segment.Emote(def.name(), def.provider(), def.image(cfg), 28, 28, 0));
                segments.add(new Segment.Text(" " + str(emote, "name")));
            }
            notice = user + " redeemed " + title + (cost > 0 ? " (" + cost + ")" : "") + (segments.isEmpty() ? "" : ":");
        }
        enqueue(new ChatLine(str(e, "id"), login, "", "", List.of(), segments, false, false, notice, true));
    }

    /** A sample redemption, for trying out the look from the settings screen. */
    public void postTestRedemption() {
        JsonObject e = new JsonObject();
        ChatConfig cfg = ChatConfig.get();
        e.addProperty("user_login", cfg.channel);
        e.addProperty("user_name", cfg.channel);
        e.addProperty("user_input", "this is a test ❤️");
        JsonObject reward = new JsonObject();
        reward.addProperty("title", "Hydrate!");
        reward.addProperty("cost", 500);
        e.add("reward", reward);
        boolean wasPaused = paused;
        paused = false;
        onRedemption("channel.channel_points_custom_reward_redemption.add", e);
        paused = wasPaused;
    }

    private static String pretty(String type) {
        if (type.isEmpty()) return "a reward";
        String s = type.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement v = o.get(k);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement v = o == null ? null : o.get(k);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        JsonElement v = o == null ? null : o.get(k);
        try {
            return v != null && v.isJsonPrimitive() ? v.getAsInt() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Waits (briefly) for the message's emote images so widths are right, keeping message order. */
    private void enqueue(ChatLine line) {
        ChatConfig cfg = ChatConfig.get();
        CompletableFuture<?>[] waits = line.segments().stream()
                .filter(s -> s instanceof Segment.Emote)
                .map(s -> ((Segment.Emote) s).image().future)
                .toArray(CompletableFuture[]::new);
        // GIPHY files are big; give them longer so the GIF's real shape is known.
        boolean hasGif = line.segments().stream().anyMatch(s -> s instanceof Segment.Emote e && e.lines() > 0);
        int waitMs = hasGif ? Math.max(cfg.maxWaitMs, 5000) : cfg.maxWaitMs;
        CompletableFuture<Void> ready = CompletableFuture.allOf(waits)
                .completeOnTimeout(null, waitMs, TimeUnit.MILLISECONDS)
                .exceptionally(t -> null);
        synchronized (chainLock) {
            postChain = postChain
                    .thenCompose(v -> ready)
                    .thenRun(() -> Minecraft.getInstance().execute(() -> post(line)))
                    .exceptionally(t -> {
                        MCTwitchChat.LOG.warn("Could not show Twitch message", t);
                        return null;
                    });
        }
    }

    // ------------------------------------------------------------ output (render thread)

    private static ChatComponent chat() {
        return Minecraft.getInstance().gui.getChat();
    }

    private void post(ChatLine line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatConfig cfg = ChatConfig.get();
        GuiMessageTag tag = tag(cfg);
        List<MessageSignature> sigs = new ArrayList<>();
        MessageSignature sig = signature(line.id());
        mc.gui.getChat().addMessage(Formatter.format(cfg, line), sig, tag);
        sigs.add(sig);
        // Blank lines under a GIF give it room; the GIF is drawn from its line down over them.
        int extra = MessageParser.extraLines(line.segments());
        for (int i = 0; i < extra; i++) {
            MessageSignature spacer = signature(line.id() + "#" + i);
            mc.gui.getChat().addMessage(Component.literal(" "), spacer, tag);
            sigs.add(spacer);
        }
        if (!line.id().isEmpty()) byId.put(line.id(), sigs);
        if (!line.login().isEmpty()) {
            Deque<MessageSignature> d = byUser.computeIfAbsent(line.login(), k -> new ArrayDeque<>());
            sigs.forEach(d::addLast);
            while (d.size() > 40) d.removeFirst();
        }
        if (line.highlight() && cfg.pingSound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f));
        }
    }

    public void postStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null) return;
            ChatConfig cfg = ChatConfig.get();
            if (!cfg.statusMessages) return;
            mc.gui.getChat().addMessage(Formatter.status(cfg, text), null, tag(cfg));
        });
    }

    private static GuiMessageTag tag(ChatConfig cfg) {
        if (!cfg.indicatorBar) return null;
        Style s = LegacyText.parseStyle(cfg.indicatorColor);
        int color = s.getColor() == null ? 0x9146FF : s.getColor().getValue();
        return new GuiMessageTag(color, null, Component.literal("Twitch chat · #" + cfg.channel), "Twitch");
    }

    private static MessageSignature signature(String id) {
        byte[] bytes = new byte[256];
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < bytes.length; i++) bytes[i] = h[i % h.length];
        } catch (Exception e) {
            Arrays.fill(bytes, (byte) (id.hashCode() ^ System.nanoTime()));
        }
        return new MessageSignature(bytes);
    }

    // ------------------------------------------------------------ helpers

    private static List<String> badges(IrcMessage m) {
        String raw = m.tag("badges");
        if (raw.isEmpty()) return List.of();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    private static Set<String> ignored(ChatConfig cfg) {
        return Arrays.stream(cfg.ignoredUsers.split(","))
                .map(s -> ChatConfig.normalizeChannel(s))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static boolean isHighlight(String text, String login, ChatConfig cfg) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (cfg.highlightMentions && !cfg.channel.isEmpty() && !login.equals(cfg.channel) && lower.contains(cfg.channel)) {
            return true;
        }
        for (String w : cfg.highlightWords.split(",")) {
            String word = w.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && lower.contains(word)) return true;
        }
        return false;
    }
}
