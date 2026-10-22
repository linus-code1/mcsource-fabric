package com.mcsource.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;

/**
 * A walking bob in the manner of Half-Life 2, in place of Minecraft's. In
 * Half-Life 2 the view itself does not bob at all; only the weapon in hand
 * does, tracing a loose figure of eight: it dips once for every footstep and
 * swings to the side once for every two. So this moves only the first-person
 * hands. It grows smoothly with speed, from a faint bob when sneaking to a
 * fuller one when sprinting, and fades out in the air and when standing
 * still.
 */
public final class ViewBob {
	/** Rise and fall of the hands at a sprint, peak to peak, in blocks. */
	private static final float RISE = 0.05F;
	/** Side to side swing of the hands at a sprint, each way, in blocks. */
	private static final float SWAY = 0.03F;
	/** Roll and nod of the hands at a sprint, in degrees. */
	private static final float ROLL = 1.2F;
	private static final float NOD = 0.8F;
	/** Sprinting speed in blocks a tick, where the bob is full. */
	private static final float SPRINT = 0.28F;
	/** Minecraft's own bob value (smoothed walking speed) tops out here. */
	private static final float FULL_BOB = 0.1F;
	/** How quickly the bob follows changes of speed, per second. */
	private static final float RATE = 8.0F;

	private static long lastNanos;
	private static float speed;

	private ViewBob() {
	}

	/**
	 * Applies the bob to the first-person hands' pose.
	 *
	 * @param walked the walked distance Minecraft bobs by, which advances one
	 *        unit for each footstep
	 * @param bob Minecraft's smoothed walking speed, which drops to zero in
	 *        the air and when stopped
	 */
	public static void apply(float walked, float bob, PoseStack poseStack) {
		long now = Util.getNanos();
		float seconds = Mth.clamp((now - lastNanos) / 1.0E9F, 0.0F, 0.25F);
		lastNanos = now;
		Entity entity = Minecraft.getInstance().getCameraEntity();
		float measured = entity == null ? 0.0F
				: (float) Math.sqrt(Mth.square(entity.getX() - entity.xo) + Mth.square(entity.getZ() - entity.zo));
		speed += (measured - speed) * (1.0F - (float) Math.exp(-RATE * seconds));

		// On the ground and moving (fades smoothly), times how fast.
		float grounded = Mth.clamp(bob / FULL_BOB, 0.0F, 1.0F);
		float pace = Mth.clamp(speed / SPRINT, 0.0F, 1.2F);
		float amount = grounded * pace;
		if (amount <= 1.0E-4F) {
			return;
		}

		// One side-to-side cycle every two steps; the hands are lowest as each foot lands.
		float phase = walked * Mth.PI;
		float side = Mth.sin(phase);
		float rise = 0.5F * Mth.cos(2.0F * phase);
		poseStack.translate(side * SWAY * amount, -rise * RISE * amount, 0.0F);
		poseStack.rotateDegrees(Axis.ZP, side * ROLL * amount);
		poseStack.rotateDegrees(Axis.XP, -rise * NOD * amount);
	}
}
