package com.mcsource.client.light;

/**
 * Marks the quads the smoothed ground draws with light already worked out
 * for each corner. The renderer normally raises every flat-lit quad to the
 * light of the block it belongs to, which undid the smooth light and showed
 * each block as a square of its own brightness; while this is on, it leaves
 * those quads as they are (see FlatLighterMixin).
 */
public final class OwnLight {
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private OwnLight() {
	}

	public static void begin() {
		DEPTH.get()[0]++;
	}

	public static void end() {
		DEPTH.get()[0]--;
	}

	public static boolean active() {
		return DEPTH.get()[0] > 0;
	}
}
