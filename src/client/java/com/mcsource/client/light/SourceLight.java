package com.mcsource.client.light;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Light worked out the way a Source map's compiler lights a level, in place
 * of Minecraft's light levels, for drawing only (mob spawning and plant
 * growth still go by Minecraft's light):
 * <ul>
 * <li>Sky light is how much of the sky can be seen from a point, found by
 * looking out along a spread of directions, plus light bounced back off the
 * ground and walls those looks hit. Minecraft's sky light only knows whether
 * a column is open, so it lit a cave mouth and an open field the same.</li>
 * <li>Lamp light is added up from the lamps themselves, falling off with
 * distance as real light does and stopped by walls in the way, so it spreads
 * in a ball with shadows instead of a diamond of steps that seeps round
 * corners. A little of Minecraft's flooded light is kept as the light that
 * bounces round corners.</li>
 * </ul>
 * The results go out as fine-grained light coordinates, which the lightmap
 * turns into colour.
 */
public final class SourceLight {
	/** Looks out at the sky, spread evenly over the dome as light falls on flat ground. */
	private static final float[][] SKY_RAYS;
	private static final float SKY_REACH = 12.0F;
	private static final float STEP = 0.5F;
	/** How much of the light falling on a surface it sends back, as a share of its colour's brightness. */
	private static final float BOUNCE = 0.45F;
	private static final float LEAF_PASS = 0.55F;
	private static final float WATER_PASS = 0.8F;
	/** How fast lamp light falls off: 1 / (1 + FALLOFF * distance squared). */
	private static final float FALLOFF = 0.045F;
	/**
	 * How much further than its Minecraft light level a lamp's light carries,
	 * fading to nothing, so pools of light trail off instead of ending in a
	 * hard ring.
	 */
	private static final float TAIL = 7.0F;
	/** The furthest any lamp's light reaches. */
	private static final int LAMP_REACH = 15 + (int) TAIL;
	/** Share of Minecraft's flooded lamp light kept as light bounced round corners. */
	private static final float LAMP_BOUNCE = 0.4F;
	private static final int MAX_LAMPS = 24;

	private static final ThreadLocal<Cache> REGION_CACHE = ThreadLocal.withInitial(Cache::new);
	private static final Cache LEVEL_CACHE = new Cache();
	private static volatile int generation;
	/** Test aid: -Dmcsource.vanillaLight=true draws with Minecraft's own light, for comparing. */
	private static final boolean OFF = Boolean.getBoolean("mcsource.vanillaLight");

	static {
		int count = 14;
		SKY_RAYS = new float[count][];
		float golden = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));
		for (int i = 0; i < count; i++) {
			float u = (i + 0.5F) / count;
			float r = Mth.sqrt(u);
			float angle = i * golden;
			SKY_RAYS[i] = new float[] {r * Mth.cos(angle), Mth.sqrt(1.0F - u), r * Mth.sin(angle)};
		}
	}

	private SourceLight() {
	}

	private static final class Cache {
		Object level;
		int generation;
		long time;
		/** Light coordinates in the low half, the bounce colour (RGB) in the high half. */
		final Long2LongOpenHashMap values = new Long2LongOpenHashMap();

		Cache() {
			values.defaultReturnValue(-1L);
		}
	}

	/** Drops remembered light, after lamps change or the held light moves. */
	public static void forget() {
		generation++;
	}

	/**
	 * The light to draw with at {@code pos}, in place of Minecraft's
	 * {@code vanilla} light coordinates there.
	 */
	public static int at(BlockAndLightGetter level, BlockState state, BlockPos pos, int vanilla) {
		if (OFF || level == null || state.emissiveRendering() || state.isSolidRender()) {
			return vanilla;
		}
		return (int) lookup(level, pos);
	}

	/**
	 * The colour light bounced onto a point takes from what it bounced off
	 * (green under leaves, warm beside sand), as opaque RGB at full strength:
	 * white where only the open sky lights it.
	 */
	public static int tint(BlockAndLightGetter level, BlockPos pos) {
		if (OFF || level == null || level.getBlockState(pos).isSolidRender()) {
			return 0xFFFFFFFF;
		}
		return 0xFF000000 | (int) (lookup(level, pos) >>> 32);
	}

	private static long lookup(BlockAndLightGetter level, BlockPos pos) {
		long key = pos.asLong();
		if (level instanceof ClientLevel client) {
			synchronized (LEVEL_CACHE) {
				Cache cache = LEVEL_CACHE;
				long time = client.getGameTime();
				if (cache.level != level || cache.generation != generation || time - cache.time > 10 || time < cache.time) {
					cache.level = level;
					cache.generation = generation;
					cache.time = time;
					cache.values.clear();
				}
				long known = cache.values.get(key);
				if (known != -1L) {
					return known;
				}
				long value = work(level, pos);
				cache.values.put(key, value);
				return value;
			}
		}
		Cache cache = REGION_CACHE.get();
		if (cache.level != level || cache.generation != generation) {
			cache.level = level;
			cache.generation = generation;
			cache.values.clear();
		}
		long known = cache.values.get(key);
		if (known != -1L) {
			return known;
		}
		long value = work(level, pos);
		cache.values.put(key, value);
		return value;
	}

	private static long work(BlockAndLightGetter level, BlockPos pos) {
		float[] colour = new float[3];
		float sky = sky(level, pos, colour);
		float lamps = lamps(level, pos);
		int light = LightCoordsUtil.smoothPack(Math.round(Mth.clamp(lamps, 0.0F, 1.0F) * 240.0F), Math.round(Mth.clamp(sky, 0.0F, 1.0F) * 240.0F));
		int rgb = Math.round(colour[0] * 255.0F) << 16 | Math.round(colour[1] * 255.0F) << 8 | Math.round(colour[2] * 255.0F);
		return (light & 0xFFFFFFFFL) | (long) rgb << 32;
	}

	/** Light coordinates as Minecraft light levels, 0 to 15, with the fractions kept. */
	public static float blockLevel(int packed) {
		return LightCoordsUtil.smoothBlock(packed) / 16.0F;
	}

	public static float skyLevel(int packed) {
		return LightCoordsUtil.smoothSky(packed) / 16.0F;
	}

	/** The brighter of two lights, each channel on its own, keeping the fractions. */
	public static int max(int a, int b) {
		return LightCoordsUtil.smoothPack(Math.max(LightCoordsUtil.smoothBlock(a), LightCoordsUtil.smoothBlock(b)),
				Math.max(LightCoordsUtil.smoothSky(a), LightCoordsUtil.smoothSky(b)));
	}

	/** Share of the sky dome seen from the middle of a block, with light bounced off what is in the way. */
	private static float sky(BlockAndLightGetter level, BlockPos pos, float[] colour) {
		colour[0] = 1.0F;
		colour[1] = 1.0F;
		colour[2] = 1.0F;
		if (level.getBrightness(LightLayer.SKY, pos) == 0 && !openBeside(level, pos)) {
			// Sealed off from the sky: nothing to see, and nothing lit by it to bounce.
			return 0.0F;
		}
		float red = 0.0F;
		float green = 0.0F;
		float blue = 0.0F;
		float[] hue = new float[3];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos last = new BlockPos.MutableBlockPos();
		float ox = pos.getX() + 0.5F;
		float oy = pos.getY() + 0.5F;
		float oz = pos.getZ() + 0.5F;
		int top = level.getMaxY();
		float total = 0.0F;
		for (float[] ray : SKY_RAYS) {
			float pass = 1.0F;
			float got = -1.0F;
			float pr = 1.0F;
			float pg = 1.0F;
			float pb = 1.0F;
			last.set(pos);
			for (float t = STEP; t <= SKY_REACH; t += STEP) {
				int x = Mth.floor(ox + ray[0] * t);
				int y = Mth.floor(oy + ray[1] * t);
				int z = Mth.floor(oz + ray[2] * t);
				if (y > top) {
					got = pass;
					break;
				}
				if (x == last.getX() && y == last.getY() && z == last.getZ()) {
					continue;
				}
				cursor.set(x, y, z);
				BlockState state = level.getBlockState(cursor);
				if (state.is(BlockTags.LEAVES)) {
					// Light through leaves comes out green.
					pass *= LEAF_PASS;
					hue(level, state, cursor, hue);
					pr *= 0.5F + 0.5F * hue[0];
					pg *= 0.5F + 0.5F * hue[1];
					pb *= 0.5F + 0.5F * hue[2];
				} else if (!state.getFluidState().isEmpty()) {
					pass *= WATER_PASS;
					pr *= 0.8F;
					pg *= 0.92F;
				} else if (state.canOcclude()) {
					// Hit something: the light it bounces back, by how lit it is and its colour.
					float lit = level.getBrightness(LightLayer.SKY, last) / 15.0F;
					got = pass * BOUNCE * albedo(level, state, cursor) * lit * lit;
					hue(level, state, cursor, hue);
					pr *= hue[0];
					pg *= hue[1];
					pb *= hue[2];
					break;
				}
				last.set(cursor);
			}
			if (got < 0.0F) {
				// Ran out of reach in the open: sky, as far as Minecraft's sky light there says.
				got = pass * level.getBrightness(LightLayer.SKY, last) / 15.0F;
			}
			total += got;
			red += got * pr;
			green += got * pg;
			blue += got * pb;
		}
		float most = Math.max(red, Math.max(green, blue));
		if (most > 1.0E-4F) {
			colour[0] = red / most;
			colour[1] = green / most;
			colour[2] = blue / most;
		}
		return total / SKY_RAYS.length;
	}

	/**
	 * Whether a block that is not open itself (a prop, a slab) has sky light
	 * beside it. Minecraft gives such blocks no light of their own, and taken
	 * as sealed, the ground under every prop showed as a dark square.
	 */
	private static boolean openBeside(BlockAndLightGetter level, BlockPos pos) {
		if (level.getBlockState(pos).isAir()) {
			return false;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (Direction direction : Direction.values()) {
			if (level.getBrightness(LightLayer.SKY, cursor.setWithOffset(pos, direction)) > 0) {
				return true;
			}
		}
		return false;
	}

	/** A block's colour with its brightness taken out (the brightest channel 1), or white when it has none. */
	private static void hue(BlockAndLightGetter level, BlockState state, BlockPos pos, float[] out) {
		MapColor colour = state.getMapColor(level, pos);
		int c = colour == null ? 0 : colour.col;
		float r = (c >> 16 & 255) / 255.0F;
		float g = (c >> 8 & 255) / 255.0F;
		float b = (c & 255) / 255.0F;
		float most = Math.max(r, Math.max(g, b));
		if (most < 0.05F) {
			out[0] = 1.0F;
			out[1] = 1.0F;
			out[2] = 1.0F;
			return;
		}
		out[0] = r / most;
		out[1] = g / most;
		out[2] = b / most;
	}

	/** How much light a block's colour sends back, 0 to 1. */
	private static float albedo(BlockAndLightGetter level, BlockState state, BlockPos pos) {
		MapColor colour = state.getMapColor(level, pos);
		if (colour == null || colour.col == 0) {
			return 0.35F;
		}
		int c = colour.col;
		float r = (c >> 16 & 255) / 255.0F;
		float g = (c >> 8 & 255) / 255.0F;
		float b = (c & 255) / 255.0F;
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	/** Lamp light at the middle of a block, 0 to 1 and a little over right beside a lamp. */
	private static float lamps(BlockAndLightGetter level, BlockPos pos) {
		float ox = pos.getX() + 0.5F;
		float oy = pos.getY() + 0.5F;
		float oz = pos.getZ() + 0.5F;
		float[] sum = {0.0F};
		int[] seen = {0};
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		Emitters.around(pos.getX(), pos.getY(), pos.getZ(), LAMP_REACH, (x, y, z, emission, soul) -> {
			if (seen[0]++ >= MAX_LAMPS) {
				return;
			}
			sum[0] += lamp(level, cursor, ox, oy, oz, x, y, z, emission);
		});
		int[] held = HeldLight.source();
		if (held != null) {
			sum[0] += lamp(level, cursor, ox, oy, oz, held[0], held[1], held[2], held[3]);
		}
		// Light that bounces round corners, from Minecraft's flood of light.
		float flooded = level.getBrightness(LightLayer.BLOCK, pos) / 15.0F;
		float bounced = LAMP_BOUNCE * flooded * flooded;
		return sum[0] + bounced * Math.max(0.0F, 1.0F - sum[0]);
	}

	/**
	 * Lamp light (0 to 1) at an exact point rather than a block, for drawing
	 * light that falls off smoothly over a surface instead of block by block.
	 * The point should sit just off the surface, in the open.
	 */
	public static float lampsAt(BlockAndLightGetter level, float px, float py, float pz) {
		if (OFF) {
			return -1.0F;
		}
		return Float.intBitsToFloat((int) point(level, px, py, pz));
	}

	/**
	 * How much of the lamp light at a point (as for {@link #lampsAt}) comes
	 * from blue soul fire, 0 to 1.
	 */
	public static float soulAt(BlockAndLightGetter level, float px, float py, float pz) {
		if (OFF) {
			return 0.0F;
		}
		return Float.intBitsToFloat((int) (point(level, px, py, pz) >>> 32));
	}

	/** Lamp light at a point and its soul share, as two float bit patterns (low, high). */
	private static long point(BlockAndLightGetter level, float px, float py, float pz) {
		PointCache cache = POINT_CACHE.get();
		if (cache.level != level || cache.generation != generation) {
			cache.level = level;
			cache.generation = generation;
			cache.values.clear();
		}
		long key = ((long) Math.round(px * 16.0F) & 0x3FFFFFL) << 42 | ((long) Math.round(py * 16.0F) & 0xFFFFFL) << 22
				| (long) Math.round(pz * 16.0F) & 0x3FFFFFL;
		long known = cache.values.get(key);
		if (known != Long.MIN_VALUE) {
			return known;
		}
		long value = lampsAtUncached(level, px, py, pz);
		cache.values.put(key, value);
		return value;
	}

	private static final class PointCache {
		Object level;
		int generation;
		final Long2LongOpenHashMap values = new Long2LongOpenHashMap();

		PointCache() {
			values.defaultReturnValue(Long.MIN_VALUE);
		}
	}

	private static final ThreadLocal<PointCache> POINT_CACHE = ThreadLocal.withInitial(PointCache::new);

	private static long lampsAtUncached(BlockAndLightGetter level, float px, float py, float pz) {
		float[] sum = {0.0F, 0.0F};
		int[] seen = {0};
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		Emitters.around(Mth.floor(px), Mth.floor(py), Mth.floor(pz), LAMP_REACH, (x, y, z, emission, soul) -> {
			if (seen[0]++ >= MAX_LAMPS) {
				return;
			}
			float one = lamp(level, cursor, px, py, pz, x, y, z, emission);
			sum[0] += one;
			if (soul) {
				sum[1] += one;
			}
		});
		int[] held = HeldLight.source();
		if (held != null) {
			float one = lamp(level, cursor, px, py, pz, held[0], held[1], held[2], held[3]);
			sum[0] += one;
			if (held[4] != 0) {
				sum[1] += one;
			}
		}
		float flooded = flood(level, cursor, px, py, pz) / 15.0F;
		float bounced = LAMP_BOUNCE * flooded * flooded;
		float lamps = Mth.clamp(sum[0] + bounced * Math.max(0.0F, 1.0F - sum[0]), 0.0F, 1.0F);
		// Flooded light that bounced round from a soul lamp is not told apart:
		// it takes the share of the lamps seen directly.
		float soul = sum[0] > 1.0E-4F ? Mth.clamp(sum[1] / sum[0], 0.0F, 1.0F) : 0.0F;
		return (Float.floatToRawIntBits(lamps) & 0xFFFFFFFFL) | (long) Float.floatToRawIntBits(soul) << 32;
	}

	/** Minecraft's block light blended between the open blocks round a point. */
	private static float flood(BlockAndLightGetter level, BlockPos.MutableBlockPos cursor, float px, float py, float pz) {
		float sx = px - 0.5F;
		float sy = py - 0.5F;
		float sz = pz - 0.5F;
		int x0 = Mth.floor(sx);
		int y0 = Mth.floor(sy);
		int z0 = Mth.floor(sz);
		float fx = sx - x0;
		float fy = sy - y0;
		float fz = sz - z0;
		float total = 0.0F;
		float weights = 0.0F;
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			cursor.set(x0 + cx, y0 + cy, z0 + cz);
			if (level.getBlockState(cursor).isSolidRender()) {
				continue;
			}
			float w = (cx == 1 ? fx : 1.0F - fx) * (cy == 1 ? fy : 1.0F - fy) * (cz == 1 ? fz : 1.0F - fz);
			total += w * level.getBrightness(LightLayer.BLOCK, cursor);
			weights += w;
		}
		return weights < 1.0E-4F ? 0.0F : total / weights;
	}

	private static float lamp(BlockAndLightGetter level, BlockPos.MutableBlockPos cursor, float ox, float oy, float oz, int x, int y, int z,
			int emission) {
		float dx = x + 0.5F - ox;
		float dy = y + 0.5F - oy;
		float dz = z + 0.5F - oz;
		float distance = Mth.sqrt(dx * dx + dy * dy + dz * dz);
		float reach = emission + TAIL;
		if (distance >= reach) {
			return 0.0F;
		}
		// A smooth window: flat near the lamp, easing to zero at the reach.
		float edge = 1.0F - distance * distance / (reach * reach);
		float strength = (0.35F + 0.65F * emission / 15.0F) * edge * edge / (1.0F + FALLOFF * 15.0F / Math.max(emission, 1) * distance * distance);
		if (distance > 1.0F && blocked(level, cursor, ox, oy, oz, dx / distance, dy / distance, dz / distance, distance, x, y, z)) {
			return 0.0F;
		}
		return strength;
	}

	/** Whether a wall stands between a point and a lamp. */
	private static boolean blocked(BlockAndLightGetter level, BlockPos.MutableBlockPos cursor, float ox, float oy, float oz, float rx, float ry,
			float rz, float distance, int lx, int ly, int lz) {
		int sx = Mth.floor(ox);
		int sy = Mth.floor(oy);
		int sz = Mth.floor(oz);
		for (float t = STEP; t < distance - 0.25F; t += STEP) {
			int x = Mth.floor(ox + rx * t);
			int y = Mth.floor(oy + ry * t);
			int z = Mth.floor(oz + rz * t);
			if ((x == sx && y == sy && z == sz) || (x == lx && y == ly && z == lz)) {
				continue;
			}
			cursor.set(x, y, z);
			BlockState state = level.getBlockState(cursor);
			if (state.canOcclude() && !state.is(BlockTags.LEAVES)) {
				// Smoothed ground is drawn up to half a block lower than its
				// block: light skimming over the top half of it is not stopped,
				// or the ground cast long false shadows across itself.
				float fy = oy + ry * t - y;
				if (state.is(com.mcsource.terrain.SmoothTerrain.SMOOTH) && fy >= 0.5F) {
					continue;
				}
				return true;
			}
		}
		return false;
	}
}
