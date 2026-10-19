package com.mcsource.client.light;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
	private static final float FALLOFF = 0.03F;
	/** Share of Minecraft's flooded lamp light kept as light bounced round corners. */
	private static final float LAMP_BOUNCE = 0.3F;
	private static final int MAX_LAMPS = 24;

	private static final ThreadLocal<Cache> REGION_CACHE = ThreadLocal.withInitial(Cache::new);
	private static final Cache LEVEL_CACHE = new Cache();
	private static volatile int generation;

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
		final Long2IntOpenHashMap values = new Long2IntOpenHashMap();

		Cache() {
			values.defaultReturnValue(-1);
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
		if (level == null || state.emissiveRendering() || state.isSolidRender()) {
			return vanilla;
		}
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
				int known = cache.values.get(key);
				if (known != -1) {
					return known;
				}
				int value = work(level, pos);
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
		int known = cache.values.get(key);
		if (known != -1) {
			return known;
		}
		int value = work(level, pos);
		cache.values.put(key, value);
		return value;
	}

	private static int work(BlockAndLightGetter level, BlockPos pos) {
		float sky = sky(level, pos);
		float lamps = lamps(level, pos);
		return LightCoordsUtil.smoothPack(Math.round(Mth.clamp(lamps, 0.0F, 1.0F) * 240.0F), Math.round(Mth.clamp(sky, 0.0F, 1.0F) * 240.0F));
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
	private static float sky(BlockAndLightGetter level, BlockPos pos) {
		if (level.getBrightness(LightLayer.SKY, pos) == 0) {
			// Sealed off from the sky: nothing to see, and nothing lit by it to bounce.
			return 0.0F;
		}
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
					pass *= LEAF_PASS;
				} else if (!state.getFluidState().isEmpty()) {
					pass *= WATER_PASS;
				} else if (state.canOcclude()) {
					// Hit something: the light it bounces back, by how lit it is and its colour.
					float lit = level.getBrightness(LightLayer.SKY, last) / 15.0F;
					got = pass * BOUNCE * albedo(level, state, cursor) * lit * lit;
					break;
				}
				last.set(cursor);
			}
			if (got < 0.0F) {
				// Ran out of reach in the open: sky, as far as Minecraft's sky light there says.
				got = pass * level.getBrightness(LightLayer.SKY, last) / 15.0F;
			}
			total += got;
		}
		return total / SKY_RAYS.length;
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
		Emitters.around(pos.getX(), pos.getY(), pos.getZ(), 15, (x, y, z, emission) -> {
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

	private static float lamp(BlockAndLightGetter level, BlockPos.MutableBlockPos cursor, float ox, float oy, float oz, int x, int y, int z,
			int emission) {
		float dx = x + 0.5F - ox;
		float dy = y + 0.5F - oy;
		float dz = z + 0.5F - oz;
		float distance = Mth.sqrt(dx * dx + dy * dy + dz * dz);
		float reach = emission + 1.0F;
		if (distance >= reach) {
			return 0.0F;
		}
		float edge = 1.0F - distance * distance / (reach * reach);
		float strength = emission / 15.0F * edge * edge / (1.0F + FALLOFF * distance * distance);
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
				return true;
			}
		}
		return false;
	}
}
