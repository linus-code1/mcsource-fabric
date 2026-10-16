package com.mcsource.client;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
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
	public SmoothBlockModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest) {
		if (level != BlockAndTintGetter.EMPTY) {
			if (state.is(SmoothTerrain.SMOOTH)) {
				int[] classes = SmoothTerrain.meshNeighbourhood(level, pos, state);
				int[] offsets = classes == null ? null : SmoothTerrain.cornerOffsets(classes);
				if (offsets != null) {
					emitBent(emitter, level, pos, state, random, cullTest, classes, offsets);
					return;
				}
			} else if (state.is(SmoothTerrain.FOLLOWS)) {
				int ground = SmoothTerrain.groundCorners(level, pos);
				if (ground != SmoothMath.FULL) {
					// Each vertex rides the ground surface under it, so snow layers and
					// flower carpets bend over slopes instead of floating flat.
					emitter.pushTransform(quad -> {
						for (int i = 0; i < 4; i++) {
							float x = quad.x(i);
							float z = quad.z(i);
							float rise = SmoothMath.surface(ground, Mth.clamp(x, 0.0F, 1.0F), Mth.clamp(z, 0.0F, 1.0F)) - 1.0F;
							quad.pos(i, x, quad.y(i) + rise, z);
						}
						quad.ambientOcclusion(TriState.FALSE);
						return true;
					});
					try {
						super.emitQuads(emitter, level, pos, state, random, cullTest);
					} finally {
						emitter.popTransform();
					}
					return;
				}
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

	private void emitBent(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, int[] classes, int[] offsets) {
		float[] normals = SmoothTerrain.cornerNormals(classes);
		int[] light = new int[8];
		int[] shade = new int[8];
		cornerLighting(level, pos, classes, light, shade);

		emitter.pushTransform(quad -> {
			for (int i = 0; i < 4; i++) {
				float x = Mth.clamp(quad.x(i), 0.0F, 1.0F);
				float y = Mth.clamp(quad.y(i), 0.0F, 1.0F);
				float z = Mth.clamp(quad.z(i), 0.0F, 1.0F);
				int corner = (x > 0.5F ? 1 : 0) + (y > 0.5F ? 2 : 0) + (z > 0.5F ? 4 : 0);
				quad.pos(i, quad.x(i), quad.y(i) + SmoothMath.offsetAt(offsets, x, y, z), quad.z(i));
				quad.lightmap(i, LightCoordsUtil.max(quad.lightmap(i), light[corner]));
				quad.color(i, ARGB.multiply(quad.color(i), shade[corner]));
				float nx = normals[corner * 3];
				float ny = normals[corner * 3 + 1];
				float nz = normals[corner * 3 + 2];
				if (nx == 0 && ny == 0 && nz == 0) {
					Vector3fc face = quad.faceNormal();
					nx = face.x();
					ny = face.y();
					nz = face.z();
				}
				// Every vertex gets a normal: a quad missing one is shaded flat
				// from its face normal, which shows each facet.
				quad.normal(i, nx, ny, nz);
				int sun = SunLight.multiplier(level, pos.getX() + quad.x(i), pos.getY() + quad.y(i), pos.getZ() + quad.z(i), nx, ny, nz,
						LightCoordsUtil.sky(light[corner]), LightCoordsUtil.block(light[corner]));
				quad.color(i, ARGB.multiply(quad.color(i), sun));
			}
			// Vanilla ambient occlusion assumes faces lie on the block's sides and
			// darkens moved faces wrongly; light comes from cornerLighting instead.
			// Enhanced shading lights each vertex by its smooth normal, so slopes
			// shade evenly across block borders.
			quad.ambientOcclusion(TriState.FALSE);
			quad.shadeMode(ShadeMode.ENHANCED);
			splitAlongFlatterDiagonal(quad);
			return true;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
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
	private static void cornerLighting(BlockAndTintGetter level, BlockPos pos, int[] classes, int[] light, int[] shade) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[] cellLight = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (SmoothTerrain.isOpen(classes, dx, dy, dz)) {
						cellLight[dx + 1 + 3 * (dz + 1) + 9 * (dy + 1)] = LightCoordsUtil.getLightCoords(level, cursor.setWithOffset(pos, dx, dy, dz));
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
						if (SmoothTerrain.isOpen(classes, ox, oy, oz)) {
							int cell = cellLight[ox + 1 + 3 * (oz + 1) + 9 * (oy + 1)];
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
			light[corner] = count == 0 ? fallback : LightCoordsUtil.pack(blockMax, skyMax);
			int gray = Math.round(255 * (0.8F + 0.2F * Math.min(count, 4) / 4.0F));
			shade[corner] = ARGB.color(255, gray, gray, gray);
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
