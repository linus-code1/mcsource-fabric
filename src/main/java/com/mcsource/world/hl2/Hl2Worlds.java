package com.mcsource.world.hl2;

import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

import com.mcsource.McSource;

/** Worlds made from Source maps: their generator, and starting players at the map's player start. */
public final class Hl2Worlds {
	/** Marks a player who has been put at the map's start once, so later joins leave them where they were. */
	private static final String STARTED_TAG = "mcsource_map_start";
	private static final ResourceKey<Block> MAP_SOLID_KEY = ResourceKey.create(Registries.BLOCK, McSource.id("map_solid"));
	/** The unseen solid a map world is built of; the map itself is drawn over it. */
	public static final Block MAP_SOLID = Registry.register(BuiltInRegistries.BLOCK, MAP_SOLID_KEY, new MapSolidBlock(
			BlockBehaviour.Properties.of().setId(MAP_SOLID_KEY).strength(-1.0F, 3600000.0F).noLootTable().noOcclusion().noTerrainParticles()
					.isSuffocating((state, level, pos) -> false).isRedstoneConductor((state, level, pos) -> false)
					.pushReaction(PushReaction.IMMOVEABLE)));

	private Hl2Worlds() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, McSource.id("hl2_map"), Hl2MapGenerator.CODEC);
		ServerLevelEvents.LOAD.register((server, level) -> {
			if (level.dimension() == Level.OVERWORLD && level.getChunkSource().getGenerator() instanceof Hl2MapGenerator map) {
				BlockPos spawn = map.spawn();
				level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spawn, map.spawnYaw(), 0.0F));
				// Exactly at the start, not somewhere random nearby inside a wall.
				level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			ServerLevel overworld = server.overworld();
			if (!(overworld.getChunkSource().getGenerator() instanceof Hl2MapGenerator map) || player.entityTags().contains(STARTED_TAG)) {
				return;
			}
			player.addTag(STARTED_TAG);
			BlockPos spawn = map.spawn();
			player.teleportTo(overworld, map.spawnX(), map.spawnFeet(), map.spawnZ(), Set.of(), map.spawnYaw(), 0.0F, true);
		});
	}
}
