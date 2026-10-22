package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;

/** Reaches the layers of a held item's render state, so it can be held in a Half-Life 2 fist (see {@code HeldItems}). */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] mcsource$layers();

	@Accessor("activeLayerCount")
	int mcsource$activeLayerCount();

	@Accessor("displayContext")
	ItemDisplayContext mcsource$displayContext();
}
