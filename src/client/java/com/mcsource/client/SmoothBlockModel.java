package com.mcsource.client;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3fc;

import com.mcsource.terrain.SmoothMath;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Draws smoothed terrain by bending the block's own quads: each vertex moves up
 * or down with the grid corners around it, so textures, grass tint and overlays
 * all follow, and neighbouring blocks stay joined. Plants and snow on top bend
 * with the ground under them.
 */
public final class SmoothBlockModel extends WrapperBlockStateModel {
	/** How far a thin snow sheet sits above the ground. */
	private static final float SNOW_LIFT = 0.02F;

	public SmoothBlockModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest) {
		if (level != BlockAndTintGetter.EMPTY) {
			if (state.is(SmoothTerrain.SMOOTH)) {
				int[] wide = SmoothTerrain.wideNeighbourhood(level, pos, state);
				float[] grid = wide == null ? null : grid(wide);
				if (grid != null) {
					emitBent(emitter, level, pos, state, random, cullTest, wide, grid);
					return;
				}
			} else if (state.is(SmoothTerrain.FOLLOWS)) {
				int ground = SmoothTerrain.groundCorners(level, pos);
				boolean thinSnow = state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == 1;
				// Plants always go this way, even on flat ground, so they are lit
				// evenly as Source lights its grass sprites.
				emitFollowing(emitter, level, pos, state, random, cullTest, ground, thinSnow);
				return;
			}
		}
		if (level == BlockAndTintGetter.EMPTY) {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
			return;
		}
		emitter.pushTransform(quad -> {
			applySun(quad, level, pos);
			return true;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	/**
	 * Plants and snow on top of smoothed ground: each vertex rides the ground
	 * surface under it, so they bend over slopes instead of floating flat. A
	 * single snow layer is drawn as a thin sheet lying on the ground, without
	 * the little walls round its edge.
	 */
	private void emitFollowing(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, int ground, boolean thinSnow) {
		int lightHere = LightCoordsUtil.getLightCoords(level, pos);
		emitter.pushTransform(quad -> {
			if (thinSnow && quad.faceNormal().y() < 0.5F) {
				return false;
			}
			for (int i = 0; i < 4; i++) {
				float x = quad.x(i);
				float z = quad.z(i);
				float groundY = SmoothMath.surface(ground, Mth.clamp(x, 0.0F, 1.0F), Mth.clamp(z, 0.0F, 1.0F)) - 1.0F;
				float y = thinSnow ? groundY + SNOW_LIFT : quad.y(i) + groundY;
				quad.pos(i, x, y, z);
				// Plants and snow take the sun as if facing up, like the ground.
				int sun = SunLight.multiplier(level, pos.getX() + x, pos.getY() + y, pos.getZ() + z, 0.0F, 1.0F, 0.0F,
						LightCoordsUtil.sky(lightHere), LightCoordsUtil.block(lightHere));
				quad.color(i, ARGB.multiply(quad.color(i), sun));
			}
			quad.ambientOcclusion(TriState.FALSE);
			if (!state.is(Blocks.SNOW)) {
				// Every blade lit as if facing up, whichever way its quad turns;
				// otherwise the crossed quads of one tuft come out light and dark.
				quad.shadeDirectionOverride(Direction.UP);
				for (int i = 0; i < 4; i++) {
					quad.normal(i, 0.0F, 1.0F, 0.0F);
				}
			}
			return true;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	/** Sun and shadow for an ordinary block face, lit by the cell it faces. */
	private static void applySun(MutableQuadView quad, BlockAndTintGetter level, BlockPos pos) {
		Vector3fc normal = quad.faceNormal();
		Direction face = quad.lightFace();
		int light = LightCoordsUtil.getLightCoords(level, quad.cullFace() == null && !touchesSide(quad, face) ? pos : pos.relative(face));
		int sky = LightCoordsUtil.sky(light);
		if (sky == 0) {
			return;
		}
		int block = LightCoordsUtil.block(light);
		for (int i = 0; i < 4; i++) {
			int sun = SunLight.multiplier(level, pos.getX() + quad.x(i), pos.getY() + quad.y(i), pos.getZ() + quad.z(i),
					normal.x(), normal.y(), normal.z(), sky, block);
			quad.color(i, ARGB.multiply(quad.color(i), sun));
		}
	}

	/** Whether a quad lies on the block's outer side in the given direction. */
	private static boolean touchesSide(MutableQuadView quad, Direction face) {
		float edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
		for (int i = 0; i < 4; i++) {
			if (Math.abs(quad.posByIndex(i, face.getAxis().ordinal()) - edge) > 1.0E-3F) {
				return false;
			}
		}
		return true;
	}

	/** Each side of a bent face is cut into this many pieces, so the surface can curve. */
	private static final int PIECES = 3;
	private static final ThreadLocal<MutableMesh> CAPTURED = ThreadLocal.withInitial(() -> Renderer.get().mutableMesh());

	/** Index into a 4x4x4 grid of corners from -1 to 2 on each axis. */
	private static int node(int gx, int gy, int gz) {
		return gx + 1 + 4 * (gz + 1) + 16 * (gy + 1);
	}

	/**
	 * Offsets in blocks of the 4x4x4 grid corners around a block, from -1 to
	 * 2 on each axis, or null when none of them moves.
	 */
	private static float[] grid(int[] wide) {
		float[] grid = new float[64];
		boolean moved = false;
		for (int gy = -1; gy <= 2; gy++) {
			for (int gz = -1; gz <= 2; gz++) {
				for (int gx = -1; gx <= 2; gx++) {
					int offset = SmoothTerrain.wideCornerOffset(wide, gx, gy, gz);
					grid[node(gx, gy, gz)] = offset / 8.0F;
					moved |= offset != 0;
				}
			}
		}
		return moved ? grid : null;
	}

	/** Catmull-Rom weights for the four nodes around a point {@code t} (0 to 1) between the middle two. */
	private static void weights(float t, float[] w) {
		float t2 = t * t;
		float t3 = t2 * t;
		w[0] = 0.5F * (-t + 2.0F * t2 - t3);
		w[1] = 0.5F * (2.0F - 5.0F * t2 + 3.0F * t3);
		w[2] = 0.5F * (t + 4.0F * t2 - 3.0F * t3);
		w[3] = 0.5F * (-t2 + t3);
	}

	/**
	 * The surface offset at a point in the block: a smooth curve through the
	 * corner offsets (which collision follows exactly), so ramps round off
	 * into the flat ground at their top and foot instead of creasing.
	 */
	private static float smoothOffset(float[] grid, float x, float y, float z) {
		float[] wx = new float[4];
		float[] wy = new float[4];
		float[] wz = new float[4];
		weights(x, wx);
		weights(y, wy);
		weights(z, wz);
		float sum = 0.0F;
		for (int j = 0; j < 4; j++) {
			for (int k = 0; k < 4; k++) {
				float wyz = wy[j] * wz[k];
				for (int i = 0; i < 4; i++) {
					sum += wx[i] * wyz * grid[i + 4 * k + 16 * j];
				}
			}
		}
		return Mth.clamp(sum, -0.5F, 0.5F);
	}

	private void emitBent(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, int[] wide, float[] grid) {
		float[] normals = smoothNormals(wide);
		int[] classes = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					classes[dx + 1 + 3 * (dz + 1) + 9 * (dy + 1)] = SmoothTerrain.isOpenWide(wide, dx, dy, dz) ? 1 : 0;
				}
			}
		}
		float[] blockLight = new float[8];
		float[] skyLight = new float[8];
		float[] shade = new float[8];
		cornerLighting(level, pos, classes, blockLight, skyLight, shade);

		// Collect the block's own quads, then draw each cut into pieces that
		// follow the curved surface.
		MutableMesh captured = CAPTURED.get();
		captured.clear();
		QuadEmitter capture = captured.emitter();
		emitter.pushTransform(quad -> {
			capture.copyFrom(quad);
			capture.emit();
			return false;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] pu = new float[4];
		float[] pv = new float[4];
		int[] pc = new int[4];
		int[] pl = new int[4];
		captured.forEachMutable(quad -> {
			for (int i = 0; i < 4; i++) {
				px[i] = quad.x(i);
				py[i] = quad.y(i);
				pz[i] = quad.z(i);
				pu[i] = quad.u(i);
				pv[i] = quad.v(i);
				pc[i] = quad.color(i);
				pl[i] = quad.lightmap(i);
			}
			Vector3fc face = quad.faceNormal();
			float fx = face.x();
			float fy = face.y();
			float fz = face.z();
			for (int a = 0; a < PIECES; a++) {
				for (int b = 0; b < PIECES; b++) {
					emitter.copyFrom(quad);
					for (int i = 0; i < 4; i++) {
						// Corners in order (0,0) (0,1) (1,1) (1,0), as vertices 0 to 3 run.
						float s = (a + (i >= 2 ? 1 : 0)) / (float) PIECES;
						float t = (b + (i == 1 || i == 2 ? 1 : 0)) / (float) PIECES;
						float w0 = (1 - s) * (1 - t);
						float w1 = (1 - s) * t;
						float w2 = s * t;
						float w3 = s * (1 - t);
						float x = px[0] * w0 + px[1] * w1 + px[2] * w2 + px[3] * w3;
						float y = py[0] * w0 + py[1] * w1 + py[2] * w2 + py[3] * w3;
						float z = pz[0] * w0 + pz[1] * w1 + pz[2] * w2 + pz[3] * w3;
						float lx = Mth.clamp(x, 0.0F, 1.0F);
						float ly = Mth.clamp(y, 0.0F, 1.0F);
						float lz = Mth.clamp(z, 0.0F, 1.0F);
						float bentY = y + smoothOffset(grid, lx, ly, lz);
						emitter.pos(i, x, bentY, z);
						emitter.uv(i, pu[0] * w0 + pu[1] * w1 + pu[2] * w2 + pu[3] * w3, pv[0] * w0 + pv[1] * w1 + pv[2] * w2 + pv[3] * w3);

						float[] n = new float[3];
						trilinear(normals, 3, lx, ly, lz, n);
						float length = Mth.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
						if (length < 1.0E-3F) {
							n[0] = fx;
							n[1] = fy;
							n[2] = fz;
						} else {
							n[0] /= length;
							n[1] /= length;
							n[2] /= length;
						}
						// Every vertex gets a normal: a quad missing one is shaded flat
						// from its face normal, which shows each facet.
						emitter.normal(i, n[0], n[1], n[2]);

						// Light, shade and sun blended from the corners, so they change
						// smoothly across the face instead of in steps.
						float[] one = new float[1];
						trilinear(blockLight, 1, lx, ly, lz, one);
						float block = one[0];
						trilinear(skyLight, 1, lx, ly, lz, one);
						float sky = one[0];
						trilinear(shade, 1, lx, ly, lz, one);
						int grey = Math.round(255.0F * one[0]);
						int original = blend(pl, w0, w1, w2, w3);
						int light = Math.max(Math.round(block * 16.0F), original & 0xFFFF)
								| Math.max(Math.round(sky * 16.0F), original >>> 16 & 0xFFFF) << 16;
						emitter.lightmap(i, light);
						int color = blendColor(pc, w0, w1, w2, w3);
						color = ARGB.multiply(color, ARGB.color(255, grey, grey, grey));
						int sun = SunLight.multiplier(level, pos.getX() + x, pos.getY() + bentY, pos.getZ() + z, n[0], n[1], n[2], sky, block);
						emitter.color(i, ARGB.multiply(color, sun));
					}
					// Vanilla ambient occlusion assumes faces lie on the block's sides and
					// darkens moved faces wrongly; light comes from cornerLighting instead.
					// Enhanced shading lights each vertex by its smooth normal, so slopes
					// shade evenly across block borders.
					emitter.ambientOcclusion(TriState.FALSE);
					emitter.shadeMode(ShadeMode.ENHANCED);
					splitAlongFlatterDiagonal(emitter);
					emitter.emit();
				}
			}
		});
		captured.clear();
	}

	/** Packed light blended per channel. */
	private static int blend(int[] light, float w0, float w1, float w2, float w3) {
		float block = (light[0] & 0xFFFF) * w0 + (light[1] & 0xFFFF) * w1 + (light[2] & 0xFFFF) * w2 + (light[3] & 0xFFFF) * w3;
		float sky = (light[0] >>> 16) * w0 + (light[1] >>> 16) * w1 + (light[2] >>> 16) * w2 + (light[3] >>> 16) * w3;
		return Math.round(block) | Math.round(sky) << 16;
	}

	private static int blendColor(int[] color, float w0, float w1, float w2, float w3) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			float c = (color[0] >>> shift & 0xFF) * w0 + (color[1] >>> shift & 0xFF) * w1 + (color[2] >>> shift & 0xFF) * w2
					+ (color[3] >>> shift & 0xFF) * w3;
			out |= Math.min(255, Math.round(c)) << shift;
		}
		return out;
	}

	/** Trilinear blend of per-corner values ({@code size} each, corner index cx + 2 * cy + 4 * cz). */
	private static void trilinear(float[] values, int size, float x, float y, float z, float[] out) {
		for (int k = 0; k < size; k++) {
			out[k] = 0.0F;
		}
		for (int corner = 0; corner < 8; corner++) {
			float w = ((corner & 1) != 0 ? x : 1 - x) * ((corner & 2) != 0 ? y : 1 - y) * ((corner & 4) != 0 ? z : 1 - z);
			for (int k = 0; k < size; k++) {
				out[k] += values[corner * size + k] * w;
			}
		}
	}

	/**
	 * Surface normals at the block's eight corners, each blended from the
	 * corner and the 26 around it, so shading turns gently over the terrain
	 * instead of in the eight directions one corner alone can give.
	 */
	private static float[] smoothNormals(int[] wide) {
		float[][] raw = new float[64][3];
		for (int gy = -1; gy <= 2; gy++) {
			for (int gz = -1; gz <= 2; gz++) {
				for (int gx = -1; gx <= 2; gx++) {
					SmoothTerrain.wideCornerGradient(wide, gx, gy, gz, raw[node(gx, gy, gz)]);
				}
			}
		}
		float[] out = new float[24];
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			float x = 0;
			float y = 0;
			float z = 0;
			for (int oy = -1; oy <= 1; oy++) {
				for (int oz = -1; oz <= 1; oz++) {
					for (int ox = -1; ox <= 1; ox++) {
						float w = (ox == 0 ? 2 : 1) * (oy == 0 ? 2 : 1) * (oz == 0 ? 2 : 1);
						float[] g = raw[node(cx + ox, cy + oy, cz + oz)];
						x += g[0] * w;
						y += g[1] * w;
						z += g[2] * w;
					}
				}
			}
			float length = Mth.sqrt(x * x + y * y + z * z);
			if (length > 1.0E-4F) {
				out[corner * 3] = x / length;
				out[corner * 3 + 1] = y / length;
				out[corner * 3 + 2] = z / length;
			}
		}
		return out;
	}

	/**
	 * A bent quad is drawn as two triangles split along the 0-2 diagonal. When
	 * the other diagonal is flatter, rotate the vertices so the fold runs along
	 * it; that keeps ridges and valleys following the terrain and avoids
	 * stretched creases across the texture.
	 */
	private static void splitAlongFlatterDiagonal(MutableQuadView quad) {
		float diagonal02 = Math.abs(quad.y(0) - quad.y(2));
		float diagonal13 = Math.abs(quad.y(1) - quad.y(3));
		if (diagonal02 <= diagonal13 + 1.0E-4F) {
			return;
		}
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] u = new float[4];
		float[] v = new float[4];
		int[] color = new int[4];
		int[] lightmap = new int[4];
		float[] nx = new float[4];
		float[] ny = new float[4];
		float[] nz = new float[4];
		boolean[] hasNormal = new boolean[4];
		for (int i = 0; i < 4; i++) {
			px[i] = quad.x(i);
			py[i] = quad.y(i);
			pz[i] = quad.z(i);
			u[i] = quad.u(i);
			v[i] = quad.v(i);
			color[i] = quad.color(i);
			lightmap[i] = quad.lightmap(i);
			hasNormal[i] = quad.hasNormal(i);
			nx[i] = quad.normalX(i);
			ny[i] = quad.normalY(i);
			nz[i] = quad.normalZ(i);
		}
		for (int i = 0; i < 4; i++) {
			int from = (i + 1) & 3;
			quad.pos(i, px[from], py[from], pz[from]);
			quad.uv(i, u[from], v[from]);
			quad.color(i, color[from]);
			quad.lightmap(i, lightmap[from]);
			if (hasNormal[from]) {
				quad.normal(i, nx[from], ny[from], nz[from]);
			}
		}
	}

	/**
	 * Smooth light for each of the block's eight corners: the light of the
	 * brightest open block around that corner, so moved faces never sample light from
	 * inside solid ground. Corners with less open space around them are shaded
	 * slightly darker, standing in for ambient occlusion.
	 */
	private static void cornerLighting(BlockAndTintGetter level, BlockPos pos, int[] open, float[] blockLight, float[] skyLight,
			float[] shade) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[] cellLight = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int index = dx + 1 + 3 * (dz + 1) + 9 * (dy + 1);
					if (open[index] != 0) {
						cellLight[index] = LightCoordsUtil.getLightCoords(level, cursor.setWithOffset(pos, dx, dy, dz));
					}
				}
			}
		}
		int fallback = LightCoordsUtil.getLightCoords(level, cursor.setWithOffset(pos, 0, 1, 0));
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			int count = 0;
			int blockMax = 0;
			int skyMax = 0;
			for (int oy = cy - 1; oy <= cy; oy++) {
				for (int oz = cz - 1; oz <= cz; oz++) {
					for (int ox = cx - 1; ox <= cx; ox++) {
						int index = ox + 1 + 3 * (oz + 1) + 9 * (oy + 1);
						if (open[index] != 0) {
							int cell = cellLight[index];
							count++;
							blockMax = Math.max(blockMax, LightCoordsUtil.block(cell));
							skyMax = Math.max(skyMax, LightCoordsUtil.sky(cell));
						}
					}
				}
			}
			// The brightest open cell, not the average: the renderer never lets a
			// face be darker than the cell it faces, so an average would be raised
			// on some faces and not others, and the facets would show.
			if (count == 0) {
				blockMax = LightCoordsUtil.block(fallback);
				skyMax = LightCoordsUtil.sky(fallback);
			}
			blockLight[corner] = blockMax;
			skyLight[corner] = skyMax;
			shade[corner] = 0.8F + 0.2F * Math.min(count, 4) / 4.0F;
		}
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		// Smoothed geometry depends on the neighbours, so it must not be shared.
		if (state.is(SmoothTerrain.SMOOTH) || state.is(SmoothTerrain.FOLLOWS)) {
			return null;
		}
		return super.createGeometryKey(level, pos, state, random);
	}
}
