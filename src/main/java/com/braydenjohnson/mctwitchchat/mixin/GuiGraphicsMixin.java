package com.braydenjohnson.mctwitchchat.mixin;

import com.braydenjohnson.mctwitchchat.emote.EmoteRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * After GUI text is drawn, paint emote images into the placeholder gaps. Hooks both the
 * vanilla int-position and (if present) NeoForge's float-position overloads; the depth
 * counter makes sure each string is handled once even when one overload calls the other.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Unique
    private static int mctwitchchat$depth;

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("HEAD"))
    private void mctwitchchat$enterInt(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow,
                                       CallbackInfoReturnable<Integer> cir) {
        mctwitchchat$depth++;
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("RETURN"))
    private void mctwitchchat$exitInt(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow,
                                      CallbackInfoReturnable<Integer> cir) {
        if (--mctwitchchat$depth <= 0) {
            mctwitchchat$depth = 0;
            EmoteRenderer.render((GuiGraphics) (Object) this, font, text, x, y, color);
        }
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;FFIZ)I", at = @At("HEAD"), require = 0)
    private void mctwitchchat$enterFloat(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow,
                                         CallbackInfoReturnable<Integer> cir) {
        mctwitchchat$depth++;
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;FFIZ)I", at = @At("RETURN"), require = 0)
    private void mctwitchchat$exitFloat(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow,
                                        CallbackInfoReturnable<Integer> cir) {
        if (--mctwitchchat$depth <= 0) {
            mctwitchchat$depth = 0;
            EmoteRenderer.render((GuiGraphics) (Object) this, font, text, x, y, color);
        }
    }
}
