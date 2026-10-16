package com.mcsource.client;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
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

import com.mcsource.terrain.SmoothMath;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Draws smoothed terrain by bending the block's own quads: each vertex moves up
 * or down with the grid corners around it, so textures, grass tint and overlays
 * all follow, and neighbouring blocks stay joined. Plants on top move with the
 * ground under them.
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
				int[] offsets = SmoothTerrain.cornerOffsets(level, pos, state);
				if (offsets != null) {
					int[] light = new int[8];
					int[] shade = new int[8];
					cornerLighting(level, pos, light, shade);
					emitter.pushTransform(quad -> {
						for (int i = 0; i < 4; i++) {
							float x = Mth.clamp(quad.x(i), 0.0F, 1.0F);
							float y = Mth.clamp(quad.y(i), 0.0F, 1.0F);
							float z = Mth.clamp(quad.z(i), 0.0F, 1.0F);
							int corner = (x > 0.5F ? 1 : 0) + (y > 0.5F ? 2 : 0) + (z > 0.5F ? 4 : 0);
							quad.pos(i, quad.x(i), quad.y(i) + SmoothMath.offsetAt(offsets, x, y, z), quad.z(i));
							quad.lightmap(i, LightCoordsUtil.max(quad.lightmap(i), light[corner]));
							quad.color(i, ARGB.multiply(quad.color(i), shade[corner]));
						}
						// Vanilla ambient occlusion assumes faces lie on the block's sides and
						// darkens moved faces wrongly; light comes from cornerLighting instead.
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
		super.emitQuads(emitter, level, pos, state, random, cullTest);
	}

	/**
	 * Smooth light for each of the block's eight corners: the average light of
	 * the open blocks around that corner, so moved faces never sample light from
	 * inside solid ground. Corners with less open space around them are shaded
	 * darker, standing in for ambient occlusion.
	 */
	private static void cornerLighting(BlockAndTintGetter level, BlockPos pos, int[] light, int[] shade) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		boolean[] open = new boolean[27];
		int[] cellLight = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int i = dx + 1 + 3 * (dz + 1) + 9 * (dy + 1);
					cursor.setWithOffset(pos, dx, dy, dz);
					open[i] = SmoothTerrain.isOpen(level.getBlockState(cursor));
					if (open[i]) {
						cellLight[i] = LightCoordsUtil.getLightCoords(level, cursor);
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
			int blockSum = 0;
			int skySum = 0;
			for (int oy = cy - 1; oy <= cy; oy++) {
				for (int oz = cz - 1; oz <= cz; oz++) {
					for (int ox = cx - 1; ox <= cx; ox++) {
						int i = ox + 1 + 3 * (oz + 1) + 9 * (oy + 1);
						if (open[i]) {
							count++;
							blockSum += LightCoordsUtil.block(cellLight[i]);
							skySum += LightCoordsUtil.sky(cellLight[i]);
						}
					}
				}
			}
			light[corner] = count == 0 ? fallback : LightCoordsUtil.smoothPack(blockSum * 16 / count, skySum * 16 / count);
			int gray = Math.round(255 * Mth.clamp(0.55F + 0.45F * count / 4.0F, 0.55F, 1.0F));
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
