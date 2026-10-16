package com.mcsource.client.mixin;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;

import com.mcsource.client.SunShadow;

/** Lays the shadow texture out as a long ellipse falling away from the sun. */
@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
	@Shadow
	private static void shadowVertex(Matrix4fc pose, VertexConsumer buffer, int color, float x, float y, float z, float u, float v) {
	}

	@Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
	private void mcsource$castFromSun(ShadowFeatureRenderer.Submit submit, VertexConsumer builder, CallbackInfo ci) {
		ci.cancel();
		float radius = submit.radius() / SunShadow.REACH;
		for (EntityRenderState.ShadowPiece piece : submit.pieces()) {
			AABB aabb = piece.shapeBelow().bounds();
			float x0 = piece.relativeX() + (float) aabb.minX;
			float x1 = piece.relativeX() + (float) aabb.maxX;
			float y = piece.relativeY() + (float) aabb.minY;
			float z0 = piece.relativeZ() + (float) aabb.minZ;
			float z1 = piece.relativeZ() + (float) aabb.maxZ;
			int color = ARGB.white(Math.min(1.0F, piece.alpha() * SunShadow.DARKEN));
			vertex(submit, builder, color, radius, x0, y, z0);
			vertex(submit, builder, color, radius, x0, y, z1);
			vertex(submit, builder, color, radius, x1, y, z1);
			vertex(submit, builder, color, radius, x1, y, z0);
		}
	}

	private static void vertex(ShadowFeatureRenderer.Submit submit, VertexConsumer builder, int color, float radius, float x, float y, float z) {
		shadowVertex(submit.pose(), builder, color, x, y, z, SunShadow.u(x, z, radius), SunShadow.v(x, z, radius));
	}
}
