package com.mcsource.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import com.mcsource.movement.SourceMovement;

/**
 * Gives the player Source-style movement on land and in the air. Only the
 * player's own client runs it; Minecraft lets that client decide where its
 * player moves, so the server needs nothing extra.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow
	protected boolean jumping;

	@Shadow
	protected abstract double getEffectiveGravity();

	@Shadow
	public abstract boolean onClimbable();

	private boolean mcsource$usesSourceMovement() {
		LivingEntity self = (LivingEntity) (Object) this;
		return self instanceof Player player && player.isLocalPlayer() && !player.getAbilities().flying && !player.isPassenger()
				&& !self.shouldDiscardFriction() && !onClimbable() && !self.hasEffect(MobEffects.LEVITATION);
	}

	@Inject(method = "travelInAir(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
	private void mcsource$sourceTravel(Vec3 input, CallbackInfo ci) {
		if (!mcsource$usesSourceMovement()) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		Vec3 motion = self.getDeltaMovement();

		// Wished direction from the movement keys, turned by where the player looks.
		double wishX = 0.0;
		double wishZ = 0.0;
		double inputLength = Math.hypot(input.x, input.z);
		if (inputLength > 1.0E-4) {
			float sin = Mth.sin(self.getYRot() * Mth.DEG_TO_RAD);
			float cos = Mth.cos(self.getYRot() * Mth.DEG_TO_RAD);
			wishX = (input.x * cos - input.z * sin) / inputLength;
			wishZ = (input.z * cos + input.x * sin) / inputLength;
		}
		// Keys give 0.98 at full press; sneaking and using items scale it down.
		double press = Math.min(inputLength / 0.98, 1.0);
		double speedScale = self.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1;
		double topSpeed = self.isSprinting() ? SourceMovement.SPRINT_SPEED / 1.3 : SourceMovement.RUN_SPEED;
		double wishSpeed = press * topSpeed * speedScale;

		boolean grounded = self.onGround() && !(jumping && motion.y > 0.0);
		double surface = 1.0;
		if (grounded) {
			BlockPos below = self.getBlockPosBelowThatAffectsMyMovement();
			float slip = self.level().getBlockState(below).getBlock().getFriction();
			// Normal blocks have 0.6; ice 0.98 grips about twenty times less.
			surface = Mth.clamp((1.0 - slip) / 0.4, 0.02, 1.0);
		}

		double[] velocity = {motion.x * 20.0, motion.z * 20.0};
		SourceMovement.step(velocity, wishX, wishZ, wishSpeed, grounded, surface, 0.05);
		self.setDeltaMovement(velocity[0] / 20.0, motion.y, velocity[1] / 20.0);
		self.move(MoverType.SELF, self.getDeltaMovement());

		// Vertical motion as in vanilla: gravity, then air drag.
		Vec3 moved = self.getDeltaMovement();
		double y = moved.y;
		if (!self.level().isClientSide() || self.level().hasChunkAt(self.blockPosition())) {
			y -= getEffectiveGravity();
		} else {
			y = self.getY() > self.level().getMinY() ? -0.1 : 0.0;
		}
		self.setDeltaMovement(moved.x, y * 0.98, moved.z);
		ci.cancel();
	}

	/** No free speed boost for jumping while sprinting; speed comes from strafing instead. */
	@Redirect(method = "jumpFromGround()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSprinting()Z"))
	private boolean mcsource$noSprintJumpBoost(LivingEntity self) {
		return self.isSprinting() && !mcsource$usesSourceMovement();
	}
}
