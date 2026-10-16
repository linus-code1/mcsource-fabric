package com.mcsource.terrain;

/**
 * Pure math for the smooth terrain surface, shared by the client mesher and the
 * collision shapes so both sides agree on exactly the same surface.
 *
 * <p>A smoothed block keeps its bottom and lowers its four top corners. Corner
 * heights are stored in eighths of a block (2, 4, 6 or 8), packed four bits each
 * in the order (x0,z0), (x1,z0), (x0,z1), (x1,z1).
 */
public final class SmoothMath {
	/** Packed value for a block whose four corners are all at full height. */
	public static final int FULL = 0x8888;
	/** Collision is built from this many columns per block side. */
	public static final int COLUMNS = 8;

	private SmoothMath() {
	}

	public static int pack(int h00, int h10, int h01, int h11) {
		return h00 | h10 << 4 | h01 << 8 | h11 << 12;
	}

	/** Corner height in eighths; index is cx + 2 * cz. */
	public static int corner(int packed, int index) {
		return packed >> (index * 4) & 0xF;
	}

	/**
	 * Height of one top corner in eighths.
	 *
	 * @param anyAboveBlocked whether any of the four cells one level up around this corner holds something
	 * @param anyBuiltBeside whether any of the four cells at this level around the corner holds a non-terrain block
	 * @param filledBeside how many of the four cells at this level around the corner hold terrain (1 to 4)
	 */
	public static int cornerHeight(boolean anyAboveBlocked, boolean anyBuiltBeside, int filledBeside) {
		if (anyAboveBlocked || anyBuiltBeside) {
			return 8;
		}
		return Math.max(1, Math.min(4, filledBeside)) * 2;
	}

	/** Bilinear surface height in blocks (0 to 1) at local position u (x) and v (z), each 0 to 1. */
	public static float surface(int packed, float u, float v) {
		float h00 = corner(packed, 0);
		float h10 = corner(packed, 1);
		float h01 = corner(packed, 2);
		float h11 = corner(packed, 3);
		float near = h00 + (h10 - h00) * u;
		float far = h01 + (h11 - h01) * u;
		return (near + (far - near) * v) / 8.0F;
	}

	/** Mean of the four corners in blocks. */
	public static float meanHeight(int packed) {
		return (corner(packed, 0) + corner(packed, 1) + corner(packed, 2) + corner(packed, 3)) / 32.0F;
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
