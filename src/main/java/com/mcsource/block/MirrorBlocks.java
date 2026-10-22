package com.mcsource.block;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import com.mcsource.McSource;

/**
 * The mirror block: a plain block whose texture asks the terrain shader for
 * a full reflection (see terrain.fsh), so it shines like a polished panel
 * in a Source map at no cost per frame.
 */
public final class MirrorBlocks {
	private static final ResourceKey<Block> MIRROR_KEY = ResourceKey.create(Registries.BLOCK, McSource.id("mirror_block"));
	private static final ResourceKey<Item> MIRROR_ITEM_KEY = ResourceKey.create(Registries.ITEM, McSource.id("mirror_block"));

	public static final Block MIRROR = Registry.register(BuiltInRegistries.BLOCK, MIRROR_KEY,
			new Block(BlockBehaviour.Properties.of().setId(MIRROR_KEY).mapColor(MapColor.METAL).strength(1.5F, 6.0F)
					.sound(SoundType.GLASS).requiresCorrectToolForDrops()));
	public static final Item MIRROR_ITEM = Registry.register(BuiltInRegistries.ITEM, MIRROR_ITEM_KEY,
			new BlockItem(MIRROR, new Item.Properties().setId(MIRROR_ITEM_KEY).useBlockDescriptionPrefix()));

	private MirrorBlocks() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("building_blocks")))
				.register(output -> output.accept(MIRROR_ITEM));
	}
}
