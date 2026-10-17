package com.mcsource.client.tree;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import com.mcsource.client.mob.SourceModel;
import com.mcsource.tree.TreeEntity;

/** Draws a tree entity with a Source tree model, at its real size give or take a little. */
public class TreeRenderer extends EntityRenderer<TreeEntity, TreeRenderer.State> {
	/** Source units per block. */
	private static final float UNITS = 1.0F / 40.0F;
	/** Trees taller than this many blocks are scaled down to it. */
	private static final float MAX_HEIGHT = 20.0F;
	/** Beyond this many blocks a tree is drawn as two crossed pictures of itself. */
	private static final float IMPOSTOR = 40.0F;

	public static class State extends EntityRenderState {
		String wood = "";
		long seed;
		float yaw;
		/** How far it has fallen over, 0 standing to 1 on the ground. */
		float fall;
		float fallDirection;
	}

	public TreeRenderer(EntityRendererProvider.Context context) {
		super(context);
		// A soft patch of shade on the ground under the crown.
		shadowRadius = 2.5F;
		shadowStrength = 0.55F;
	}

	// Lit by the air well above the foot, which sits sunk into the ground.
	@Override
	protected int getSkyLightLevel(TreeEntity tree, BlockPos pos) {
		return super.getSkyLightLevel(tree, pos.above(2));
	}

	@Override
	protected int getBlockLightLevel(TreeEntity tree, BlockPos pos) {
		return super.getBlockLightLevel(tree, pos.above(2));
	}

	@Override
	protected float getShadowRadius(State state) {
		return state.fall > 0.0F ? 0.0F : super.getShadowRadius(state);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(TreeEntity tree, State state, float partialTicks) {
		super.extractRenderState(tree, state, partialTicks);
		state.wood = tree.wood();
		state.seed = tree.blockPosition().asLong() * 0x9E3779B97F4A7C15L;
		state.yaw = tree.getYRot();
		int falling = tree.falling();
		if (falling < 0) {
			state.fall = 0.0F;
		} else {
			float t = Math.min(1.0F, (falling + partialTicks) / TreeEntity.FALL_TICKS);
			// Slow to start, then faster as it goes, like a falling tree.
			state.fall = t * t;
		}
		state.fallDirection = tree.fallDirection();
	}

	@Override
	protected AABB getBoundingBoxForCulling(TreeEntity tree, float partialTicks) {
		// The crown reaches far past the trunk the entity's box covers.
		if (tree.falling() >= 0) {
			// Falling over, it reaches out as far as it is tall.
			return tree.getBoundingBox().inflate(MAX_HEIGHT, 0.0, MAX_HEIGHT).expandTowards(0.0, MAX_HEIGHT, 0.0);
		}
		return tree.getBoundingBox().inflate(8.0, 0.0, 8.0).expandTowards(0.0, MAX_HEIGHT, 0.0);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		SourceModel model = TreeModels.pick(state.wood, state.seed >>> 8);
		if (model == null) {
			return;
		}
		float size = 0.8F + 0.3F * ((state.seed >>> 40 & 0xFF) / 255.0F);
		float scale = UNITS * size;
		if (model.height * scale > MAX_HEIGHT) {
			scale = MAX_HEIGHT / model.height;
		}
		float drawScale = scale;
		poseStack.pushPose();
		// Sink the foot a little so it meets ground that smoothing lowered.
		poseStack.translate(0.0F, -0.4F, 0.0F);
		if (state.fall > 0.0F) {
			// Topple about the foot of the trunk, away from whoever felled it.
			poseStack.rotateDegrees(Axis.YP, -state.fallDirection);
			poseStack.rotateDegrees(Axis.XP, 88.0F * state.fall);
			poseStack.rotateDegrees(Axis.YP, state.fallDirection);
		}
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		int light = state.lightCoords;
		float distance = (float) Math.sqrt(state.distanceToCameraSq);
		if (distance > IMPOSTOR && state.fall <= 0.0F) {
			// Far away: two crossed pictures of the tree instead of its triangles.
			SourceModel.Impostor picture = model.impostor();
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(picture.front()), (pose, consumer) -> card(pose, consumer,
					picture.frontMin() * drawScale, picture.frontMax() * drawScale, picture.bottom() * drawScale, picture.top() * drawScale, false,
					light));
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(picture.side()), (pose, consumer) -> card(pose, consumer,
					picture.sideMin() * drawScale, picture.sideMax() * drawScale, picture.bottom() * drawScale, picture.top() * drawScale, true,
					light));
		} else {
			// Near: the whole model, every leaf and branch, so nothing pops in.
			Identifier[] textures = model.materialTextures();
			for (int i = 0; i < textures.length; i++) {
				int material = i;
				collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(textures[i]),
						(pose, consumer) -> model.emitMaterial(pose, consumer, material, drawScale, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F));
			}
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/**
	 * One picture card, standing on the model's origin: across Minecraft's x
	 * axis for the front picture, or across z for the side one. Model x is
	 * Minecraft's z and model y is Minecraft's x, as in {@link SourceModel}.
	 */
	private static void card(PoseStack.Pose pose, VertexConsumer consumer, float min, float max, float bottom, float top, boolean side,
			int light) {
		float[][] corners = {{min, top, 0.0F, 0.0F}, {min, bottom, 0.0F, 1.0F}, {max, bottom, 1.0F, 1.0F}, {max, top, 1.0F, 0.0F}};
		for (int face = 0; face < 2; face++) {
			for (int k = 0; k < 4; k++) {
				float[] c = corners[face == 0 ? k : 3 - k];
				float x = side ? 0.0F : c[0];
				float z = side ? c[0] : 0.0F;
				consumer.addVertex(pose, x, c[1], z).setColor(-1).setUv(c[2], c[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
						.setNormal(pose, 0.0F, 1.0F, 0.0F);
			}
		}
	}
}
