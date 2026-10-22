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
 * Minecraft's. And Half-Life 2's walking bob in place of Minecraft's: the
 * view itself stays level and steady, and only the first-person hands and
 * what they hold bob (see {@link ViewBob}), when the View Bobbing option is
 * on.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
	private void mcsource$noMenuBlur(CallbackInfo ci) {
		ci.cancel();
	}

	private boolean mcsource$hands;

	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void mcsource$handsStart(CallbackInfo ci) {
		mcsource$hands = true;
	}

	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void mcsource$handsEnd(CallbackInfo ci) {
		mcsource$hands = false;
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void mcsource$sourceBob(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		ci.cancel();
		CameraEntityRenderState entity = camera.entityRenderState;
		if (mcsource$hands && entity != null && entity.isPlayer) {
			ViewBob.apply(entity.backwardsInterpolatedWalkDistance, entity.bob, poseStack);
		}
	}
}
