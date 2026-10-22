package com.mcsource.client.mixin;

import net.minecraft.client.renderer.chunk.RenderSectionRegion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sections a chunk build can read, so light rays stay inside them. */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {
	@Accessor("minSectionX")
	int mcsource$minSectionX();

	@Accessor("minSectionY")
	int mcsource$minSectionY();

	@Accessor("minSectionZ")
	int mcsource$minSectionZ();
}
