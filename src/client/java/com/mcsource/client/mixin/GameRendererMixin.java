package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import com.mcsource.client.ViewBob;

/**
 * No blur behind menus: Half-Life 2 draws its menus over a sharp, darkened
 * world, and the blur is one of the things that makes menus look like
 * Minecraft's. And a Half-Life 2 style walking bob (see {@link ViewBob}) in
 * place of Minecraft's; the game only bobs when the View Bobbing option is
 * on, and the same bob moves the view and the first-person hands.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
	private void mcsource$noMenuBlur(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void mcsource$sourceBob(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		ci.cancel();
		CameraEntityRenderState entity = camera.entityRenderState;
		if (entity != null && entity.isPlayer) {
			ViewBob.apply(entity.backwardsInterpolatedWalkDistance, entity.bob, poseStack);
		}
	}
}
