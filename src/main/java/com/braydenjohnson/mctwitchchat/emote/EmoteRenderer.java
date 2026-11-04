package com.braydenjohnson.mctwitchchat.emote;

import com.braydenjohnson.mctwitchchat.config.ChatConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Draws emote images into the gaps left by placeholder characters. Called after any GUI text draw. */
public final class EmoteRenderer {
    private record Pending(EmoteImage image, float x, float width) {
    }

    private EmoteRenderer() {
    }

    public static void render(GuiGraphics graphics, Font font, FormattedCharSequence text, float x, float y, int color) {
        if (!Placeholders.used || text == null) return;
        // Fast path: most text on screen has no emotes.
        boolean none = text.accept((index, style, cp) -> !Placeholders.isPlaceholder(cp, style));
        if (none) return;

        StringSplitter splitter = font.getSplitter();
        List<Pending> found = new ArrayList<>(4);
        float[] cursor = {x};
        text.accept((index, style, cp) -> {
            float advance = splitter.stringWidth(FormattedCharSequence.codepoint(cp, style));
            if (Placeholders.isPlaceholder(cp, style)) {
                EmoteImage img = Placeholders.get(style.getInsertion());
                if (img != null && img.texture() != null) found.add(new Pending(img, cursor[0], advance));
            }
            cursor[0] += advance;
            return true;
        });
        if (found.isEmpty()) return;

        // Same alpha rule as Font: an alpha byte under 4 means "opaque".
        float alpha = (color & 0xFC000000) == 0 ? 1f : ((color >>> 24) & 0xFF) / 255f;
        boolean animate = ChatConfig.get().animated;
        long now = Util.getMillis();

        PoseStack pose = graphics.pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1f, 1f, 1f, alpha);
        for (Pending p : found) {
            EmoteImage img = p.image();
            ResourceLocation tex = img.texture();
            int fw = img.frameWidth();
            int fh = img.frameHeight();
            if (tex == null || fw <= 0 || fh <= 0) continue;
            float drawW = p.width();
            float drawH = Math.min(drawW / img.aspect(), 24f);
            if (drawH * img.aspect() < drawW) drawW = drawH * img.aspect();
            float dx = p.x() + (p.width() - drawW) / 2f;
            // Text glyphs sit in the top 8 px of the 9 px line; centre the emote on them.
            float dy = y + 4f - drawH / 2f;
            int frame = img.frameIndex(now, animate);

            pose.pushPose();
            pose.translate(dx, dy, 0f);
            pose.scale(drawW / fw, drawH / fh, 1f);
            graphics.blit(tex, 0, 0, 0f, (float) (frame * img.stride()), fw, fh, fw, img.textureHeight());
            pose.popPose();
        }
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
