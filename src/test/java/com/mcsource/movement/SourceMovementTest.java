package com.mcsource.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SourceMovementTest {
	/** One substep: three per game tick. */
	private static final double DT = 0.05 / SourceMovement.SUBSTEPS;

	@Test
	void runningReachesRunSpeed() {
		double[] v = {0.0, 0.0};
		for (int i = 0; i < 120; i++) {
			SourceMovement.step(v, 1.0, 0.0, SourceMovement.RUN_SPEED, true, 1.0, DT);
		}
		assertEquals(SourceMovement.RUN_SPEED, v[0], 1.0E-6);
		assertEquals(0.0, v[1], 1.0E-9);
	}

	@Test
	void frictionStopsQuickly() {
		double[] v = {SourceMovement.RUN_SPEED, 0.0};
		int ticks = 0;
		while (v[0] > 0.0 && ticks < 100) {
			SourceMovement.step(v, 0.0, 0.0, 0.0, true, 1.0, DT);
			ticks++;
		}
		assertTrue(ticks <= 9 * SourceMovement.SUBSTEPS, "stopped after " + ticks + " steps");
	}

	@Test
	void iceKeepsSpeed() {
		double[] v = {SourceMovement.RUN_SPEED, 0.0};
		SourceMovement.step(v, 0.0, 0.0, 0.0, true, 0.05, DT);
		assertTrue(v[0] > SourceMovement.RUN_SPEED * 0.95);
	}

	@Test
	void airDoesNotSpeedUpStraightAhead() {
		double[] v = {SourceMovement.RUN_SPEED, 0.0};
		SourceMovement.step(v, 1.0, 0.0, SourceMovement.RUN_SPEED, false, 1.0, DT);
		assertEquals(SourceMovement.RUN_SPEED, v[0], 1.0E-9);
	}

	@Test
	void airStrafingGainsSpeed() {
		// Strafe while turning: the wished direction stays at right angles to
		// the motion, as when holding a strafe key and turning the mouse.
		double[] v = {SourceMovement.RUN_SPEED, 0.0};
		for (int i = 0; i < 12 * SourceMovement.SUBSTEPS; i++) {
			double speed = Math.hypot(v[0], v[1]);
			SourceMovement.step(v, -v[1] / speed, v[0] / speed, SourceMovement.RUN_SPEED, false, 1.0, DT);
		}
		assertTrue(Math.hypot(v[0], v[1]) > SourceMovement.RUN_SPEED + 1.0);
	}

	@Test
	void jumpClearsABlock() {
		double v = SourceMovement.jumpSpeed(SourceMovement.GRAVITY);
		double height = v * v / (2.0 * SourceMovement.GRAVITY);
		assertTrue(height > 1.25, "jump height " + height);
	}

	@Test
	void speedIsCapped() {
		double[] v = {100.0, 0.0};
		SourceMovement.step(v, 0.0, 0.0, 0.0, false, 1.0, DT);
		assertEquals(SourceMovement.MAX_SPEED, v[0], 1.0E-9);
	}
}
