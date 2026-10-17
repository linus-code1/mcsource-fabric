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
	/** Pulled back towards the eye, so the hand is not held out ahead. */
	private static final float DEFAULT_OFFSET_Y = -8.0F;
	private static final float DEFAULT_OFFSET_Z = 10.0F;
	private static SourceModel trimmed;
	private static int mcsource$slot = -1;
	private static long mcsource$drawStart;
	private static long mcsource$swingStart;
	private static float mcsource$lastAttack;

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
		long now = Util.getMillis();
		float seconds = now / 1000.0F;
		// Minecraft lowers its arm after every hit while the attack recharges and
		// swings it in a quick jab. Half-Life 2's hands do neither: they play
		// the view model's own animations at their own speed. The draw plays
		// only after changing slot, and a swing plays through once when an
		// attack starts.
		int slot = playerState == null ? 0 : Minecraft.getInstance().player == null ? 0
				: Minecraft.getInstance().player.getInventory().getSelectedSlot();
		if (slot != mcsource$slot) {
			mcsource$slot = slot;
			mcsource$drawStart = now;
		}
		if (attackValue > 0.0F && attackValue < mcsource$lastAttack || attackValue > 0.0F && mcsource$lastAttack == 0.0F) {
			mcsource$swingStart = now;
		}
		mcsource$lastAttack = attackValue;
		float drawTime = draw == null ? 0.0F : (now - mcsource$drawStart) / 1000.0F * draw.fps();
		boolean drawing = draw != null && drawTime < draw.frameCount() - 1;
		if (drawing) {
			model.pose(draw, drawTime, pose);
		} else if (idle != null) {
			model.pose(idle, seconds * idle.fps(), pose);
		} else {
			model.bindPose(pose);
		}
		float swingTime = swing == null ? 0.0F : (now - mcsource$swingStart) / 1000.0F * swing.fps();
		if (swing != null && mcsource$swingStart > 0 && swingTime < swing.frameCount() - 1) {
			RiggedModel.Pose swinging = new RiggedModel.Pose(model);
			model.pose(swing, swingTime, swinging);
			model.blend(pose, swinging, 1.0F);
		}
		Matrix4f[] bones = new Matrix4f[model.boneCount()];
		model.skinning(pose, bones);

		poseStack.pushPose();
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
				GameInstalls.setting("viewmodel_offset_y", DEFAULT_OFFSET_Y) * UNITS);
		SourceModel.Axes axes = arm == HumanoidArm.LEFT ? SourceModel.Axes.HALF_LIFE_MIRRORED : SourceModel.Axes.HALF_LIFE;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(hands.texture), (matrices, consumer) -> {
			hands.skin(bones);
			hands.emit(matrices, consumer, axes, UNITS, light, OverlayTexture.NO_OVERLAY);
		});
		poseStack.popPose();
		ci.cancel();
	}
}
