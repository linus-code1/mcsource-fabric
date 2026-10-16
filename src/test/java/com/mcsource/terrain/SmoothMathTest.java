package com.mcsource.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmoothMathTest {
	private static final int[] HEIGHTS = {4, 6, 8, 10, 12};

	@Test
	void fullBlockStaysFull() {
		assertEquals(SmoothMath.FULL, SmoothMath.pack(8, 8, 8, 8));
		assertEquals(1.0F, SmoothMath.surface(SmoothMath.FULL, 0.3F, 0.7F), 1e-6);
	}

	@Test
	void cornersRoundTrip() {
		int packed = SmoothMath.pack(4, 8, 10, 12);
		assertEquals(4, SmoothMath.corner(packed, 0));
		assertEquals(8, SmoothMath.corner(packed, 1));
		assertEquals(10, SmoothMath.corner(packed, 2));
		assertEquals(12, SmoothMath.corner(packed, 3));
		assertEquals(0.5F, SmoothMath.surface(packed, 0, 0), 1e-6);
		assertEquals(1.5F, SmoothMath.surface(packed, 1, 1), 1e-6);
	}

	@Test
	void cornerOffsets() {
		// Flat ground, flat ceiling and straight walls stay put.
		assertEquals(0, SmoothMath.cornerOffset(4, 0));
		assertEquals(0, SmoothMath.cornerOffset(0, 4));
		assertEquals(0, SmoothMath.cornerOffset(2, 2));
		// Fully buried or fully open corners never move.
		assertEquals(0, SmoothMath.cornerOffset(0, 0));
		assertEquals(0, SmoothMath.cornerOffset(4, 4));
		// On ground, emptier corners sink and fuller corners rise, by half a block.
		assertEquals(-4, SmoothMath.cornerOffset(1, 0));
		assertEquals(-4, SmoothMath.cornerOffset(3, 0));
		assertEquals(4, SmoothMath.cornerOffset(4, 1));
		assertEquals(4, SmoothMath.cornerOffset(4, 3));
		// On ceilings it is the other way round.
		assertEquals(4, SmoothMath.cornerOffset(0, 2));
		assertEquals(-4, SmoothMath.cornerOffset(2, 4));
	}

	/**
	 * A one-block step: on ground the top edge sinks and the foot rises half a
	 * block; under a ceiling the lower edge rises and the upper edge sinks. Either
	 * way the two corners meet and the step's vertical face closes into a ramp.
	 * Pairs are (lower, upper) block counts around the upper and lower corner.
	 */
	@Test
	void oneBlockStepsBecomeRamps() {
		int[][][] steps = {
				{{2, 0}, {4, 2}}, {{1, 0}, {4, 1}}, {{3, 0}, {4, 3}}, // ground: straight, outer, inner
				{{2, 4}, {0, 2}}, {{3, 4}, {0, 3}}, {{1, 4}, {0, 1}}, // ceiling: straight, outer, inner
		};
		for (int[][] step : steps) {
			float upperCorner = 1 + SmoothMath.cornerOffset(step[0][0], step[0][1]) / 8.0F;
			float lowerCorner = SmoothMath.cornerOffset(step[1][0], step[1][1]) / 8.0F;
			assertEquals(upperCorner, lowerCorner, 1e-6);
		}
	}

	@Test
	void meshTopMatchesCollisionCorners() {
		int[] offsets = {-4, 2, 0, 4, -2, 0, 4, -4};
		for (int cz = 0; cz <= 1; cz++) {
			for (int cx = 0; cx <= 1; cx++) {
				int top = offsets[cx + 2 + 4 * cz];
				assertEquals(top / 8.0F, SmoothMath.offsetAt(offsets, cx, 1, cz), 1e-6);
			}
		}
	}

	/** The collision columns must sit on the drawn surface everywhere, for every corner combination. */
	@Test
	void collisionMatchesDrawnSurface() {
		float maxError = 0;
		for (int a : HEIGHTS) {
			for (int b : HEIGHTS) {
				for (int c : HEIGHTS) {
					for (int d : HEIGHTS) {
						int packed = SmoothMath.pack(a, b, c, d);
						for (int j = 0; j < SmoothMath.COLUMNS; j++) {
							for (int i = 0; i < SmoothMath.COLUMNS; i++) {
								float column = SmoothMath.columnHeight16(packed, i, j) / 16.0F;
								// Compare against the drawn surface anywhere over this column.
								for (int s = 0; s <= 4; s++) {
									for (int t = 0; t <= 4; t++) {
										float u = (i + s / 4.0F) / SmoothMath.COLUMNS;
										float v = (j + t / 4.0F) / SmoothMath.COLUMNS;
										maxError = Math.max(maxError, Math.abs(column - SmoothMath.surface(packed, u, v)));
									}
								}
							}
						}
					}
				}
			}
		}
		// Steepest slope is one block per block in each axis; half a column's width along both
		// axes plus rounding to sixteenths keeps the gap within an eighth of a block.
		assertTrue(maxError <= 0.125F + 1e-4F, "collision strays " + maxError + " blocks from the drawn surface");
	}

	@Test
	void stepsStayWalkable() {
		for (int a : HEIGHTS) {
			for (int b : HEIGHTS) {
				for (int c : HEIGHTS) {
					for (int d : HEIGHTS) {
						int packed = SmoothMath.pack(a, b, c, d);
						for (int j = 0; j < SmoothMath.COLUMNS; j++) {
							for (int i = 1; i < SmoothMath.COLUMNS; i++) {
								int step = Math.abs(SmoothMath.columnHeight16(packed, i, j) - SmoothMath.columnHeight16(packed, i - 1, j));
								// Players step up 0.6 blocks; keep each step far below that.
								assertTrue(step <= 3, "step of " + step + "/16 in " + Integer.toHexString(packed));
							}
						}
					}
				}
			}
		}
	}
}
