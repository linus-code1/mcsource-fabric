package com.mcsource.kart;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import com.mcsource.McSource;

/** Registers the kart and the item that places it. */
public final class Karts {
	private static final ResourceKey<EntityType<?>> KART_KEY = ResourceKey.create(Registries.ENTITY_TYPE, McSource.id("kart"));
	private static final ResourceKey<Item> KART_ITEM_KEY = ResourceKey.create(Registries.ITEM, McSource.id("kart"));

	public static final EntityType<KartEntity> KART = Registry.register(BuiltInRegistries.ENTITY_TYPE, KART_KEY,
			EntityType.Builder.<KartEntity>of(KartEntity::new, MobCategory.MISC)
					.sized(2.3F, 0.9F)
					// The driver sits low in the seat, a little behind the middle.
					.passengerAttachments(new Vec3(0.0, 0.42, -0.25))
					.clientTrackingRange(10)
					.build(KART_KEY));
	public static final Item KART_ITEM = Registry.register(BuiltInRegistries.ITEM, KART_ITEM_KEY,
			new KartItem(new Item.Properties().setId(KART_ITEM_KEY).stacksTo(1)));

	private Karts() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(KART, KartEntity.createAttributes());
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
				.register(output -> output.accept(KART_ITEM));
	}
}
