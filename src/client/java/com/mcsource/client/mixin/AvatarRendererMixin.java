package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import com.mcsource.client.HandMesh;

/**
 * Draws a modelled suit hand for the first-person arms instead of the
 * player's blocky skin arm.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	private static final Identifier MCSOURCE_HAND = Identifier.fromNamespaceAndPath("mcsource", "textures/entity/suit_hand.png");

	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void mcsource$suitHand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm,
			boolean sleeve, CallbackInfo ci) {
		PlayerModel model = (PlayerModel) ((AvatarRenderer<?>) (Object) this).getModel();
		boolean left = arm == model.leftArm;
		arm.resetPose();
		arm.zRot = left ? -0.1F : 0.1F;
		poseStack.pushPose();
		arm.translateAndRotate(poseStack);
		collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(MCSOURCE_HAND), (pose, consumer) -> HandMesh.emit(pose, consumer, light, left));
		poseStack.popPose();
		ci.cancel();
	}
}
