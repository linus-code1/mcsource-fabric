package com.mcsource.client.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Smooths the eye over steps, as Source does on stairs: when the player
 * walks up or down onto ground at another height, the view glides to the new
 * height instead of jumping there, so walking over the stepped collision of
 * smoothed terrain feels like walking up a slope.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	/** How far behind the view may lag, in blocks; more than a step snaps. */
	@Unique
	private static final double MAX_LAG = 1.1;
	/** How quickly the lag closes, per second. */
	@Unique
	private static final double RATE = 14.0;

	@Shadow
	private @Nullable Entity entity;
	@Shadow
	private boolean detached;

	@Shadow
	public abstract Vec3 position();

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Unique
	private double mcsource$eyeY = Double.NaN;
	@Unique
	private long mcsource$lastNanos;
	@Unique
	private @Nullable Entity mcsource$lastEntity;

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void mcsource$smoothSteps(float partialTicks, CallbackInfo ci) {
		long now = Util.getNanos();
		double seconds = Math.min((now - mcsource$lastNanos) / 1.0E9, 0.25);
		mcsource$lastNanos = now;
		if (detached || entity == null) {
			mcsource$eyeY = Double.NaN;
			return;
		}
		Vec3 here = position();
		double lag = mcsource$eyeY - here.y;
		// On the ground, or just dropping off a small step; a jump or a fall keeps
		// the view on the player.
		boolean walking = (entity.onGround() || lag > 0.0 && entity.fallDistance < 0.6) && !entity.isPassenger() && !entity.isInWater();
		if (entity != mcsource$lastEntity || Double.isNaN(lag) || !walking || Math.abs(lag) > MAX_LAG) {
			mcsource$lastEntity = entity;
			mcsource$eyeY = here.y;
			return;
		}
		lag *= Math.exp(-RATE * seconds);
		mcsource$eyeY = here.y + lag;
		setPosition(new Vec3(here.x, mcsource$eyeY, here.z));
	}
}
