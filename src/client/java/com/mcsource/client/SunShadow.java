package com.mcsource.client;

import net.minecraft.util.Mth;

/**
 * Entity shadows cast by the fixed sun of {@link SunLight}, like the projected
 * shadows of Source: the round blob is stretched into an ellipse and pushed
 * away from the sun, so it lines up with the baked terrain shadows.
 */
public final class SunShadow {
	/** How much wider than the round shadow the ground is gathered. */
	public static final float REACH = 2.6F;
	/** Shadow pieces are this much darker than Minecraft's blob. */
	public static final float DARKEN = 1.25F;

	/** Length of the ellipse along the ground, in shadow radii. */
	private static final float STRETCH = 1.7F;
	/** Width across, in shadow radii. */
	private static final float WIDTH = 0.9F;
	/** How far the centre is pushed away from the sun, in shadow radii. */
	private static final float OFFSET = 0.8F;

	/** Direction along the ground away from the sun. */
	private static final float AWAY_X;
	private static final float AWAY_Z;

	static {
		float x = -SunLight.sunX();
		float z = -SunLight.sunZ();
		float length = Mth.sqrt(x * x + z * z);
		AWAY_X = x / length;
		AWAY_Z = z / length;
	}

	private SunShadow() {
	}

	/** Texture u at {@code (x, z)} from the entity, for a shadow of the given radius. */
	public static float u(float x, float z, float radius) {
		float along = x * AWAY_X + z * AWAY_Z - OFFSET * radius;
		return along / (2.0F * STRETCH * radius) + 0.5F;
	}

	/** Texture v at {@code (x, z)} from the entity, for a shadow of the given radius. */
	public static float v(float x, float z, float radius) {
		float across = x * AWAY_Z - z * AWAY_X;
		return across / (2.0F * WIDTH * radius) + 0.5F;
	}
}
