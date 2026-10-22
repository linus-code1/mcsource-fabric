package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Lays the still water and lava textures over two blocks each way instead of
 * one, by each block's place in the world, so their pattern no longer comes
 * back on every block. Flowing faces are left as Minecraft maps them.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidUvMixin {
	private static final int SPREAD = 2;

	@Inject(method = "vertex", at = @At("HEAD"), cancellable = true)
	private void mcsource$spread(VertexConsumer consumer, float x, float y, float z, int colour, float u, float v, int light,
			CallbackInfo ci) {
		TextureAtlasSprite sprite = stillAt(u, v);
		if (sprite == null) {
			return;
		}
		float su = (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
		float sv = (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
		// Still tops map u along x and v along z, a whole block per face, so
		// the block a corner belongs to is its place less how far it is across.
		int cellX = Math.round(x - su);
		int cellZ = Math.round(z - sv);
		float nu = (Math.floorMod(cellX, SPREAD) + Mth.clamp(su, 0.0F, 1.0F)) / SPREAD;
		float nv = (Math.floorMod(cellZ, SPREAD) + Mth.clamp(sv, 0.0F, 1.0F)) / SPREAD;
		consumer.addVertex(x, y, z, colour, sprite.getU(nu), sprite.getV(nv), OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
		ci.cancel();
	}

	/** The still water or lava sprite the coordinates fall in, or null. */
	private static TextureAtlasSprite stillAt(float u, float v) {
		var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		for (String name : new String[] {"block/water_still", "block/lava_still"}) {
			TextureAtlasSprite sprite = atlas.getSprite(Identifier.withDefaultNamespace(name));
			float e = 1.0E-5F;
			if (u >= sprite.getU0() - e && u <= sprite.getU1() + e && v >= sprite.getV0() - e && v <= sprite.getV1() + e) {
				return sprite;
			}
		}
		return null;
	}
}
