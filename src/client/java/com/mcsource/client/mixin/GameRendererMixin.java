package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

/**
 * No blur behind menus: Half-Life 2 draws its menus over a sharp, darkened
 * world, and the blur is one of the things that makes menus look like
 * Minecraft's.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
	private void mcsource$noMenuBlur(CallbackInfo ci) {
		ci.cancel();
	}
}
