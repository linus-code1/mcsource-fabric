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
		// Flat ground: four terrain blocks below the corner, four open above.
		assertEquals(0, SmoothMath.cornerOffset(4));
		// Fully buried or fully open corners never move.
		assertEquals(0, SmoothMath.cornerOffset(0));
		assertEquals(0, SmoothMath.cornerOffset(8));
		// Outer corners sink, inner corners rise, by at most half a block.
		assertEquals(-4, SmoothMath.cornerOffset(1));
		assertEquals(-2, SmoothMath.cornerOffset(3));
		assertEquals(2, SmoothMath.cornerOffset(5));
		assertEquals(4, SmoothMath.cornerOffset(7));
	}

	/**
	 * A straight one-block step: the top edge of the high side (2 of 8 terrain)
	 * sinks half a block and the foot of the step (6 of 8) rises half a block,
	 * so they meet and the step's vertical face closes into a ramp.
	 */
	@Test
	void oneBlockStepBecomesRamp() {
		float topEdge = 1 + SmoothMath.cornerOffset(2) / 8.0F;
		float foot = 0 + SmoothMath.cornerOffset(6) / 8.0F;
		assertEquals(topEdge, foot, 1e-6);
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
