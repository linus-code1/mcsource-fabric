package com.mcsource.client.mob;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;

import com.mcsource.model.RiggedModel;

/**
 * Draws players (others, and yourself in third person) as a Half-Life 2
 * character instead of the blocky skin model. The model's own animations
 * are blended from what the player is doing: standing, walking, running,
 * crouching, in the air or riding, with the arms swinging on a hit and the
 * head turned to where the player looks. Players are drawn as in Minecraft
 * when none of the models can be found.
 *
 * <p>This draws in place of the skin model inside Minecraft's own player
 * renderer (see {@code PlayerBodyMixin}), so turning, lying down, swimming,
 * gliding, dying, the shadow and the name tag all work as before.
 */
public final class SourcePlayerRenderer {
	/** A rebel first, then the same man as a citizen, then a player model. */
	private static final String[] MODELS = {
		"source:models/humans/group03/male_07.mdl",
		"source:models/humans/group01/male_07.mdl",
		"source:models/player.mdl",
		"halflife:models/player.mdl",
	};
	private static final boolean ENABLED = GameInstalls.flag("hl2_player", true);

	// Sequence names (by prefix) and activities, first match wins.
	private static final String[] IDLE = {"act_idle", "idle_unarmed", "idle01", "idle1", "idle", "look_idle"};
	private static final String[] WALK = {"act_walk", "walk_all", "walk"};
	private static final String[] RUN = {"act_run", "run_all", "run"};
	private static final String[] CROUCH = {"act_crouchidle", "act_cover_low", "crouch_idle", "crouchidle", "crouch"};
	private static final String[] CROUCH_WALK = {"act_walk_crouch", "crouch_walk", "crouchwalk", "act_run_crouch"};
	private static final String[] AIR = {"act_glide", "act_jump", "jump_holding_glide", "jump_holding_jump", "jump"};
	private static final String[] SIT = {"act_busy_sit_chair", "act_sit", "sitchair", "sit_chair", "sit"};
	private static final String[] SWING = {"act_melee_attack_swing", "act_gesture_melee_attack_swing", "act_melee_attack1",
		"swing", "melee", "throw"};

	/** The player's standing height in blocks, which the model is scaled to. */
	private static final float HEIGHT = 1.8F;
	/** Undoes the skin model's own frame (see LivingEntityRenderer.submit and AvatarRenderer.scale). */
	private static final float SKIN_SCALE = 0.9375F;
	private static final float SKIN_DROP = 1.501F;
	/** How far Minecraft lowers a crouching player, which the crouching animation already does. */
	private static final float CROUCH_DROP = 2.0F / 16.0F;
	/** Walking is about 4.3 blocks a second, sprinting 5.6. */
	private static final float RUN_FROM = 4.7F;
	private static final float RUN_FULL = 5.4F;
	/** How quickly blends follow what the player does, per second. */
	private static final float BLEND_RATE = 10.0F;

	/** How each player moves, followed from frame to frame. */
	private static final class Motion {
		double x;
		double z;
		boolean placed;
		float distance;
		float move;
		float run;
		float crouch;
		float air;
		float airTime;
		float sit;
		long nanos;
	}

	private static final Map<Integer, Motion> MOTIONS = new HashMap<>();
	private static final Map<RiggedModel, Bones> BONES = new IdentityHashMap<>();

	/** The bones that the head look and the upper body swing work on. */
	private record Bones(int neck, int head, boolean[] upper) {
	}

	private SourcePlayerRenderer() {
	}

	/** The model players are drawn with, or null to draw them as in Minecraft. */
	public static SourceModel model() {
		return ENABLED ? SourceModel.first(MODELS) : null;
	}

	/** Whether this player is drawn with the model. */
	public static boolean replaces(Object state) {
		return state instanceof AvatarRenderState avatar && avatar.entityType == EntityTypes.PLAYER && model() != null;
	}

	/**
	 * Submits the player's model in place of the skin model. The pose stack
	 * is as Minecraft left it for the skin model; returns false to draw the
	 * skin model after all.
	 */
	public static boolean submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay) {
		if (state.entityType != EntityTypes.PLAYER) {
			return false;
		}
		SourceModel loaded = model();
		if (loaded == null) {
			return false;
		}
		if (state.isInvisible) {
			return true;
		}
		RiggedModel model = loaded.model;
		float scale = loaded.height > 1.0F ? HEIGHT / loaded.height : 1.0F / 40.0F;
		Motion motion = follow(state);
		RiggedModel.Pose pose = new RiggedModel.Pose(model);
		animate(model, state, motion, pose, scale);
		Matrix4f[] bones = new Matrix4f[model.boneCount()];
		model.skinning(pose, bones);
		Bones rig = BONES.computeIfAbsent(model, SourcePlayerRenderer::findBones);
		look(model, rig, bones, state);

		poseStack.pushPose();
		// Back from the skin model's upside-down, backwards frame to the feet,
		// facing where the body faces.
		poseStack.translate(0.0F, SKIN_DROP, 0.0F);
		poseStack.scale(1.0F / SKIN_SCALE, 1.0F / SKIN_SCALE, 1.0F / SKIN_SCALE);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		if (state.isCrouching) {
			poseStack.translate(0.0F, CROUCH_DROP, 0.0F);
		}
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(loaded.texture), (matrices, consumer) -> {
			loaded.skin(bones);
			loaded.emit(matrices, consumer, SourceModel.Axes.HALF_LIFE, scale, light, overlay);
		});
		poseStack.popPose();
		return true;
	}

	/** Updates how this player is moving, smoothly, from where they are now. */
	private static Motion follow(AvatarRenderState state) {
		long now = Util.getNanos();
		if (MOTIONS.size() > 256) {
			// Forget players not drawn for a while.
			for (Iterator<Motion> it = MOTIONS.values().iterator(); it.hasNext();) {
				if (now - it.next().nanos > 30_000_000_000L) {
					it.remove();
				}
			}
		}
		Motion motion = MOTIONS.computeIfAbsent(state.id, id -> new Motion());
		float seconds = motion.placed ? Mth.clamp((now - motion.nanos) / 1.0E9F, 0.0F, 0.25F) : 0.0F;
		motion.nanos = now;

		double dx = state.x - motion.x;
		double dz = state.z - motion.z;
		double step = Math.sqrt(dx * dx + dz * dz);
		if (motion.placed && step < 4.0) {
			motion.distance += (float) step;
		}
		motion.x = state.x;
		motion.z = state.z;

		Minecraft minecraft = Minecraft.getInstance();
		Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(state.id);
		float speed;
		boolean grounded;
		if (entity != null) {
			// Blocks a second, from the last tick's movement.
			speed = (float) Math.sqrt(Mth.square(entity.getX() - entity.xo) + Mth.square(entity.getZ() - entity.zo)) * 20.0F;
			boolean flying = entity instanceof Player player && player.getAbilities().flying;
			grounded = entity.onGround() || flying || entity.isInWater();
		} else {
			speed = state.walkAnimationSpeed * 5.0F;
			grounded = true;
		}
		boolean riding = state.isPassenger;
		boolean lying = state.isVisuallySwimming || state.isFallFlying || state.isAutoSpinAttack;

		float follow = motion.placed ? 1.0F - (float) Math.exp(-BLEND_RATE * seconds) : 1.0F;
		float moving = riding ? 0.0F : Mth.clamp(speed / (state.isCrouching ? 0.9F : 1.8F), 0.0F, 1.0F);
		float running = Mth.clamp((speed - RUN_FROM) / (RUN_FULL - RUN_FROM), 0.0F, 1.0F);
		motion.move += (moving - motion.move) * follow;
		motion.run += (running - motion.run) * follow;
		motion.crouch += ((state.isCrouching && !riding ? 1.0F : 0.0F) - motion.crouch) * follow;
		boolean inAir = !grounded && !riding && !lying;
		motion.air += ((inAir ? 1.0F : 0.0F) - motion.air) * follow;
		motion.airTime = inAir ? motion.airTime + seconds : 0.0F;
		motion.sit += ((riding ? 1.0F : 0.0F) - motion.sit) * follow;
		motion.placed = true;
		return motion;
	}

	private static void animate(RiggedModel model, AvatarRenderState state, Motion motion, RiggedModel.Pose pose, float scale) {
		float seconds = state.ageInTicks / 20.0F;
		// Model units walked, for stepping in time with the ground covered.
		float units = motion.distance / scale;

		RiggedModel.Sequence idle = model.find(IDLE);
		if (idle == null && model.sequences.length > 0) {
			idle = model.sequences[0];
		}
		if (idle != null) {
			model.pose(idle, seconds * idle.fps(), pose);
		} else {
			model.bindPose(pose);
			return;
		}
		RiggedModel.Sequence walk = model.find(WALK);
		RiggedModel.Sequence run = model.find(RUN);
		layer(model, pose, walk, units, seconds, motion.move);
		if (run != walk) {
			layer(model, pose, run, units, seconds, motion.move * motion.run);
		}

		RiggedModel.Sequence crouch = model.find(CROUCH);
		if (crouch != null && motion.crouch > 0.01F) {
			RiggedModel.Pose crouched = new RiggedModel.Pose(model);
			model.pose(crouch, seconds * crouch.fps(), crouched);
			layer(model, crouched, model.find(CROUCH_WALK), units, seconds, motion.move);
			model.blend(pose, crouched, motion.crouch);
		}

		RiggedModel.Sequence air = model.find(AIR);
		if (air != null && motion.air > 0.01F) {
			RiggedModel.Pose jumping = new RiggedModel.Pose(model);
			float frame = air.looping() ? seconds * air.fps() : Math.min(motion.airTime * air.fps(), air.frameCount() - 1);
			model.pose(air, frame, jumping);
			model.blend(pose, jumping, motion.air);
		}

		RiggedModel.Sequence sit = model.find(SIT);
		if (sit != null && motion.sit > 0.01F) {
			RiggedModel.Pose sitting = new RiggedModel.Pose(model);
			model.pose(sit, sit.looping() ? seconds * sit.fps() : 0.0F, sitting);
			model.blend(pose, sitting, motion.sit);
		}

		// A hit swings the upper body only, so the legs keep walking.
		RiggedModel.Sequence swing = model.find(SWING);
		Bones rig = BONES.computeIfAbsent(model, SourcePlayerRenderer::findBones);
		if (swing != null && rig.upper() != null && state.swingAnimation > 0.0F) {
			RiggedModel.Pose swinging = new RiggedModel.Pose(model);
			model.pose(swing, state.swingAnimation * (swing.frameCount() - 1), swinging);
			model.blend(pose, swinging, Mth.clamp(Mth.sin(state.swingAnimation * Mth.PI) * 3.0F, 0.0F, 1.0F), rig.upper());
		}
	}

	/** Blends a moving animation into the pose, its cycle following the ground covered. */
	private static void layer(RiggedModel model, RiggedModel.Pose pose, RiggedModel.Sequence sequence, float units, float seconds,
			float weight) {
		if (sequence == null || weight <= 0.01F) {
			return;
		}
		float frame = sequence.distance() > 1.0F ? units / sequence.distance() * sequence.frameCount() : seconds * sequence.fps();
		RiggedModel.Pose moving = new RiggedModel.Pose(model);
		model.pose(sequence, frame, moving);
		model.blend(pose, moving, weight);
	}

	private static Bones findBones(RiggedModel model) {
		int neck = -1;
		int head = -1;
		int spine = -1;
		int spineRank = Integer.MAX_VALUE;
		for (int b = 0; b < model.boneCount(); b++) {
			String name = model.boneNames[b].toLowerCase(Locale.ROOT);
			if (neck < 0 && name.contains("neck")) {
				neck = b;
			}
			if (head < 0 && name.contains("head") && !name.contains("forehead")) {
				head = b;
			}
			// The upper spine carries the arms; any spine bone will do.
			int rank = name.contains("spine2") ? 0 : name.contains("spine1") ? 1 : name.contains("spine") ? 2 : Integer.MAX_VALUE;
			if (rank < spineRank) {
				spineRank = rank;
				spine = b;
			}
		}
		boolean[] upper = spine < 0 ? null : below(model, spine);
		return new Bones(neck, head, upper);
	}

	/** Marks a bone and every bone hanging from it. */
	private static boolean[] below(RiggedModel model, int root) {
		boolean[] marked = new boolean[model.boneCount()];
		marked[root] = true;
		// Parents always come before their children.
		for (int b = root + 1; b < marked.length; b++) {
			int parent = model.parents[b];
			marked[b] = parent >= 0 && parent < b && marked[parent];
		}
		return marked;
	}

	/** Turns the head (and a little of the neck) to where the player looks. */
	private static void look(RiggedModel model, Bones rig, Matrix4f[] bones, AvatarRenderState state) {
		if (rig.head() < 0 || state.isFallFlying || state.isVisuallySwimming) {
			return;
		}
		// Right is a negative turn about Half-Life's up axis; down is positive about its left axis.
		float yaw = -Mth.clamp(state.yRot, -75.0F, 75.0F) * Mth.DEG_TO_RAD;
		float pitch = Mth.clamp(state.xRot, -60.0F, 60.0F) * Mth.DEG_TO_RAD;
		if (rig.neck() >= 0 && rig.neck() != rig.head()) {
			turn(model, bones, rig.neck(), yaw * 0.4F, pitch * 0.4F);
			turn(model, bones, rig.head(), yaw * 0.6F, pitch * 0.6F);
		} else {
			turn(model, bones, rig.head(), yaw, pitch);
		}
	}

	/** Turns a bone and everything hanging from it about the bone's posed joint, in model space. */
	private static void turn(RiggedModel model, Matrix4f[] bones, int bone, float yaw, float pitch) {
		Vector3f joint = new Matrix4f(model.inverseBind[bone]).invert().getTranslation(new Vector3f());
		bones[bone].transformPosition(joint);
		Matrix4f rotation = new Matrix4f().translation(joint).rotateZ(yaw).rotateY(pitch).translate(-joint.x, -joint.y, -joint.z);
		boolean[] moved = below(model, bone);
		for (int b = 0; b < moved.length; b++) {
			if (moved[b]) {
				bones[b] = new Matrix4f(rotation).mul(bones[b]);
			}
		}
	}
}
