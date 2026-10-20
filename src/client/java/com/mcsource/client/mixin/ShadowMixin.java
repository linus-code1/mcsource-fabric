package com.mcsource.client.mixin;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mcsource.client.SmoothBlockModel;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Lays mob and item shadows on the smoothed ground instead of on the flat
 * block tops under it: each block's square of shadow is cut into a small
 * grid whose corners sit on the drawn ground, so the shadow follows slopes
 * instead of floating over them or sinking into them.
 */
@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowMixin {
	/** Each block's square of shadow is cut into this many pieces each way. */
	private static final int CUTS = 4;
	/** How far the shadow sits off the ground, so the two never fight. */
	private static final float LIFT = 0.015F;

	@Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
	private void mcsource$followGround(ShadowFeatureRenderer.Submit submit, VertexConsumer consumer, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		ci.cancel();
		Matrix4fc pose = submit.pose();
		// The pose carries the entity's place relative to the camera.
		Vector3f offset = pose.transformPosition(0.0F, 0.0F, 0.0F, new Vector3f());
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		double ex = camera.x + offset.x;
		double ey = camera.y + offset.y;
		double ez = camera.z + offset.z;
		float radius = submit.radius();
		int n = CUTS + 1;
		float[] xs = new float[n * n];
		float[] zs = new float[n * n];
		float[] heights = new float[n * n];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < n; k++) {
				xs[i * n + k] = i / (float) CUTS;
				zs[i * n + k] = k / (float) CUTS;
			}
		}
		for (EntityRenderState.ShadowPiece piece : submit.pieces()) {
			AABB bounds = piece.shapeBelow().bounds();
			int colour = ARGB.white(piece.alpha());
			float x0 = piece.relativeX() + (float) bounds.minX;
			float x1 = piece.relativeX() + (float) bounds.maxX;
			float z0 = piece.relativeZ() + (float) bounds.minZ;
			float z1 = piece.relativeZ() + (float) bounds.maxZ;
			float y = piece.relativeY() + (float) bounds.minY;
			BlockPos cell = BlockPos.containing(ex + piece.relativeX() + 0.5, ey + piece.relativeY() + 0.5, ez + piece.relativeZ() + 0.5);
			boolean bent = minecraft.level.getBlockState(cell.below()).is(SmoothTerrain.SMOOTH)
					|| minecraft.level.getBlockState(cell).is(SmoothTerrain.FOLLOWS);
			if (!bent) {
				quad(pose, consumer, colour, radius, x0, x1, z0, z1, y, y, y, y);
				continue;
			}
			SmoothBlockModel.groundHeights(minecraft.level, cell, xs, zs, heights);
			float base = cell.getY() - (float) ey + LIFT;
			for (int i = 0; i < CUTS; i++) {
				for (int k = 0; k < CUTS; k++) {
					float ax = Mth_lerp(i / (float) CUTS, x0, x1);
					float bx = Mth_lerp((i + 1) / (float) CUTS, x0, x1);
					float az = Mth_lerp(k / (float) CUTS, z0, z1);
					float bz = Mth_lerp((k + 1) / (float) CUTS, z0, z1);
					quad(pose, consumer, colour, radius, ax, bx, az, bz, base + heights[i * n + k], base + heights[i * n + k + 1],
							base + heights[(i + 1) * n + k + 1], base + heights[(i + 1) * n + k]);
				}
			}
		}
	}

	private static float Mth_lerp(float t, float a, float b) {
		return a + (b - a) * t;
	}

	/** One square of shadow, its corners (x0 z0) (x0 z1) (x1 z1) (x1 z0) at the heights given. */
	private static void quad(Matrix4fc pose, VertexConsumer consumer, int colour, float radius, float x0, float x1, float z0, float z1,
			float y00, float y01, float y11, float y10) {
		vertex(pose, consumer, colour, radius, x0, y00, z0);
		vertex(pose, consumer, colour, radius, x0, y01, z1);
		vertex(pose, consumer, colour, radius, x1, y11, z1);
		vertex(pose, consumer, colour, radius, x1, y10, z0);
	}

	private static void vertex(Matrix4fc pose, VertexConsumer consumer, int colour, float radius, float x, float y, float z) {
		Vector3f at = pose.transformPosition(x, y, z, new Vector3f());
		float u = -x / 2.0F / radius + 0.5F;
		float v = -z / 2.0F / radius + 0.5F;
		consumer.addVertex(at.x(), at.y(), at.z(), colour, u, v, OverlayTexture.NO_OVERLAY, 15728880, 0.0F, 1.0F, 0.0F);
	}
}
