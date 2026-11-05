package com.braydenjohnson.mctwitchchat.twitch;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.util.Http;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Twitch EventSub over WebSocket, used for channel point redemptions (they never reach IRC
 * unless the reward asks for text). Handles keepalive timeouts, Twitch's reconnect message,
 * token refresh and resubscribing after a dropped connection.
 */
public final class EventSubClient {
    private static final String URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String CUSTOM = "channel.channel_points_custom_reward_redemption.add";
    private static final String AUTOMATIC = "channel.channel_points_automatic_reward_redemption.add";

    private final String broadcasterId;
    private final BiConsumer<String, JsonObject> onEvent;
    private final Consumer<String> onStatus;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MCTwitchChat-EventSub");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;
    private volatile WebSocket socket;
    private volatile long lastMessage = System.currentTimeMillis();
    private volatile int keepaliveSeconds = 30;
    private volatile int backoff = 1;
    private volatile boolean connected;

    public EventSubClient(String broadcasterId, BiConsumer<String, JsonObject> onEvent, Consumer<String> onStatus) {
        this.broadcasterId = broadcasterId;
        this.onEvent = onEvent;
        this.onStatus = onStatus;
    }

    public boolean connected() {
        return connected;
    }

    public void start() {
        timer.scheduleAtFixedRate(this::watchdog, 5, 5, TimeUnit.SECONDS);
        timer.execute(() -> open(URL, false));
    }

    public void stop() {
        running = false;
        connected = false;
        WebSocket s = socket;
        if (s != null) s.abort();
        timer.shutdownNow();
    }

    private void watchdog() {
        if (!running || socket == null) return;
        long silentMs = System.currentTimeMillis() - lastMessage;
        if (silentMs > (keepaliveSeconds + 10) * 1000L) {
            MCTwitchChat.LOG.info("EventSub keepalive missed; reconnecting");
            WebSocket s = socket;
            socket = null;
            if (s != null) s.abort();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        connected = false;
        int wait = backoff;
        backoff = Math.min(backoff * 2, 60);
        onStatus.accept("Rewards: reconnecting in " + wait + "s");
        timer.schedule(() -> open(URL, false), wait, TimeUnit.SECONDS);
    }

    /** @param migrating true when following Twitch's session_reconnect (subscriptions carry over) */
    private void open(String url, boolean migrating) {
        if (!running) return;
        WebSocket previous = socket;
        Http.client().newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener(migrating, previous))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        MCTwitchChat.LOG.info("EventSub connect failed: {}", err.toString());
                        scheduleReconnect();
                    }
                });
    }

    private final class Listener implements WebSocket.Listener {
        private final boolean migrating;
        private final WebSocket previous;
        private final StringBuilder buffer = new StringBuilder();
        private WebSocket self;

        Listener(boolean migrating, WebSocket previous) {
            this.migrating = migrating;
            this.previous = previous;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            self = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(webSocket, JsonParser.parseString(text).getAsJsonObject());
                } catch (Exception e) {
                    MCTwitchChat.LOG.warn("Bad EventSub message: {}", text, e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            MCTwitchChat.LOG.info("EventSub closed: {} {}", statusCode, reason);
            if (webSocket == socket) {
                socket = null;
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            MCTwitchChat.LOG.info("EventSub error: {}", error.toString());
            if (webSocket == socket) {
                socket = null;
                scheduleReconnect();
            }
        }

        private void handle(WebSocket ws, JsonObject msg) {
            JsonObject meta = msg.getAsJsonObject("metadata");
            JsonObject payload = msg.getAsJsonObject("payload");
            String type = meta.get("message_type").getAsString();
            lastMessage = System.currentTimeMillis();
            switch (type) {
                case "session_welcome" -> {
                    JsonObject session = payload.getAsJsonObject("session");
                    if (session.has("keepalive_timeout_seconds") && !session.get("keepalive_timeout_seconds").isJsonNull()) {
                        keepaliveSeconds = session.get("keepalive_timeout_seconds").getAsInt();
                    }
                    socket = self;
                    if (migrating) {
                        if (previous != null) previous.abort();
                        connected = true;
                    } else {
                        String sessionId = session.get("id").getAsString();
                        CompletableFuture.runAsync(() -> subscribe(sessionId), timer);
                    }
                }
                case "session_reconnect" -> {
                    String url = payload.getAsJsonObject("session").get("reconnect_url").getAsString();
                    open(url, true);
                }
                case "notification" -> {
                    String sub = meta.get("subscription_type").getAsString();
                    onEvent.accept(sub, payload.getAsJsonObject("event"));
                }
                case "revocation" -> {
                    String why = payload.getAsJsonObject("subscription").get("status").getAsString();
                    onStatus.accept("Rewards: Twitch revoked access (" + why + "). Log in again.");
                }
                default -> {
                }
            }
        }
    }

    private void subscribe(String sessionId) {
        if (!TwitchAuth.ensureValid()) {
            onStatus.accept("Rewards: Twitch login is not valid. Log in again on the Rewards tab.");
            return;
        }
        boolean custom = create(sessionId, CUSTOM, "1");
        // Automatic rewards (sub emote unlocks, celebrations…): try v2, fall back to v1.
        boolean auto = create(sessionId, AUTOMATIC, "2") || create(sessionId, AUTOMATIC, "1");
        if (custom) {
            connected = true;
            backoff = 1;
            onStatus.accept("Rewards: listening for redemptions" + (auto ? "" : " (custom rewards only)"));
        } else {
            onStatus.accept("Rewards: could not subscribe. Are you logged in as the channel owner?");
        }
    }

    private boolean create(String sessionId, String type, String version) {
        for (int attempt = 0; attempt < 2; attempt++) {
            TwitchAuth.Tokens t = TwitchAuth.tokens();
            if (t == null) return false;
            JsonObject body = new JsonObject();
            body.addProperty("type", type);
            body.addProperty("version", version);
            JsonObject cond = new JsonObject();
            cond.addProperty("broadcaster_user_id", broadcasterId);
            body.add("condition", cond);
            JsonObject transport = new JsonObject();
            transport.addProperty("method", "websocket");
            transport.addProperty("session_id", sessionId);
            body.add("transport", transport);
            try {
                Http.Response res = Http.send("POST", "https://api.twitch.tv/helix/eventsub/subscriptions",
                        Map.of("Authorization", "Bearer " + t.accessToken, "Client-Id", t.clientId),
                        body.toString(), "application/json");
                if (res.ok()) return true;
                if (res.status() == 401 && attempt == 0 && TwitchAuth.refresh()) continue;
                MCTwitchChat.LOG.warn("EventSub subscribe {} v{} failed: {} {}", type, version, res.status(), res.body());
                return false;
            } catch (Exception e) {
                MCTwitchChat.LOG.warn("EventSub subscribe {} failed", type, e);
                return false;
            }
        }
        return false;
    }
}
