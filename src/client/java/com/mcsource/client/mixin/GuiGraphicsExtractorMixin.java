package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Minecraft writes labels on its light grey panels in dark grey. The panels
 * are Half-Life 2's dark grey here, so dark grey text is drawn light instead.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"),
			ordinal = 2, argsOnly = true)
	private int mcsource$lightLabels(int color) {
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		// Black (book pages, signs) stays black.
		if (max >= 0x30 && max <= 0x60 && max - min <= 0x10) {
			return color & 0xFF000000 | 0xD8D8D8;
		}
		return color;
	}
}
