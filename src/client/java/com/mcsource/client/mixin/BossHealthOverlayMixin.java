package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.resources.Identifier;
import net.minecraft.world.BossEvent;

import com.mcsource.client.SourceHud;

/**
 * Boss bars drawn plainly in the manner of Half-Life 2's HUD bars, such as
 * the suit's aux power: a flat translucent dark track with rounded ends and
 * a flat bar of colour in it, in place of Minecraft's bevelled bar and its
 * notches.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
	private static final int TRACK = 0x66000000;

	@Shadow
	@Final
	private static Identifier[] BAR_BACKGROUND_SPRITES;

	@Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$flatBar(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, int width, Identifier[] sprites,
			Identifier[] overlaySprites, CallbackInfo ci) {
		ci.cancel();
		if (sprites == BAR_BACKGROUND_SPRITES) {
			SourceHud.panel(graphics, x - 1, y - 1, width + 2, 7, TRACK, 2);
		} else if (width > 0) {
			graphics.fill(x + 1, y + 1, x + width - 1, y + 4, colour(event.getColor()));
		}
	}

	private static int colour(BossEvent.BossBarColor color) {
		return switch (color) {
			case PINK -> 0xD8E080B0;
			case BLUE -> 0xD860A8E8;
			case RED -> 0xD8D04030;
			case GREEN -> 0xD870C040;
			case PURPLE -> 0xD8A060D0;
			case WHITE -> 0xD8E0E0E0;
			default -> 0xD8FFDC00;
		};
	}
}
