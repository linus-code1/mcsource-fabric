package com.mcsource.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mcsource.client.light.SourceLight;

/** Adds the light of a torch held in the player's hand to the light blocks and things are drawn with. */
@Mixin(LightCoordsUtil.class)
public abstract class LightCoordsMixin {
	@Inject(method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
			at = @At("RETURN"), cancellable = true)
	private static void mcsource$heldLight(LightCoordsUtil.BrightnessGetter getter, BlockAndLightGetter level, BlockState state, BlockPos pos,
			CallbackInfoReturnable<Integer> cir) {
		int packed = cir.getReturnValueI();
		int lit = SourceLight.at(level, state, pos, packed);
		if (lit != packed) {
			cir.setReturnValue(lit);
		}
	}
}
