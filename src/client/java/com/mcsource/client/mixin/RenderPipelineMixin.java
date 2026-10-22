package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.resources.Identifier;

/**
 * See-through block layers (the rock, grass and material blends laid over
 * faces, as well as water and glass) are drawn pulled a little towards the
 * camera by the depth test, as block cracks are. The blends lie on the very
 * face beneath them; lifted off it by a fixed distance alone, they fell
 * behind it again wherever the depth buffer could not tell the two apart,
 * and flickered through it as the player moved. A depth bias scales with
 * the depth buffer's own precision, so they stay in front at any distance.
 */
@Mixin(RenderPipeline.class)
public abstract class RenderPipelineMixin {
	@Shadow
	@Final
	@Mutable
	private DepthStencilState depthStencilState;

	@Shadow
	@Final
	private Identifier location;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcsource$biasBlends(CallbackInfo ci) {
		String path = location.getPath();
		boolean translucentTerrain = path.contains("translucent_terrain") || path.contains("oit") && path.contains("terrain");
		if (translucentTerrain && depthStencilState != null && depthStencilState.depthBiasConstant() == 0.0F) {
			depthStencilState = new DepthStencilState(depthStencilState.depthTest(), depthStencilState.writeDepth(), 1.0F, 8.0F);
			com.mcsource.McSource.LOGGER.info("Depth bias for blend layers on pipeline {}", location);
		}
	}
}
