package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.client.prop.PropModels;

/**
 * A furniture prop doesn't fill its block, so the blocks beside it keep the
 * faces that would hide behind a full crafting table or furnace.
 */
@Mixin(Block.class)
public abstract class PropCullMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private static void mcsource$showFacesBesideProps(BlockState state, BlockState neighbour, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		if (PropModels.isProp(neighbour)) {
			cir.setReturnValue(true);
		}
	}
}
