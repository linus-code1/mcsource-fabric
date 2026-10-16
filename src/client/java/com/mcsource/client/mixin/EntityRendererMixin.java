package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.renderer.entity.EntityRenderer;

import com.mcsource.client.SunShadow;

/**
 * Gathers the ground under a wider area than the round shadow needs, so the
 * shadow can be cast long, away from the sun.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@ModifyArg(method = "extractShadow", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(FF)F", ordinal = 0), index = 0)
	private float mcsource$widenShadow(float radius) {
		return radius * SunShadow.REACH;
	}
}
