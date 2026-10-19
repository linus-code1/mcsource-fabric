package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.client.light.Emitters;

/** Keeps the list of lamps up to date as blocks change on the client. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
			at = @At("HEAD"))
	private void mcsource$lampChanged(BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
		Emitters.changed((ClientLevel) (Object) this, pos, before, after);
	}
}
