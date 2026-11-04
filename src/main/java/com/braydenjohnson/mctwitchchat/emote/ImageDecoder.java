package com.braydenjohnson.mctwitchchat.emote;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Decodes PNG/JPEG and animated GIF (with proper frame disposal) into ARGB frames. */
public final class ImageDecoder {
    private static final int MAX_GIF_FRAMES = 600;

    public record Decoded(List<BufferedImage> frames, int[] delaysMs) {
    }

    private ImageDecoder() {
    }

    /** Decodes and scales every frame down to fit maxW x maxH (never up). */
    public static Decoded decode(byte[] data, int maxW, int maxH) throws IOException {
        if (isGif(data)) {
            Decoded gif = decodeGif(data, maxW, maxH);
            if (gif != null) return gif;
        }
        if (isWebp(data)) throw new IOException("WebP images are not supported");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new IOException("Unsupported image format");
        return new Decoded(List.of(fit(img, maxW, maxH)), new int[]{0});
    }

    /** A fresh ARGB copy, scaled down to fit if needed. */
    static BufferedImage fit(BufferedImage src, int maxW, int maxH) {
        float scale = Math.min(1f, Math.min((float) maxW / src.getWidth(), (float) maxH / src.getHeight()));
        if (scale >= 1f) return copy(src);
        return resize(src, Math.max(1, Math.round(src.getWidth() * scale)), Math.max(1, Math.round(src.getHeight() * scale)));
    }

    private static boolean isGif(byte[] d) {
        return d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8';
    }

    private static boolean isWebp(byte[] d) {
        return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    private static Decoded decodeGif(byte[] data, int maxW, int maxH) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) return null;
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, false);
            int width = -1;
            int height = -1;
            IIOMetadata stream = reader.getStreamMetadata();
            if (stream != null) {
                Node root = stream.getAsTree(stream.getNativeMetadataFormatName());
                Node lsd = child(root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    width = intAttr(lsd, "logicalScreenWidth", -1);
                    height = intAttr(lsd, "logicalScreenHeight", -1);
                }
            }
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            BufferedImage canvas = null;
            for (int i = 0; i < MAX_GIF_FRAMES; i++) {
                BufferedImage frame;
                IIOMetadata meta;
                try {
                    frame = reader.read(i);
                    meta = reader.getImageMetadata(i);
                } catch (IndexOutOfBoundsException end) {
                    break;
                } catch (Exception broken) {
                    // Some GIFs have junk after the last good frame; keep what decoded.
                    if (frames.isEmpty()) throw new IOException("Broken GIF", broken);
                    break;
                }
                Node tree = meta.getAsTree("javax_imageio_gif_image_1.0");
                Node desc = child(tree, "ImageDescriptor");
                int left = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
                int top = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
                Node gce = child(tree, "GraphicControlExtension");
                int delayCs = gce == null ? 10 : intAttr(gce, "delayTime", 10);
                String disposal = gce == null ? "none" : attr(gce, "disposalMethod", "none");

                if (canvas == null) {
                    if (width <= 0 || height <= 0) {
                        width = frame.getWidth() + left;
                        height = frame.getHeight() + top;
                    }
                    canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }
                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, left, top, null);
                g.dispose();
                frames.add(fit(canvas, maxW, maxH));
                // Browsers treat 0 and 1 centisecond delays as 100 ms; do the same.
                delays.add(delayCs <= 1 ? 100 : delayCs * 10);

                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(AlphaComposite.Clear);
                    clear.fillRect(left, top, frame.getWidth(), frame.getHeight());
                    clear.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
            if (frames.isEmpty()) return null;
            int[] d = new int[delays.size()];
            for (int i = 0; i < d.length; i++) d[i] = delays.get(i);
            return new Decoded(frames, d);
        } finally {
            reader.dispose();
        }
    }

    static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName())) return n;
        }
        return null;
    }

    private static String attr(Node node, String name, String def) {
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) return def;
        Node a = attrs.getNamedItem(name);
        return a == null ? def : a.getNodeValue();
    }

    private static int intAttr(Node node, String name, int def) {
        try {
            return Integer.parseInt(attr(node, name, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
