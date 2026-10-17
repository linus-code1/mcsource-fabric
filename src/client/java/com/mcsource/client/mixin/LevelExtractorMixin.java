package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;

import com.mcsource.tree.TreeEntity;

/**
 * Minecraft hides an entity whenever the chunk section its feet stand in is
 * hidden behind terrain. A tree's crown towers far above its foot, so trees
 * behind a hill blinked in and out as the view moved; a tree is drawn
 * whenever its whole height is in view.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void mcsource$treesIgnoreHiddenSections(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks,
			long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof TreeEntity) {
			cir.setReturnValue(levelRenderer.entityRenderDispatcher().shouldRender(entity, frustum, camX, camY, camZ, partialTicks));
		}
	}
}
