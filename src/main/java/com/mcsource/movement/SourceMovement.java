package com.mcsource.movement;

/**
 * Horizontal player movement in the style of Source engine games, written from
 * the published description of the Quake-family player physics: ground
 * friction that slows you towards a stop, acceleration towards the speed you
 * ask for, and air control that only adds speed sideways to your motion, which
 * makes air strafing and bunny hopping work. Vertical motion (gravity and the
 * jump itself) stays Minecraft's so jump heights don't change.
 *
 * <p>Speeds are in blocks per second. One block is taken as 40 Source units,
 * which makes the player 72 units tall like in Half-Life 2.
 */
public final class SourceMovement {
	public static final double UNITS_PER_BLOCK = 40.0;
	/** Normal running speed, 190 units/s. */
	public static final double RUN_SPEED = 190.0 / UNITS_PER_BLOCK;
	/** Sprint speed, 320 units/s. */
	public static final double SPRINT_SPEED = 320.0 / UNITS_PER_BLOCK;
	/** Below this speed friction acts as if moving at it, so you stop quickly (100 units/s). */
	public static final double STOP_SPEED = 100.0 / UNITS_PER_BLOCK;
	public static final double FRICTION = 4.0;
	public static final double ACCELERATE = 10.0;
	public static final double AIR_ACCELERATE = 10.0;
	/** Air control only adds speed up to this along the wished direction (30 units/s). */
	public static final double AIR_SPEED_CAP = 30.0 / UNITS_PER_BLOCK;
	/** Hard limit so speed can't grow past what the server accepts. */
	public static final double MAX_SPEED = 3500.0 / UNITS_PER_BLOCK / 4.0;

	private SourceMovement() {
	}

	/**
	 * Advances horizontal velocity by one step.
	 *
	 * @param velocity   current {x, z} velocity, updated in place
	 * @param wishX      wished direction x (unit length, or zero)
	 * @param wishZ      wished direction z
	 * @param wishSpeed  wished speed
	 * @param grounded   on the ground and not jumping this step
	 * @param surface    how grippy the ground is, 1 for normal blocks, near 0 for ice
	 * @param dt         step length in seconds
	 */
	public static void step(double[] velocity, double wishX, double wishZ, double wishSpeed, boolean grounded, double surface, double dt) {
		if (grounded) {
			friction(velocity, FRICTION * surface, dt);
			accelerate(velocity, wishX, wishZ, wishSpeed, wishSpeed, ACCELERATE * surface, dt);
		} else {
			accelerate(velocity, wishX, wishZ, wishSpeed, Math.min(wishSpeed, AIR_SPEED_CAP), AIR_ACCELERATE, dt);
		}
		double speed = Math.hypot(velocity[0], velocity[1]);
		if (speed > MAX_SPEED) {
			velocity[0] *= MAX_SPEED / speed;
			velocity[1] *= MAX_SPEED / speed;
		}
	}

	static void friction(double[] velocity, double friction, double dt) {
		double speed = Math.hypot(velocity[0], velocity[1]);
		if (speed < 1.0E-4) {
			velocity[0] = 0.0;
			velocity[1] = 0.0;
			return;
		}
		double drop = Math.max(speed, STOP_SPEED) * friction * dt;
		double scale = Math.max(speed - drop, 0.0) / speed;
		velocity[0] *= scale;
		velocity[1] *= scale;
	}

	/**
	 * Adds speed along the wished direction until the speed along it reaches
	 * {@code capSpeed}; the amount added per step scales with the full wished speed.
	 */
	static void accelerate(double[] velocity, double wishX, double wishZ, double wishSpeed, double capSpeed, double accelerate, double dt) {
		if (wishSpeed <= 0.0) {
			return;
		}
		double current = velocity[0] * wishX + velocity[1] * wishZ;
		double add = capSpeed - current;
		if (add <= 0.0) {
			return;
		}
		double gain = Math.min(accelerate * dt * wishSpeed, add);
		velocity[0] += gain * wishX;
		velocity[1] += gain * wishZ;
	}
}
