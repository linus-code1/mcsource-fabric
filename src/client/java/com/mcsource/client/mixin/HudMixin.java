package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

import com.mcsource.client.SourceHud;

/**
 * The scoreboard down the side of the screen drawn as a Half-Life 2 panel:
 * one translucent dark box with rounded corners and a little room round the
 * text, the title over a faint rule, instead of Minecraft's two square
 * grey strips.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	private static final int PANEL = 0x66000000;
	private static final int RULE = 0x40FFDC00;

	@Redirect(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 0))
	private void mcsource$noHeaderStrip(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
	}

	@Redirect(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 1))
	private void mcsource$panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		// y0 is the top of the scores; the title sits in the 9 pixels above it.
		int left = x0 - 3;
		int top = y0 - 9 - 5;
		int right = Math.min(graphics.guiWidth() - 1, x1 + 2);
		SourceHud.panel(graphics, left, top, right - left, y1 + 4 - top, PANEL, 4);
		graphics.fill(x0 + 1, y0, x1 - 2, y0 + 1, RULE);
	}
}
