package com.braydenjohnson.mctwitchchat.twitch;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.braydenjohnson.mctwitchchat.util.Http;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Twitch login with the Device Code Grant flow: the mod shows a code, you approve it at
 * twitch.tv/activate, and the mod gets a token that can only read channel point redemptions.
 * Tokens live in config/mctwitchchat-auth.json, separate from the settings file, so the
 * settings can be shared without leaking the login.
 */
public final class TwitchAuth {
    public static final String SCOPES = "channel:read:redemptions";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Tokens {
        public String clientId = "";
        public String accessToken = "";
        public String refreshToken = "";
        public String login = "";
        public String userId = "";
    }

    private static volatile Tokens tokens;
    private static volatile String status = "Not logged in";
    private static volatile boolean loginRunning;
    private static volatile boolean cancelLogin;

    private TwitchAuth() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(MCTwitchChat.MODID + "-auth.json");
    }

    public static synchronized Tokens tokens() {
        if (tokens == null) {
            tokens = load();
            if (tokens != null) status = "Logged in as " + tokens.login;
        }
        return tokens;
    }

    public static boolean loggedIn() {
        Tokens t = tokens();
        return t != null && !t.accessToken.isEmpty();
    }

    public static String status() {
        return status;
    }

    public static boolean loginRunning() {
        return loginRunning;
    }

    private static Tokens load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return null;
        try {
            Tokens t = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Tokens.class);
            return t != null && t.accessToken != null && !t.accessToken.isEmpty() ? t : null;
        } catch (Exception e) {
            MCTwitchChat.LOG.warn("Could not read {}", f, e);
            return null;
        }
    }

    private static synchronized void save(Tokens t) {
        tokens = t;
        Path f = file();
        try {
            if (t == null) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(t), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            MCTwitchChat.LOG.error("Could not save {}", f, e);
        }
    }

    public static void logout() {
        cancelLogin = true;
        Tokens t = tokens();
        save(null);
        status = "Not logged in";
        if (t != null && !t.accessToken.isEmpty()) {
            Http.POOL.execute(() -> {
                try {
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("client_id", t.clientId);
                    form.put("token", t.accessToken);
                    Http.postForm("https://id.twitch.tv/oauth2/revoke", form);
                } catch (Exception ignored) {
                }
            });
        }
    }

    /**
     * Starts the device login in the background.
     *
     * @param openUrl  called with the twitch.tv/activate link (opens the browser)
     * @param onCode   called with the code the user has to confirm
     * @param onDone   called when login succeeds
     */
    public static void startLogin(String clientId, Consumer<String> openUrl, Consumer<String> onCode, Runnable onDone) {
        if (loginRunning) return;
        if (clientId == null || clientId.isBlank()) {
            status = "Paste your Twitch app's Client ID first";
            return;
        }
        loginRunning = true;
        cancelLogin = false;
        status = "Starting Twitch login…";
        Http.POOL.execute(() -> {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", clientId);
                form.put("scopes", SCOPES);
                Http.Response res = Http.postForm("https://id.twitch.tv/oauth2/device", form);
                if (!res.ok()) {
                    status = "Twitch refused the login (" + res.status() + "). Is the Client ID right and the app set to Public?";
                    MCTwitchChat.LOG.warn("Device code request failed: {} {}", res.status(), res.body());
                    return;
                }
                JsonObject d = res.json().getAsJsonObject();
                String deviceCode = d.get("device_code").getAsString();
                String userCode = d.get("user_code").getAsString();
                String uri = d.get("verification_uri").getAsString();
                int interval = Math.max(2, d.has("interval") ? d.get("interval").getAsInt() : 5);
                long expires = System.currentTimeMillis() + 1000L * (d.has("expires_in") ? d.get("expires_in").getAsInt() : 1800);
                status = "Approve code " + userCode + " at twitch.tv/activate (opened in your browser)";
                onCode.accept(userCode);
                openUrl.accept(uri);

                while (!cancelLogin && System.currentTimeMillis() < expires) {
                    Thread.sleep(interval * 1000L);
                    Map<String, String> poll = new LinkedHashMap<>();
                    poll.put("client_id", clientId);
                    poll.put("scopes", SCOPES);
                    poll.put("device_code", deviceCode);
                    poll.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                    Http.Response t = Http.postForm("https://id.twitch.tv/oauth2/token", poll);
                    if (t.ok()) {
                        JsonObject tok = t.json().getAsJsonObject();
                        Tokens fresh = new Tokens();
                        fresh.clientId = clientId;
                        fresh.accessToken = tok.get("access_token").getAsString();
                        fresh.refreshToken = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : "";
                        if (!validate(fresh)) {
                            status = "Login worked but the token could not be checked. Try again.";
                            return;
                        }
                        save(fresh);
                        status = "Logged in as " + fresh.login;
                        onDone.run();
                        return;
                    }
                    String body = t.body();
                    if (body.contains("authorization_pending")) continue;
                    if (body.contains("slow_down")) {
                        interval += 5;
                        continue;
                    }
                    status = "Login failed: " + shortMessage(t);
                    MCTwitchChat.LOG.warn("Device token poll failed: {} {}", t.status(), body);
                    return;
                }
                if (!cancelLogin) status = "The login code expired. Click Log in again.";
            } catch (Exception e) {
                status = "Login failed: " + e.getMessage();
                MCTwitchChat.LOG.warn("Twitch login failed", e);
            } finally {
                loginRunning = false;
            }
        });
    }

    /** Fills in login and user id. Returns false if the token is not valid. */
    private static boolean validate(Tokens t) throws IOException {
        Http.Response res = Http.send("GET", "https://id.twitch.tv/oauth2/validate",
                Map.of("Authorization", "OAuth " + t.accessToken), null, null);
        if (!res.ok()) return false;
        JsonObject v = res.json().getAsJsonObject();
        t.login = v.has("login") ? v.get("login").getAsString() : "";
        t.userId = v.has("user_id") ? v.get("user_id").getAsString() : "";
        return !t.userId.isEmpty();
    }

    /**
     * Makes sure the access token still works, refreshing it if needed (access tokens last
     * about 4 hours; the refresh token lasts 30 days of inactivity). Blocking.
     */
    public static synchronized boolean ensureValid() {
        Tokens t = tokens();
        if (t == null) return false;
        try {
            if (validate(t)) return true;
            return refresh();
        } catch (IOException e) {
            // Offline: keep the token and let the caller retry later.
            return false;
        }
    }

    /** Swaps the refresh token for a new pair. Refresh tokens are single use. */
    public static synchronized boolean refresh() {
        Tokens t = tokens();
        if (t == null || t.refreshToken.isEmpty()) return false;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", t.clientId);
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", t.refreshToken);
            Http.Response res = Http.postForm("https://id.twitch.tv/oauth2/token", form);
            if (!res.ok()) {
                MCTwitchChat.LOG.warn("Token refresh failed: {} {}", res.status(), res.body());
                if (res.status() == 400 || res.status() == 401) {
                    save(null);
                    status = "Twitch login expired. Log in again on the Rewards tab.";
                }
                return false;
            }
            JsonObject tok = res.json().getAsJsonObject();
            Tokens fresh = new Tokens();
            fresh.clientId = t.clientId;
            fresh.accessToken = tok.get("access_token").getAsString();
            fresh.refreshToken = tok.has("refresh_token") ? tok.get("refresh_token").getAsString() : t.refreshToken;
            if (!validate(fresh)) return false;
            save(fresh);
            status = "Logged in as " + fresh.login;
            return true;
        } catch (Exception e) {
            MCTwitchChat.LOG.warn("Token refresh failed", e);
            return false;
        }
    }

    /** The Client ID to use: the one from login, falling back to the settings. */
    public static String clientId() {
        Tokens t = tokens();
        if (t != null && !t.clientId.isEmpty()) return t.clientId;
        return ChatConfig.get().twitchClientId;
    }

    private static String shortMessage(Http.Response r) {
        try {
            JsonObject o = r.json().getAsJsonObject();
            if (o.has("message")) return o.get("message").getAsString();
        } catch (Exception ignored) {
        }
        return "HTTP " + r.status();
    }
}
