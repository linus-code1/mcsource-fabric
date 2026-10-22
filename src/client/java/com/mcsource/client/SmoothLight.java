package com.mcsource.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

import com.mcsource.client.light.SourceLight;

/**
 * Light at any point, blended smoothly between blocks. Each corner where
 * eight blocks meet takes the brightest open block among them, and a point
 * blends its eight nearest corners. Two faces meeting at an edge read the
 * same corners there, so the light runs on across the edge instead of
 * changing block by block, and it is never darker than any open block the
 * point touches (the renderer raises a face to its block's light, so
 * anything darker would show as squares).
 */
public final class SmoothLight {
	private static final class Cache {
		Object level;
		final Long2LongOpenHashMap corners = new Long2LongOpenHashMap();
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		Cache() {
			corners.defaultReturnValue(Long.MIN_VALUE);
		}
	}

	private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

	private SmoothLight() {
	}

	/** Block and sky light (0 to 15) at a world position, as {block, sky}. */
	public static float[] at(BlockAndTintGetter level, float x, float y, float z) {
		Cache cache = CACHE.get();
		if (cache.level != level) {
			cache.level = level;
			cache.corners.clear();
		}
		int x0 = Mth.floor(x);
		int y0 = Mth.floor(y);
		int z0 = Mth.floor(z);
		float fx = x - x0;
		float fy = y - y0;
		float fz = z - z0;
		float block = 0.0F;
		float sky = 0.0F;
		float weights = 0.0F;
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			long value = corner(level, cache, x0 + cx, y0 + cy, z0 + cz);
			if (value < 0) {
				continue;
			}
			float w = (cx == 1 ? fx : 1.0F - fx) * (cy == 1 ? fy : 1.0F - fy) * (cz == 1 ? fz : 1.0F - fz);
			block += w * (value >> 16 & 0xFFFF) / 16.0F;
			sky += w * (value & 0xFFFF) / 16.0F;
			weights += w;
		}
		if (weights < 1.0E-4F) {
			int light = LightCoordsUtil.getLightCoords(level, cache.cursor.set(x0, y0, z0));
			return new float[] {SourceLight.blockLevel(light), SourceLight.skyLevel(light)};
		}
		return new float[] {block / weights, sky / weights};
	}

	/** Packed light coordinates for {@link #at}'s result. */
	public static int pack(float[] light) {
		return LightCoordsUtil.smoothPack(Math.round(Mth.clamp(light[0], 0.0F, 15.0F) * 16.0F), Math.round(Mth.clamp(light[1], 0.0F, 15.0F) * 16.0F));
	}

	/**
	 * The brightest open block of the eight around a corner, as block light
	 * << 16 | sky light (each 0 to 240), or -1 when all eight are solid.
	 */
	private static long corner(BlockAndTintGetter level, Cache cache, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		long known = cache.corners.get(key);
		if (known != Long.MIN_VALUE) {
			return known;
		}
		float block = -1.0F;
		float sky = -1.0F;
		for (int dy = -1; dy <= 0; dy++) {
			for (int dz = -1; dz <= 0; dz++) {
				for (int dx = -1; dx <= 0; dx++) {
					cache.cursor.set(x + dx, y + dy, z + dz);
					if (level.getBlockState(cache.cursor).isSolidRender()) {
						continue;
					}
					int light = LightCoordsUtil.getLightCoords(level, cache.cursor);
					block = Math.max(block, SourceLight.blockLevel(light));
					sky = Math.max(sky, SourceLight.skyLevel(light));
				}
			}
		}
		long value = block < 0.0F ? -1L : (long) Math.round(block * 16.0F) << 16 | Math.round(sky * 16.0F);
		cache.corners.put(key, value);
		return value;
	}
}
