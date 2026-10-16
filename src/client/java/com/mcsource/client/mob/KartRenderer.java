package com.mcsource.client.mob;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;

import com.mcsource.kart.KartEntity;

/** Draws the kart with its rigged model, wheels turning with the distance driven. */
public class KartRenderer extends EntityRenderer<KartEntity, LivingEntityRenderState> {
	public KartRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.8F;
	}

	@Override
	public LivingEntityRenderState createRenderState() {
		return new LivingEntityRenderState();
	}

	@Override
	public void extractRenderState(KartEntity kart, LivingEntityRenderState state, float partialTicks) {
		super.extractRenderState(kart, state, partialTicks);
		state.bodyRot = Mth.rotLerp(partialTicks, kart.yBodyRotO, kart.yBodyRot);
		state.walkAnimationPos = Mth.lerp(partialTicks, kart.travelledO, kart.travelled) * SourceMobRenderer.WALK_STEPS_PER_BLOCK;
		state.walkAnimationSpeed = 1.0F;
		state.hasRedOverlay = kart.hurtTime > 0;
		state.deathTime = 0.0F;
		state.scale = 1.0F;
		state.ageScale = 1.0F;
	}

	@Override
	public void submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		SourceMobRenderer.submit(state, poseStack, collector);
		super.submit(state, poseStack, collector, camera);
	}
}
