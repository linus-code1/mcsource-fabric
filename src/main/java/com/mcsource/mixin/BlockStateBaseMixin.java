package com.mcsource.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.mcsource.terrain.SmoothMath;
import com.mcsource.terrain.SmoothShapes;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Gives smoothed terrain the same shape for collision, the block outline and
 * ray picking on both the client and the server.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$smoothCollision(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		mcsource$applySmoothShape(level, pos, cir);
	}

	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$smoothCachedCollision(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		mcsource$applySmoothShape(level, pos, cir);
	}

	@Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			at = @At("HEAD"), cancellable = true)
	private void mcsource$smoothOutline(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		mcsource$applySmoothShape(level, pos, cir);
	}

	private void mcsource$applySmoothShape(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		BlockState self = (BlockState) (Object) this;
		int packed = SmoothTerrain.corners(level, pos, self);
		if (packed != SmoothMath.FULL) {
			cir.setReturnValue(SmoothShapes.shape(packed));
		}
	}
}
