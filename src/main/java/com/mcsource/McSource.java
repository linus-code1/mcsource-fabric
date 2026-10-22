package com.mcsource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import com.mcsource.kart.Karts;
import com.mcsource.tree.Trees;
import com.mcsource.world.TestHillsGenerator;

public class McSource implements ModInitializer {
	public static final String MOD_ID = "mcsource";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("test_hills"), TestHillsGenerator.CODEC);
		Karts.register();
		Trees.register();
		com.mcsource.terrain.ServerSupport.register();
		com.mcsource.world.hl2.Hl2Worlds.register();
		LOGGER.info("MC:Source loaded");
	}
}
