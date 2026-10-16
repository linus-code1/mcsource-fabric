package com.mcsource.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmoothMathTest {
	private static final int[] HEIGHTS = {2, 4, 6, 8};

	@Test
	void fullBlockStaysFull() {
		assertEquals(SmoothMath.FULL, SmoothMath.pack(8, 8, 8, 8));
		assertEquals(1.0F, SmoothMath.surface(SmoothMath.FULL, 0.3F, 0.7F), 1e-6);
	}

	@Test
	void cornersRoundTrip() {
		int packed = SmoothMath.pack(2, 4, 6, 8);
		assertEquals(2, SmoothMath.corner(packed, 0));
		assertEquals(4, SmoothMath.corner(packed, 1));
		assertEquals(6, SmoothMath.corner(packed, 2));
		assertEquals(8, SmoothMath.corner(packed, 3));
		assertEquals(0.25F, SmoothMath.surface(packed, 0, 0), 1e-6);
		assertEquals(1.0F, SmoothMath.surface(packed, 1, 1), 1e-6);
	}

	@Test
	void cornerHeightRules() {
		assertEquals(8, SmoothMath.cornerHeight(true, false, 1));
		assertEquals(8, SmoothMath.cornerHeight(false, true, 1));
		assertEquals(2, SmoothMath.cornerHeight(false, false, 1));
		assertEquals(4, SmoothMath.cornerHeight(false, false, 2));
		assertEquals(8, SmoothMath.cornerHeight(false, false, 4));
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
		// Steepest slope is 0.75 blocks per block in each axis; half a column's width along both
		// axes plus rounding to sixteenths keeps the gap under an eighth of a block.
		assertTrue(maxError <= 0.125F, "collision strays " + maxError + " blocks from the drawn surface");
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
