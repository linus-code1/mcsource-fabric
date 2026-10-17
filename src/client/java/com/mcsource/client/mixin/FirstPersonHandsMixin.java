package com.mcsource.client.mixin;

import org.joml.Matrix4f;
import org.joml.Vector3f;
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
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

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
		// Half-Life: Source's crowbar, for players who own that but not Half-Life 2.
		"source:models/v_crowbar.mdl#hand,glove,arm",
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
	private static int mcsource$swingVariant;
	/** Whether the crowbar is out for a swing, which hides the held item. */
	private static boolean mcsource$crowbarOut;
	/** Held items are drawn this much smaller than Minecraft's, as they are held closer to the eye. */
	private static final float ITEM_SCALE = 0.4F;

	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void mcsource$halfLife2Hands(PoseStack poseStack, SubmitNodeCollector collector, int light, float inverseArmHeight,
			float attackValue, HumanoidArm arm, PlayerRenderState playerState, CallbackInfo ci) {
		if (mcsource$drawHands(poseStack, collector, light, attackValue, arm) != null) {
			ci.cancel();
		}
	}

	/**
	 * A held item is held in Half-Life 2's hand: the hand is drawn with its
	 * own animations and the item rides in its palm, instead of Minecraft's
	 * floating item with its jab and dip. Items being used (eaten, drawn,
	 * charged) and maps keep Minecraft's own animations.
	 */
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void mcsource$itemInHalfLife2Hand(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks,
			float xRot, InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
			SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		if (hand != InteractionHand.MAIN_HAND || itemStack.isEmpty() || itemStack.has(DataComponents.MAP_ID) || state.isScoping
				|| state.useItemRemainingTicks > 0 || playerState.avatarRenderState == null) {
			return;
		}
		HumanoidArm arm = playerState.avatarRenderState.mainArm;
		Vector3f palm = mcsource$drawHands(poseStack, collector, lightCoords, attack, arm);
		if (palm == null) {
			return;
		}
		if (mcsource$crowbarOut) {
			ci.cancel();
			return;
		}
		poseStack.pushPose();
		poseStack.translate(palm.x, palm.y, palm.z);
		poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
		state.mainHandRenderState.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
		ci.cancel();
	}

	/**
	 * Draws the hands; returns where the palm is, in the pose stack's space as
	 * it was when called, or null when there are no Half-Life 2 hands.
	 */
	private static Vector3f mcsource$drawHands(PoseStack poseStack, SubmitNodeCollector collector, int light, float attackValue,
			HumanoidArm arm) {
		SourceModel hands = null;
		SourceModel crowbar = null;
		for (String reference : HANDS) {
			hands = SourceModel.get(reference);
			if (hands != null) {
				// The same view model with its weapon, shown while swinging.
				int hash = reference.indexOf('#');
				crowbar = hash < 0 ? null : SourceModel.get(reference.substring(0, hash));
				break;
			}
		}
		if (hands == null) {
			return null;
		}
		if (crowbar != null && crowbar.model.boneCount() != hands.model.boneCount()) {
			crowbar = null;
		}
		Matrix4f base = new Matrix4f(poseStack.last().pose());
		if (hands != trimmed) {
			// The upper arm reaches past the edge of the screen in Source; with
			// the hands moved into view its open end would show.
			hands.hideBones("upperarm", "clavicle", "bicep", "shoulder");
			if (crowbar != null) {
				crowbar.hideBones("upperarm", "clavicle", "bicep", "shoulder");
			}
			trimmed = hands;
		}
		RiggedModel model = hands.model;
		RiggedModel.Pose pose = new RiggedModel.Pose(model);
		RiggedModel.Sequence idle = model.find("idle01", "idle", "act_vm_idle");
		RiggedModel.Sequence draw = model.find("draw", "act_vm_draw", "deploy");
		RiggedModel.Sequence swing = mcsource$swingVariant == 0
				? model.find("misscenter1", "hitcenter1", "act_vm_misscenter", "act_vm_hitcenter", "attack")
				: model.find("misscenter2", "hitcenter2", "misscenter1", "act_vm_misscenter", "attack");
		long now = Util.getMillis();
		float seconds = now / 1000.0F;
		// Minecraft lowers its arm after every hit while the attack recharges and
		// swings it in a quick jab. Half-Life 2's hands do neither: they play
		// the view model's own animations at their own speed. The draw plays
		// only after changing slot, and a swing plays through once when an
		// attack starts.
		int slot = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getInventory().getSelectedSlot();
		if (slot != mcsource$slot) {
			mcsource$slot = slot;
			mcsource$drawStart = now;
		}
		// A swing always plays through at the model's own speed. Minecraft
		// restarts its quick jab every few ticks while a block is being mined;
		// restarting the view model's swing that often cut it into the same
		// jab, so a new swing starts only once the last one has finished.
		boolean attackStarted = attackValue > 0.0F && (attackValue < mcsource$lastAttack || mcsource$lastAttack == 0.0F);
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
		boolean playing = swing != null && mcsource$swingStart > 0 && swingTime < swing.frameCount() - 1;
		if (attackStarted && !playing) {
			mcsource$swingStart = now;
			mcsource$swingVariant ^= 1;
			swing = mcsource$swingVariant == 0
					? model.find("misscenter1", "hitcenter1", "act_vm_misscenter", "act_vm_hitcenter", "attack")
					: model.find("misscenter2", "hitcenter2", "misscenter1", "act_vm_misscenter", "attack");
			swingTime = 0.0F;
		}
		mcsource$crowbarOut = swing != null && mcsource$swingStart > 0 && swingTime < swing.frameCount() - 1;
		if (mcsource$crowbarOut) {
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
		// While a swing plays, the crowbar is out, as in Half-Life 2.
		SourceModel shown = mcsource$crowbarOut && crowbar != null ? crowbar : hands;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(shown.texture), (matrices, consumer) -> {
			shown.skin(bones);
			shown.emit(matrices, consumer, axes, UNITS, light, OverlayTexture.NO_OVERLAY);
		});
		// Where the palm is now, for an item held in it.
		hands.skin(bones);
		Vector3f palm = hands.centroid(arm == HumanoidArm.LEFT ? "l_hand" : "r_hand", arm == HumanoidArm.LEFT ? "l hand" : "r hand", "hand");
		if (palm == null) {
			palm = new Vector3f();
		}
		// Half-Life's axes to the model's, as the hands are drawn.
		Vector3f point = new Vector3f(palm.y * (arm == HumanoidArm.LEFT ? -1.0F : 1.0F), palm.z, palm.x).mul(UNITS);
		poseStack.last().pose().transformPosition(point);
		poseStack.popPose();
		return base.invert().transformPosition(point);
	}
}
