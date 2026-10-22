package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.commands.RenderPass;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import com.mcsource.client.lod.LodRenderer;

/** Draws distant terrain first in the solid pass, so the real blocks cover it. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererLodMixin {
	@Inject(method = "executeSolid", at = @At("HEAD"))
	private void mcsource$distantTerrain(ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
			CallbackInfo ci) {
		LodRenderer.draw(pass);
		com.mcsource.client.map.MapRenderer.draw(pass);
	}
}
