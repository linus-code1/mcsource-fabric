package com.mcsource.client.mob;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

import com.mcsource.model.GoldSrcModel;

/**
 * Draws a mob with its rigged model: picks and blends the model's idle,
 * walk, attack and death animations from what the mob is doing, skins the
 * mesh to the posed bones, and submits the triangles.
 */
public final class SourceMobRenderer {
	/** Minecraft's walk animation advances about four steps per block walked. */
	public static final float WALK_STEPS_PER_BLOCK = 4.0F;

	private SourceMobRenderer() {
	}

	/** Draws the mob if it has a rig; returns false to draw it normally. */
	public static boolean submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		SourceRigs.Rig rig = SourceRigs.get(state.entityType);
		if (rig == null) {
			return false;
		}
		SourceModel loaded = SourceModel.get(rig.model());
		if (loaded == null) {
			return false;
		}
		if (state.isInvisible) {
			return true;
		}
		GoldSrcModel model = loaded.model;
		GoldSrcModel.Pose pose = new GoldSrcModel.Pose(model);
		animate(rig, model, state, pose);
		Matrix4f[] bones = new Matrix4f[model.bones.length];
		model.matrices(pose, bones);

		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.bodyRot);
		float scale = rig.scale() * state.scale * state.ageScale;
		int light = state.lightCoords;
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(loaded.texture), (matrices, consumer) -> {
			loaded.skin(bones);
			loaded.emit(matrices, consumer, SourceModel.Axes.HALF_LIFE, scale, light, overlay);
		});
		poseStack.popPose();
		return true;
	}

	private static void animate(SourceRigs.Rig rig, GoldSrcModel model, LivingEntityRenderState state, GoldSrcModel.Pose pose) {
		float seconds = state.ageInTicks / 20.0F;
		GoldSrcModel.Sequence death = model.find(rig.death());
		if (state.deathTime > 0.0F && death != null) {
			// Minecraft removes a dead mob after a second; play the fall in that time.
			model.pose(death, Math.min(state.deathTime / 20.0F, 1.0F) * (death.frameCount() - 1), pose);
			return;
		}
		GoldSrcModel.Sequence idle = model.find(rig.idle());
		if (idle == null) {
			idle = model.sequences[0];
		}
		model.pose(idle, seconds * idle.fps(), pose);

		GoldSrcModel.Sequence walk = model.find(rig.walk());
		if (walk != null) {
			float weight = Mth.clamp(state.walkAnimationSpeed * 1.5F, 0.0F, 1.0F);
			if (weight > 0.01F) {
				float frame;
				if (walk.distance() > 1.0F) {
					// Step in time with the ground covered, so feet don't slide.
					float units = state.walkAnimationPos / WALK_STEPS_PER_BLOCK / rig.scale();
					frame = units / walk.distance() * walk.frameCount();
				} else {
					frame = seconds * walk.fps();
				}
				GoldSrcModel.Pose walking = new GoldSrcModel.Pose(model);
				model.pose(walk, frame, walking);
				model.blend(pose, walking, weight);
			}
		}

		GoldSrcModel.Sequence attack = model.find(rig.attack());
		if (attack != null && state instanceof ArmedEntityRenderState armed && armed.swingAnimation > 0.0F) {
			GoldSrcModel.Pose attacking = new GoldSrcModel.Pose(model);
			model.pose(attack, armed.swingAnimation * (attack.frameCount() - 1), attacking);
			model.blend(pose, attacking, Mth.clamp(Mth.sin(armed.swingAnimation * Mth.PI) * 3.0F, 0.0F, 1.0F));
		}
	}
}
