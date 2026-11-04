package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;
import com.braydenjohnson.mctwitchchat.util.Http;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One downloaded emote, emoji or GIF. All animation frames live in one texture, packed in a
 * grid with a 2 px transparent gap so smooth filtering never bleeds between frames.
 */
public final class EmoteImage {
    private static final int MAX_TEXTURE = 4096;
    private static final int MAX_FRAMES = 600;

    public final String key;
    final List<String> urls;
    final int maxW;
    final int maxH;
    /** Completes once the image is decoded (or has failed). */
    public final CompletableFuture<EmoteImage> future = new CompletableFuture<>();

    private volatile boolean failed;
    private volatile int frameW;
    private int frameH;
    private int frameCount;
    private int cols;
    private int texW;
    private int texH;
    private int[] frameEnds = new int[0];
    private int totalMs;
    private NativeImage pending;
    private volatile ResourceLocation texture;

    EmoteImage(String key, List<String> urls, int maxW, int maxH) {
        this.key = key;
        this.urls = urls;
        this.maxW = maxW;
        this.maxH = maxH;
    }

    public boolean failed() {
        return failed;
    }

    public boolean hasSize() {
        return frameW > 0;
    }

    public float aspect() {
        return frameW > 0 && frameH > 0 ? (float) frameW / frameH : 1f;
    }

    public ResourceLocation texture() {
        return texture;
    }

    public int frameWidth() {
        return frameW;
    }

    public int frameHeight() {
        return frameH;
    }

    public int textureWidth() {
        return texW;
    }

    public int textureHeight() {
        return texH;
    }

    public float u(int frame) {
        return (frame % cols) * (frameW + 2);
    }

    public float v(int frame) {
        return (frame / cols) * (frameH + 2);
    }

    public int frameIndex(long nowMs, boolean animate) {
        int n = frameCount;
        if (!animate || n <= 1 || totalMs <= 0) return 0;
        long t = nowMs % totalMs;
        int[] ends = frameEnds;
        for (int i = 0; i < n; i++) {
            if (t < ends[i]) return i;
        }
        return n - 1;
    }

    void fail() {
        failed = true;
        future.complete(this);
    }

    /** Worker thread: pick frames and pack them into one image. Frames arrive already scaled. */
    void prepare(ImageDecoder.Decoded decoded) {
        List<BufferedImage> frames = decoded.frames();
        int[] delays = decoded.delaysMs();
        BufferedImage first = frames.get(0);
        int w = Math.min(first.getWidth(), MAX_TEXTURE - 2);
        int h = Math.min(first.getHeight(), MAX_TEXTURE - 2);
        int sx = w + 2;
        int sy = h + 2;
        int maxCols = Math.max(1, MAX_TEXTURE / sx);
        int maxRows = Math.max(1, MAX_TEXTURE / sy);
        int maxFrames = Math.max(1, Math.min(MAX_FRAMES, maxCols * maxRows));
        int step = (int) Math.ceil(frames.size() / (double) maxFrames);

        List<BufferedImage> picked = new ArrayList<>();
        List<Integer> pickedDelays = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += step) {
            picked.add(frames.get(i));
            int sum = 0;
            for (int k = i; k < Math.min(i + step, frames.size()); k++) sum += delays[k];
            pickedDelays.add(Math.max(20, sum));
        }

        int n = picked.size();
        int c = Math.min(n, maxCols);
        int rows = (n + c - 1) / c;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, c * sx, rows * sy, true);
        int[] ends = new int[n];
        int total = 0;
        for (int f = 0; f < n; f++) {
            BufferedImage src = picked.get(f);
            if (src.getWidth() != w || src.getHeight() != h) src = ImageDecoder.resize(src, w, h);
            int x0 = (f % c) * sx;
            int y0 = (f / c) * sy;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = argb >>> 24;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage in 1.21.1 stores ABGR
                    img.setPixelRGBA(x0 + x, y0 + y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            total += pickedDelays.get(f);
            ends[f] = total;
        }
        this.pending = img;
        this.frameH = h;
        this.frameCount = n;
        this.cols = c;
        this.texW = c * sx;
        this.texH = rows * sy;
        this.frameEnds = ends;
        this.totalMs = n > 1 ? total : 0;
        this.frameW = w; // written last: marks the size as known
    }

    /** Render thread: hand the pixels to the GPU. */
    void upload() {
        NativeImage img = pending;
        pending = null;
        if (img == null) return;
        try {
            DynamicTexture tex = new DynamicTexture(img);
            tex.setFilter(true, false);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(MCTwitchChat.MODID, "emote/" + Http.sha1(key));
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            texture = loc;
        } catch (Exception e) {
            MCTwitchChat.LOG.warn("Could not upload emote texture {}", key, e);
            img.close();
            failed = true;
        }
    }
}
