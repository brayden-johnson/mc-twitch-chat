package com.braydenjohnson.mctwitchchat.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class Http {
    public static final String USER_AGENT = "MCTwitchChat (+https://github.com/brayden-johnson/mc-twitch-chat)";
    public static final String ACCEPT_IMAGE = "image/gif,image/png;q=0.9,image/jpeg;q=0.8,*/*;q=0.1";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final AtomicInteger THREADS = new AtomicInteger();
    /** Background pool for downloads and image decoding. */
    public static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "MCTwitchChat-Worker-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private Http() {
    }

    public static byte[] getBytes(String url, String accept) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode() + " for " + url);
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad URL " + url, e);
        }
    }

    public static JsonElement getJson(String url) throws IOException {
        return JsonParser.parseString(new String(getBytes(url, "application/json"), StandardCharsets.UTF_8));
    }

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** "//cdn..." to "https://cdn..." */
    public static String fixUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }
}
