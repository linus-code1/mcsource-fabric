package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import com.mcsource.client.light.SourceLight;

/**
 * Mobs, items and the player's own hands take the same light as the ground
 * round them (see SourceLight): they read it from the block they stand in,
 * past the hook in {@link LightCoordsMixin}.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityLightMixin {
	@Inject(method = "getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I", at = @At("RETURN"), cancellable = true)
	private void mcsource$heldLight(Entity entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
		BlockPos at = BlockPos.containing(entity.getLightProbePosition(partialTick));
		cir.setReturnValue(SourceLight.at(entity.level(), entity.level().getBlockState(at), at, cir.getReturnValueI()));
	}
}
