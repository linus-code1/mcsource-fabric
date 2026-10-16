package com.mcsource.client.mixin;

import org.joml.Matrix4f;
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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import com.mcsource.client.mob.SourceModel;
import com.mcsource.model.GoldSrcModel;

/**
 * Draws the rigged suit hand model for the first-person arms instead of the
 * player's blocky skin arm, with its fingers resting in a slowly breathing
 * curl.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
	private void mcsource$suitHand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm,
			boolean sleeve, CallbackInfo ci) {
		SourceModel hand = SourceModel.get("suit_hand");
		if (hand == null) {
			return;
		}
		PlayerModel model = (PlayerModel) ((AvatarRenderer<?>) (Object) this).getModel();
		boolean left = arm == model.leftArm;
		arm.resetPose();
		arm.zRot = left ? -0.1F : 0.1F;

		GoldSrcModel rig = hand.model;
		GoldSrcModel.Sequence idle = rig.find("idle");
		GoldSrcModel.Pose pose = new GoldSrcModel.Pose(rig);
		rig.pose(idle != null ? idle : rig.sequences[0], Util.getMillis() / 1000.0F * 10.0F, pose);
		Matrix4f[] bones = new Matrix4f[rig.bones.length];
		rig.matrices(pose, bones);

		poseStack.pushPose();
		arm.translateAndRotate(poseStack);
		// The model is built in the arm part's 1/16 block units, for the right arm.
		SourceModel.Axes axes = left ? SourceModel.Axes.MIRRORED : SourceModel.Axes.SAME;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(hand.texture), (matrices, consumer) -> {
			hand.skin(bones);
			hand.emit(matrices, consumer, axes, 1.0F / 16.0F, light, OverlayTexture.NO_OVERLAY);
		});
		poseStack.popPose();
		ci.cancel();
	}
}
