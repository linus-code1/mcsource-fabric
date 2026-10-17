package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import com.mcsource.client.prop.PropModels;

/** A chest drawn as a Half-Life 2 prop is in the chunk mesh, so Minecraft's own chest isn't drawn over it. */
@Mixin(ChestRenderer.class)
public abstract class ChestRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$propChest(ChestRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
			CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && state.blockPos != null && PropModels.isProp(minecraft.level.getBlockState(state.blockPos))) {
			ci.cancel();
		}
	}
}
