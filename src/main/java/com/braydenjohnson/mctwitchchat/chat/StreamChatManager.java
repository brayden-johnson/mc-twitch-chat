package com.braydenjohnson.mctwitchchat.chat;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.emote.EmoteRegistry;
import com.braydenjohnson.mctwitchchat.irc.IrcMessage;
import com.braydenjohnson.mctwitchchat.irc.TwitchIrcClient;
import com.braydenjohnson.mctwitchchat.util.LegacyText;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Connects Twitch chat to the Minecraft chat window. */
public final class StreamChatManager {
    private static final StreamChatManager INSTANCE = new StreamChatManager();

    private TwitchIrcClient client;
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

    // ------------------------------------------------------------ connection

    /** Starts, stops or switches channel to match the saved settings. */
    public synchronized void applyConfig() {
        ChatConfig cfg = ChatConfig.get();
        EmoteRegistry.ensureGlobals();
        if (!cfg.enabled || cfg.channel.isEmpty()) {
            stopClient();
            status = cfg.channel.isEmpty() ? "No channel set" : "Turned off";
            return;
        }
        if (client != null && client.channel().equals(cfg.channel)) return;
        restart();
    }

    public synchronized void restart() {
        stopClient();
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
        boolean highlight = isHighlight(text, login, cfg);
        ChatLine line = new ChatLine(m.tag("id"), login, display, m.tag("color"), badges(m), segments, action, highlight, null);
        enqueue(line);
    }

    private void onUserNotice(IrcMessage m, ChatConfig cfg) {
        if (paused || !cfg.showNotices) return;
        String login = m.login();
        if (login.isEmpty()) login = m.tag("login");
        String system = m.tag("system-msg");
        String type = m.tag("msg-id");
        if (system.isEmpty() && "announcement".equals(type)) system = "Announcement";
        String text = m.trailing == null ? "" : m.trailing;
        List<Segment> segments = MessageParser.parse(text, m.tag("emotes"), m.tag("gifs"), cfg);
        String display = text.isEmpty() ? "" : (m.tag("display-name").isEmpty() ? login : m.tag("display-name"));
        ChatLine line = new ChatLine(m.tag("id"), login, display, m.tag("color"), badges(m), segments, false, false,
                system.isEmpty() ? type : system);
        enqueue(line);
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
