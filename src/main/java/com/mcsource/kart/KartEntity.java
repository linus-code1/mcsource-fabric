package com.mcsource.kart;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A go-kart you can sit in and drive: W and S for throttle and brake, A and D
 * to steer. It grips the road at low speed and slides a little through fast
 * corners. Hitting it pops it back into an item.
 */
public class KartEntity extends Mob {
	/** Top speed forwards and backwards, in blocks per tick. */
	private static final double MAX_SPEED = 0.75;
	private static final double MAX_REVERSE = 0.22;
	private static final double ACCELERATION = 0.032;
	private static final double BRAKE = 0.07;
	/** Speed kept each tick with no throttle. */
	private static final double COAST = 0.985;
	/** How quickly sideways sliding turns into forward motion on the ground. */
	private static final double GRIP = 0.3;
	/** Steering rate at speed, in degrees per tick. */
	private static final float TURN_RATE = 5.5F;
	private static final double GRAVITY = 0.08;

	/** Forward speed along the kart's heading, in blocks per tick. */
	private double speed;
	private float steering;
	/** Distance driven, signed (reversing counts down), for turning the wheels. */
	public float travelled;
	public float travelledO;

	public KartEntity(EntityType<? extends KartEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 8.0).add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!isVehicle() && !player.isSecondaryUseActive()) {
			if (!level().isClientSide()) {
				player.startRiding(this);
			}
			return InteractionResult.SUCCESS;
		}
		return super.mobInteract(player, hand);
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : super.getControllingPassenger();
	}

	@Override
	protected Vec3 getRiddenInput(Player controller, Vec3 selfInput) {
		return new Vec3(controller.xxa, 0.0, controller.zza);
	}

	@Override
	protected void tickRidden(Player controller, Vec3 input) {
		super.tickRidden(controller, input);
		double throttle = input.z;
		if (throttle > 0.0) {
			speed += speed < 0.0 ? BRAKE : ACCELERATION;
		} else if (throttle < 0.0) {
			speed -= speed > 0.0 ? BRAKE : ACCELERATION * 0.6;
		} else {
			speed *= COAST;
		}
		speed = Mth.clamp(speed, -MAX_REVERSE, MAX_SPEED);

		steering += ((float) input.x - steering) * 0.35F;
		// Steering only bites when rolling, and reverses when backing up.
		float grip = (float) Mth.clamp(Math.abs(speed) / 0.25, 0.0, 1.0);
		float turn = steering * TURN_RATE * grip * (speed < 0.0 ? -1.0F : 1.0F);
		setYRot(getYRot() - turn);
		yBodyRot = getYRot();
		yHeadRot = getYRot();
		// The driver turns with the kart, like in a boat.
		controller.setYRot(controller.getYRot() - turn);
		controller.setYHeadRot(controller.getYHeadRot() - turn);
	}

	@Override
	public void travel(Vec3 input) {
		if (getControllingPassenger() == null) {
			speed *= 0.9;
		}
		if (isInWater()) {
			speed *= 0.8;
		}
		Vec3 heading = Vec3.directionFromRotation(0.0F, getYRot());
		Vec3 motion = getDeltaMovement();
		double grip = onGround() ? GRIP : 0.02;
		double x = Mth.lerp(grip, motion.x, heading.x * speed);
		double z = Mth.lerp(grip, motion.z, heading.z * speed);
		double y = (motion.y - GRAVITY) * 0.98;
		setDeltaMovement(x, y, z);
		move(MoverType.SELF, getDeltaMovement());
		if (horizontalCollision) {
			speed *= 0.5;
		}
		if (onGround() && getDeltaMovement().y < 0.0) {
			setDeltaMovement(getDeltaMovement().x, 0.0, getDeltaMovement().z);
		}
	}

	@Override
	public void tick() {
		super.tick();
		yBodyRot = getYRot();
		yHeadRot = getYRot();
		Vec3 heading = Vec3.directionFromRotation(0.0F, getYRot());
		travelledO = travelled;
		travelled += (float) ((getX() - xo) * heading.x + (getZ() - zo) * heading.z);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.getEntity() instanceof Player player) {
			if (!player.getAbilities().instabuild) {
				spawnAtLocation(level, new ItemStack(Karts.KART_ITEM));
			}
			discard();
			return true;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}
}
