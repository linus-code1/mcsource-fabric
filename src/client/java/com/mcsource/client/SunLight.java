package com.mcsource.client;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.client.light.SourceLight;
import com.mcsource.terrain.SmoothTerrain;

/**
 * A fixed sun, like a Source map's light_environment: every vertex out under
 * the sky is lit by how directly it faces the sun and whether anything stands
 * between it and the sun. Shadowed and turned-away surfaces keep only the cool
 * sky fill. The result is a colour multiplier baked into the vertex when the
 * chunk is meshed, so shadows follow the terrain smoothly and cost nothing per
 * frame. The day/night cycle still comes from the lightmap.
 */
public final class SunLight {
	/** Direction towards the sun: high in the west-south-west. */
	private static final float SUN_X;
	private static final float SUN_Y;
	private static final float SUN_Z;
	/** Small offsets of the sun direction, giving soft shadow edges. */
	private static final float[][] RAYS;

	// Shares of daylight from the sky fill and from the sun. They match the
	// lightmap, where open sky gives AMBIENT * FILL + DIRECT * SUN.
	private static final float AMBIENT = 0.7F;
	private static final float DIRECT = 0.6F;
	private static final float[] FILL = {0.78F, 0.87F, 1.06F};
	private static final float[] SUN = {1.06F, 0.98F, 0.86F};

	/** How far shadows reach. Stays inside the 3x3x3 sections a chunk mesh may read. */
	private static final float MAX_REACH = 14.5F;
	private static final float STEP = 0.3F;
	/** Occluders this close to the start are the surface itself. */
	private static final float SKIP = 0.3F;
	private static final float LEAF_TRANSMIT = 0.45F;
	private static final float WRAP = 0.35F;
	private static final float BOUNCE = 0.3F;

	private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

	static {
		float x = -0.5F;
		float y = 0.85F;
		float z = 0.28F;
		float length = Mth.sqrt(x * x + y * y + z * z);
		SUN_X = x / length;
		SUN_Y = y / length;
		SUN_Z = z / length;
		// Two unit vectors across the sun direction for the penumbra rays.
		float hLength = Mth.sqrt(SUN_X * SUN_X + SUN_Z * SUN_Z);
		float ux = SUN_Z / hLength;
		float uz = -SUN_X / hLength;
		float vx = SUN_Y * uz;
		float vy = SUN_Z * ux - SUN_X * uz;
		float vz = -SUN_Y * ux;
		// A wide spread gives the soft penumbra of Source's baked shadows and
		// hides the one-block spacing of the vertices the shadow is stored at.
		float spread = 0.13F;
		float[][] offsets = {{0, 0}, {1, 0}, {0.31F, 0.95F}, {-0.81F, 0.59F}, {-0.81F, -0.59F}, {0.31F, -0.95F}};
		RAYS = new float[offsets.length][];
		for (int i = 0; i < offsets.length; i++) {
			float a = offsets[i][0] * spread;
			float b = offsets[i][1] * spread;
			float dx = SUN_X + a * ux + b * vx;
			float dy = SUN_Y + b * vy;
			float dz = SUN_Z + a * uz + b * vz;
			float l = Mth.sqrt(dx * dx + dy * dy + dz * dz);
			RAYS[i] = new float[] {dx / l, dy / l, dz / l};
		}
	}

	/** How strongly shade takes the colour of the light bounced into it. */
	private static final float BOUNCE_TINT = 0.45F;

	private SunLight() {
	}

	/**
	 * Colour multiplier (opaque ARGB) for a vertex at world position
	 * {@code (x, y, z)} with normal {@code (nx, ny, nz)}, given the sky and block
	 * light there on the 0-15 scale.
	 */
	public static int multiplier(BlockAndTintGetter level, float x, float y, float z, float nx, float ny, float nz, float sky,
			float block) {
		if (sky <= 0.0F) {
			return -1;
		}
		// Wrapped lighting: surfaces turned partly away from the sun still catch
		// some of it, the way bounced light softens Source's lightmaps.
		float facing = (nx * SUN_X + ny * SUN_Y + nz * SUN_Z + WRAP) / (1.0F + WRAP);
		float sun = facing <= 0.0F ? 0.0F : facing * visibility(level, x, y, z);
		// Only daylight is shaded: indoors and near lamps the multiplier fades out.
		float skyShare = sky / 15.0F;
		float strength = skyShare * skyShare * (1.0F - 0.8F * Mth.clamp(block / 15.0F, 0.0F, 1.0F));
		// Shade takes the colour of what the light bounced off on its way in.
		int tint = SourceLight.tint(level, BlockPos.containing(x + nx * 0.5F, y + ny * 0.5F, z + nz * 0.5F));
		float bounce = BOUNCE_TINT * skyShare * (1.0F - 0.8F * Mth.clamp(block / 15.0F, 0.0F, 1.0F)) * (1.0F - 0.7F * sun);
		int r = Math.round(channel(0, sun, strength) * Mth.lerp(bounce, 1.0F, ARGB.red(tint) / 255.0F));
		int g = Math.round(channel(1, sun, strength) * Mth.lerp(bounce, 1.0F, ARGB.green(tint) / 255.0F));
		int b = Math.round(channel(2, sun, strength) * Mth.lerp(bounce, 1.0F, ARGB.blue(tint) / 255.0F));
		return ARGB.color(255, r, g, b);
	}

	private static int channel(int c, float sun, float strength) {
		// Shade never goes fully to the sky fill: light bounced off sunlit
		// ground nearby lifts it, as radiosity does in Source maps.
		float received = sun + BOUNCE * (1.0F - sun);
		float lit = (AMBIENT * FILL[c] + DIRECT * SUN[c] * received) / (AMBIENT * FILL[c] + DIRECT * SUN[c]);
		return Math.round(255.0F * Mth.lerp(strength, 1.0F, lit));
	}

	/** Fraction of the sun visible from a point, 0 to 1, cached per chunk mesh. */
	private static float visibility(BlockAndTintGetter level, float x, float y, float z) {
		Cache cache = CACHE.get();
		if (cache.level != level) {
			cache.level = level;
			cache.values.clear();
		}
		long key = ((long) Math.round(x * 16.0F) & 0x3FFFFFL) << 42 | ((long) Math.round(y * 16.0F) & 0xFFFFFL) << 22
				| (long) Math.round(z * 16.0F) & 0x3FFFFFL;
		float cached = cache.values.getOrDefault(key, -1.0F);
		if (cached >= 0.0F) {
			return cached;
		}
		float total = 0.0F;
		for (float[] ray : RAYS) {
			total += trace(level, cache.cursor, x, y, z, ray);
		}
		float result = total / RAYS.length;
		cache.values.put(key, result);
		return result;
	}

	private static float trace(BlockAndTintGetter level, BlockPos.MutableBlockPos cursor, float x, float y, float z, float[] ray) {
		float reach = MAX_REACH / Math.max(Math.abs(ray[0]), Math.max(Math.abs(ray[1]), Math.abs(ray[2])));
		int originX = Mth.floor(x);
		int originY = Mth.floor(y);
		int originZ = Mth.floor(z);
		int lastX = Integer.MIN_VALUE;
		int lastY = 0;
		int lastZ = 0;
		float transmit = 1.0F;
		for (float t = SKIP; t < reach; t += STEP) {
			float px = x + ray[0] * t;
			float py = y + ray[1] * t;
			float pz = z + ray[2] * t;
			int bx = Mth.floor(px);
			int by = Mth.floor(py);
			int bz = Mth.floor(pz);
			if (bx == lastX && by == lastY && bz == lastZ) {
				continue;
			}
			lastX = bx;
			lastY = by;
			lastZ = bz;
			if (Math.abs(bx - originX) > 15 || Math.abs(by - originY) > 15 || Math.abs(bz - originZ) > 15) {
				break;
			}
			BlockState state = level.getBlockState(cursor.set(bx, by, bz));
			if (state.isAir()) {
				continue;
			}
			if (state.is(BlockTags.LEAVES)) {
				transmit *= LEAF_TRANSMIT;
			} else if (state.isSolidRender()) {
				// Only full opaque cubes block the sun. Snow layers, slabs and
				// plants would otherwise shade the very surface they lie on.
				// Smoothed ground may sit up to half a block lower than its cell,
				// so its top half only blocks the sun when buried.
				if (state.is(SmoothTerrain.SMOOTH) && py - by >= 0.5F
						&& SmoothTerrain.isOpen(level.getBlockState(cursor.set(bx, by + 1, bz)))) {
					continue;
				}
				return 0.0F;
			}
			if (transmit < 0.05F) {
				return 0.0F;
			}
		}
		return transmit;
	}

	private static final class Cache {
		Object level;
		final Long2FloatOpenHashMap values = new Long2FloatOpenHashMap();
		final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	}
}
