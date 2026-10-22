package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

import com.mcsource.client.lod.LodTerrain;

/**
 * With distant terrain drawn, the world no longer fades out at the render
 * distance: the haze carries on thickening out to the end of the distant
 * terrain instead. The render distance is still passed as the fog's start,
 * where the haze reaches its usual strength.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void mcsource$distantFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount,
			ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		if (!LodTerrain.active(level)) {
			return;
		}
		FogData fog = cir.getReturnValue();
		fog.renderDistanceStart = renderDistanceInChunks * 16;
		fog.renderDistanceEnd = LodTerrain.RADIUS;
	}
}
