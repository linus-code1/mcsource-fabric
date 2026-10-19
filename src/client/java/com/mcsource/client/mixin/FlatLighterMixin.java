package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mcsource.client.light.OwnLight;

/**
 * Lets the smoothed ground keep the light it gives each corner: the
 * renderer's flat lighting otherwise raises a quad to its block's light (see
 * {@link OwnLight}). Optional, so another renderer in its place is fine.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.FlatLighter", remap = false)
public abstract class FlatLighterMixin {
	@Inject(method = "light", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcsource$ownLight(CallbackInfoReturnable<Integer> cir) {
		if (OwnLight.active()) {
			cir.setReturnValue(0);
		}
	}
}
