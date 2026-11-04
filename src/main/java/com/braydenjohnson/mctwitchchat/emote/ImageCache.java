package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.util.Http;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Downloads, decodes and keeps emote images. Raw downloads are also cached on disk. */
public final class ImageCache {
    private static final Map<String, EmoteImage> IMAGES = new ConcurrentHashMap<>();

    private ImageCache() {
    }

    public static Path diskDir() {
        return FMLPaths.GAMEDIR.get().resolve("mctwitchchat-cache");
    }

    /** Emotes and emoji: small images. */
    public static EmoteImage get(List<String> urls) {
        return get(urls, 256, 64);
    }

    public static EmoteImage get(List<String> urls, int maxW, int maxH) {
        String key = urls.get(0);
        return IMAGES.computeIfAbsent(key, k -> {
            EmoteImage img = new EmoteImage(k, urls, maxW, maxH);
            Http.POOL.execute(() -> load(img));
            return img;
        });
    }

    private static void load(EmoteImage img) {
        Exception last = null;
        for (String url : img.urls) {
            try {
                byte[] bytes = fetch(url);
                ImageDecoder.Decoded decoded = ImageDecoder.decode(bytes, img.maxW, img.maxH);
                img.prepare(decoded);
                Minecraft.getInstance().execute(img::upload);
                img.future.complete(img);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        MCTwitchChat.LOG.debug("Emote image failed: {} ({})", img.key, last == null ? "no urls" : last.toString());
        img.fail();
    }

    private static byte[] fetch(String url) throws IOException {
        Path file = diskDir().resolve(Http.sha1(url) + ".bin");
        if (Files.isRegularFile(file)) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException ignored) {
            }
        }
        byte[] bytes = Http.getBytes(url, Http.ACCEPT_IMAGE);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            MCTwitchChat.LOG.debug("Could not cache {}", url, e);
        }
        return bytes;
    }

    /** Deletes the on-disk cache. Images already in memory keep working. */
    public static int clearDisk() {
        int count = 0;
        Path dir = diskDir();
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                try {
                    Files.deleteIfExists(p);
                    count++;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            MCTwitchChat.LOG.warn("Could not clear emote cache", e);
        }
        return count;
    }

    public static int loadedCount() {
        return IMAGES.size();
    }
}
