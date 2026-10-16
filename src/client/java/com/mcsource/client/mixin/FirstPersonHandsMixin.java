package com.mcsource.client.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.HumanoidArm;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.client.mob.SourceModel;
import com.mcsource.model.RiggedModel;

/**
 * Draws Half-Life 2's first-person hands, read from the player's own game
 * files, in place of the empty-handed arm. The hands come from a weapon view
 * model with the weapon's own meshes left out, and are placed the way Source
 * places a view model: at the eye, drawn with its narrower view model field
 * of view, idling and swinging with the model's own animations.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	/** View models to try, in order; {@code #...} keeps only meshes whose material names contain one of the words. */
	private static final String[] HANDS = {
		"source:models/weapons/v_hands.mdl",
		"source:models/weapons/v_crowbar.mdl#hand,glove,arm",
		"source:models/weapons/v_physcannon.mdl#hand,glove,arm",
	};
	private static final float UNITS = 1.0F / 40.0F;
	/**
	 * Defaults for the view model settings: Half-Life 2's own field of view of
	 * 54, and a lift and a step in towards the middle, because with the
	 * crowbar left out the empty hand would otherwise sit mostly below the
	 * screen.
	 */
	private static final float DEFAULT_FOV = 54.0F;
	private static final float DEFAULT_OFFSET_X = -2.5F;
	private static final float DEFAULT_OFFSET_Z = 9.0F;
	private static SourceModel trimmed;

	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void mcsource$halfLife2Hands(PoseStack poseStack, SubmitNodeCollector collector, int light, float inverseArmHeight,
			float attackValue, HumanoidArm arm, PlayerRenderState playerState, CallbackInfo ci) {
		SourceModel hands = SourceModel.first(HANDS);
		if (hands == null) {
			return;
		}
		if (hands != trimmed) {
			// The upper arm reaches past the edge of the screen in Source; with
			// the hands moved into view its open end would show.
			hands.hideBones("upperarm", "clavicle", "bicep", "shoulder");
			trimmed = hands;
		}
		RiggedModel model = hands.model;
		RiggedModel.Pose pose = new RiggedModel.Pose(model);
		RiggedModel.Sequence idle = model.find("idle01", "idle", "act_vm_idle");
		RiggedModel.Sequence draw = model.find("draw", "act_vm_draw", "deploy");
		RiggedModel.Sequence swing = model.find("misscenter1", "hitcenter1", "act_vm_misscenter", "act_vm_hitcenter", "attack");
		float seconds = Util.getMillis() / 1000.0F;
		boolean drawing = draw != null && inverseArmHeight > 0.0F;
		if (drawing) {
			// Bringing the hand up after switching items plays Half-Life 2's draw.
			model.pose(draw, (1.0F - inverseArmHeight) * (draw.frameCount() - 1), pose);
		} else if (idle != null) {
			model.pose(idle, seconds * idle.fps(), pose);
		} else {
			model.bindPose(pose);
		}
		if (swing != null && attackValue > 0.0F) {
			RiggedModel.Pose swinging = new RiggedModel.Pose(model);
			model.pose(swing, attackValue * (swing.frameCount() - 1), swinging);
			model.blend(pose, swinging, 1.0F);
		}
		Matrix4f[] bones = new Matrix4f[model.boneCount()];
		model.skinning(pose, bones);

		poseStack.pushPose();
		if (!drawing) {
			// Without a draw animation, lowered while switching items as Minecraft lowers its arm.
			poseStack.translate(0.0F, inverseArmHeight * -0.6F, 0.0F);
		}
		// Spread sideways and up so it fills the screen as it would at the view
		// model field of view. Source's is horizontal on a 4:3 screen and
		// Minecraft's is vertical, so compare their vertical halves.
		float fov = Minecraft.getInstance().options.fov().get();
		float viewModelFov = Mth.clamp(GameInstalls.setting("viewmodel_fov", DEFAULT_FOV), 20.0F, 120.0F);
		double sourceHalf = Math.atan(Math.tan(Math.toRadians(viewModelFov / 2.0F)) * 0.75);
		float spread = (float) (Math.tan(Math.toRadians(fov / 2.0F)) / Math.tan(sourceHalf));
		poseStack.scale(spread, spread, 1.0F);
		// Source's forward (x) becomes the camera's forward (-z).
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		// viewmodel_offset_x/y/z as in Counter-Strike: right, forward, up, in
		// Source units; here x is left, y up and z forward.
		float side = GameInstalls.setting("viewmodel_offset_x", DEFAULT_OFFSET_X) * (arm == HumanoidArm.LEFT ? -1.0F : 1.0F);
		poseStack.translate(-side * UNITS, GameInstalls.setting("viewmodel_offset_z", DEFAULT_OFFSET_Z) * UNITS,
				GameInstalls.setting("viewmodel_offset_y", 0.0F) * UNITS);
		SourceModel.Axes axes = arm == HumanoidArm.LEFT ? SourceModel.Axes.HALF_LIFE_MIRRORED : SourceModel.Axes.HALF_LIFE;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(hands.texture), (matrices, consumer) -> {
			hands.skin(bones);
			hands.emit(matrices, consumer, axes, UNITS, light, OverlayTexture.NO_OVERLAY);
		});
		poseStack.popPose();
		ci.cancel();
	}
}
