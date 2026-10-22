package com.mcsource.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mcsource.world.hl2.MapCollision;

/**
 * In map worlds, things move against the map's own solid (see
 * {@link MapCollision}) instead of the blocks under it, then against any
 * blocks placed since; what they run into turns their motion along it, as
 * in Source, rather than stopping it dead.
 */
@Mixin(Entity.class)
public abstract class EntityMapCollisionMixin {
	/** Surfaces of the map run into on the current move, or null when the move didn't use the map. */
	@Unique
	private double[] mcsource$mapPlanes;

	@Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void mcsource$mapCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		mcsource$mapPlanes = null;
		Entity self = (Entity) (Object) this;
		if (self instanceof Projectile || movement.lengthSqr() == 0.0) {
			return;
		}
		MapCollision map = MapCollision.of(self.level());
		if (map == null) {
			return;
		}
		Vec3 moved = map.move(self.getBoundingBox(), movement, self.maxUpStep(), self.onGround());
		mcsource$mapPlanes = map.lastPlanes();
		// Then the blocks placed in the map (the map's own blocks don't collide for things that move like this).
		var box = self.getBoundingBox();
		var others = self.level().getEntityCollisions(self, box.expandTowards(moved));
		Vec3 result = moved.lengthSqr() == 0.0 ? moved : Entity.collideBoundingBox(self, moved, box, self.level(), others);
		if (!result.equals(moved)) {
			// Something placed was in the way: let the usual rules stop that part.
			mcsource$mapPlanes = null;
		}
		cir.setReturnValue(result);
	}

	@Inject(method = "restituteMovementAfterCollisions(Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)V",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$slideAlongMap(BlockState effectState, boolean xCollision, boolean zCollision, Vec3 movement, CallbackInfo ci) {
		double[] planes = mcsource$mapPlanes;
		if (planes == null || planes.length == 0) {
			return;
		}
		Entity self = (Entity) (Object) this;
		Vec3 v = self.getDeltaMovement();
		double x = v.x;
		double y = v.y;
		double z = v.z;
		for (int i = 0; i < planes.length; i += 3) {
			double nx = planes[i];
			double ny = planes[i + 1];
			double nz = planes[i + 2];
			if (ny >= MapCollision.WALKABLE) {
				// Ground: stop falling, keep running.
				y = Math.max(y, 0.0);
				continue;
			}
			if (ny <= -MapCollision.WALKABLE) {
				y = Math.min(y, 0.0);
				continue;
			}
			double into = x * nx + y * ny + z * nz;
			if (into < 0.0) {
				x -= nx * into;
				y -= ny * into;
				z -= nz * into;
			}
		}
		self.setDeltaMovement(x, y, z);
		ci.cancel();
	}
}
