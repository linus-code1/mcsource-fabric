package com.mcsource.terrain;

/**
 * Pure math for the smooth terrain surface, shared by the client mesher and the
 * collision shapes so both sides agree on exactly the same surface.
 *
 * <p>Smoothing moves every grid corner (the points where block corners meet)
 * up or down by an offset that depends only on the eight blocks around that
 * corner. Every block touching a corner moves it the same way, so the surface
 * stays watertight: a one-block step, straight or diagonal, becomes a ramp, and
 * flat ground stays flat.
 *
 * <p>Offsets are in eighths of a block, from -4 to +4. A block's four top
 * corner heights are stored as {@code 8 + offset} (4 to 12), packed four bits
 * each in the order (x0,z0), (x1,z0), (x0,z1), (x1,z1).
 */
public final class SmoothMath {
	/** Packed value for a block whose four top corners are all at full height. */
	public static final int FULL = 0x8888;
	/** Collision is built from this many columns per block side. */
	public static final int COLUMNS = 8;

	private SmoothMath() {
	}

	/**
	 * Vertical offset in eighths for a grid corner with {@code lower} smoothed
	 * blocks among the four just below it and {@code upper} among the four just
	 * above, when none of the eight is a built block or liquid.
	 *
	 * <p>Exactly half full (flat ground, a flat ceiling, a straight wall) stays
	 * put. Otherwise the corner moves half a block toward the side that closes
	 * a one-block step: on ground (more terrain below) emptier corners sink and
	 * fuller corners rise; on ceilings (more terrain above) the opposite. The
	 * top and foot of every one-block step therefore meet, straight or diagonal,
	 * above or below. Corners never move further than half a block, so
	 * neighbouring corners can meet but never cross.
	 */
	public static int cornerOffset(int lower, int upper) {
		int terrain = lower + upper;
		if (terrain <= 0 || terrain >= 8 || terrain == 4 || lower == upper) {
			return 0;
		}
		int towardFuller = terrain < 4 ? -4 : 4;
		return lower > upper ? towardFuller : -towardFuller;
	}

	public static int pack(int h00, int h10, int h01, int h11) {
		return h00 | h10 << 4 | h01 << 8 | h11 << 12;
	}

	/** Corner height in eighths; index is cx + 2 * cz. */
	public static int corner(int packed, int index) {
		return packed >> (index * 4) & 0xF;
	}

	/** Bilinear surface height in blocks (0.5 to 1.5) at local position u (x) and v (z), each 0 to 1. */
	public static float surface(int packed, float u, float v) {
		float h00 = corner(packed, 0);
		float h10 = corner(packed, 1);
		float h01 = corner(packed, 2);
		float h11 = corner(packed, 3);
		float near = h00 + (h10 - h00) * u;
		float far = h01 + (h11 - h01) * u;
		return (near + (far - near) * v) / 8.0F;
	}

	/** Mean of the four top corners in blocks. */
	public static float meanHeight(int packed) {
		return (corner(packed, 0) + corner(packed, 1) + corner(packed, 2) + corner(packed, 3)) / 32.0F;
	}

	/**
	 * Trilinear offset in blocks inside a block from its eight corner offsets
	 * (eighths, index cx + 2 * cy + 4 * cz), at local position x, y, z (0 to 1).
	 */
	public static float offsetAt(int[] offsets, float x, float y, float z) {
		float result = 0;
		for (int i = 0; i < 8; i++) {
			float wx = (i & 1) != 0 ? x : 1 - x;
			float wy = (i & 2) != 0 ? y : 1 - y;
			float wz = (i & 4) != 0 ? z : 1 - z;
			result += offsets[i] * wx * wy * wz;
		}
		return result / 8.0F;
	}

	/**
	 * Collision column height in sixteenths of a block, sampled from the same
	 * surface the mesher draws at the centre of column (i, j).
	 */
	public static int columnHeight16(int packed, int i, int j) {
		float u = (i + 0.5F) / COLUMNS;
		float v = (j + 0.5F) / COLUMNS;
		return Math.max(1, Math.round(surface(packed, u, v) * 16.0F));
	}
}
