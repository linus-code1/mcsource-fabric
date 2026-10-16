package com.mcsource.client.tree;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
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
	/** Distances in blocks where trees switch to fewer leaves. */
	private static final float NEAR = 24.0F;
	private static final float FAR = 56.0F;

	public static class State extends EntityRenderState {
		String wood = "";
		long seed;
		float yaw;
	}

	public TreeRenderer(EntityRendererProvider.Context context) {
		super(context);
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
	}

	@Override
	protected AABB getBoundingBoxForCulling(TreeEntity tree, float partialTicks) {
		// The crown reaches far past the trunk the entity's box covers.
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
		poseStack.rotateDegrees(Axis.YP, -state.yaw);
		Identifier[] textures = model.materialTextures();
		int light = state.lightCoords;
		// Level of detail: further trees drop their thinnest branches, and the
		// furthest draw fewer, bigger leaf cards. Leaves are kept in full at
		// middle distance, or the crowns look bare.
		float distance = (float) Math.sqrt(state.distanceToCameraSq);
		float keep = distance < NEAR ? 1.0F : distance < FAR ? 0.4F : 0.15F;
		float leafKeep = distance < FAR ? 1.0F : 0.45F;
		float grow = leafKeep < 1.0F ? 1.0F / (float) Math.sqrt(leafKeep) : 1.0F;
		for (int i = 0; i < textures.length; i++) {
			int material = i;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(textures[i]),
					(pose, consumer) -> model.emitMaterial(pose, consumer, material, drawScale, light, OverlayTexture.NO_OVERLAY,
							model.cutout(material) ? leafKeep : keep, grow));
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
