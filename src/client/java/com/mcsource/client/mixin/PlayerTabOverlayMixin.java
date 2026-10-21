package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;

import com.mcsource.client.SourceHud;

/**
 * The player list (held Tab) drawn like a Source game's scoreboard: dark
 * translucent panels with rounded corners for the header, the players and
 * the footer, and only a faint band behind each player instead of
 * Minecraft's grey boxes.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	private static final int PANEL = 0x99000000;
	private static final int ROW = 0x12FFFFFF;

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void mcsource$panels(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		if (color == Integer.MIN_VALUE) {
			// A section: header, players or footer.
			SourceHud.panel(graphics, x0 - 3, y0 - 2, x1 - x0 + 6, y1 - y0 + 4, PANEL, 4);
		} else {
			graphics.fill(x0, y0, x1, y1, ROW);
		}
	}
}
