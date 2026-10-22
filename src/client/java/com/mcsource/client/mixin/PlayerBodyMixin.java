package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;

import com.mcsource.client.mob.SourcePlayerRenderer;

/**
 * Draws players as a Half-Life 2 character (see {@link SourcePlayerRenderer})
 * in place of the skin model and its layers (armour, cape, held items),
 * while the rest of Minecraft's player drawing (turning, lying down, the
 * name tag) runs as usual. The first-person hands are drawn elsewhere and
 * are not touched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PlayerBodyMixin {
	@Unique
	private static final String SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

	@WrapOperation(method = SUBMIT, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel("
			+ "Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private void mcsource$sourcePlayer(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack poseStack,
			RenderType renderType, int light, int overlay, int tint, UvMapping uvMapping, int outline, Operation<Void> original) {
		if (state instanceof AvatarRenderState avatar && SourcePlayerRenderer.submit(avatar, poseStack, collector, light, overlay)) {
			return;
		}
		original.call(collector, model, state, poseStack, renderType, light, overlay, tint, uvMapping, outline);
	}

	@WrapOperation(method = SUBMIT, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
			+ "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"))
	private boolean mcsource$noSkinLayers(LivingEntityRenderer<?, ?, ?> renderer, LivingEntityRenderState state, Operation<Boolean> original) {
		return !SourcePlayerRenderer.replaces(state) && original.call(renderer, state);
	}
}
