package com.mcsource.client.lod;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.Fluids;

import com.mcsource.client.mixin.SpriteContentsAccessor;

/**
 * Distant terrain: what lies past the loaded chunks, as a coarse coloured
 * height field that runs on to the horizon instead of the world stopping
 * at the render distance.
 *
 * Every chunk the client receives leaves behind a few samples of its ground
 * (height and colour every four blocks), kept after the chunk unloads, so
 * land once seen stays drawn. Where nothing has been seen, the land is
 * predicted: in single player from the world generator itself (the height
 * of the ground and its biome, without trees or buildings), and on a server
 * by carrying the known land on outwards, easing towards sea level.
 */
public final class LodTerrain {
	/** How far out distant terrain reaches, in blocks from the player. */
	public static final int RADIUS = 2048;
	/** The far clipping plane needed to draw it all, corners included. */
	public static final float DEPTH_FAR = RADIUS * 1.5F + 512.0F;

	/** Samples a chunk keeps: four by four, one every four blocks. */
	static final int SAMPLES = 4;
	static final int UNKNOWN = Integer.MIN_VALUE;

	/** Ground samples of chunks seen, by chunk: heights then colours. */
	static final Map<Long, int[]> COLUMNS = new ConcurrentHashMap<>();
	/** Chunks loaded now; distant terrain leaves them to the real blocks. */
	static final Set<Long> LOADED = ConcurrentHashMap.newKeySet();
	/** Predictions from the world generator, by block position. */
	static final Map<Long, Long> PREDICTED = new ConcurrentHashMap<>();
	/** Average colours of block textures, by block. */
	private static final Map<Block, Integer> TEXTURE_COLOURS = new ConcurrentHashMap<>();
	/** Colours of the blocks predicted ground is made of, worked out on the render thread. */
	static final Map<Block, Integer> PALETTE = new ConcurrentHashMap<>();

	/** Bumped whenever the samples or the loaded chunks change. */
	static volatile int changes;
	private static @Nullable ClientLevel level;
	static volatile @Nullable ServerLevel serverLevel;
	static volatile int seaLevel = 63;
	static volatile int minY = -64;

	private LodTerrain() {
	}

	public static void register() {
		ClientChunkEvents.CHUNK_LOAD.register(LodTerrain::loaded);
		ClientChunkEvents.CHUNK_UNLOAD.register((clientLevel, chunk) -> {
			if (clientLevel == level && LOADED.remove(chunk.getPos().pack())) {
				changes++;
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(null));
	}

	/** Whether distant terrain is drawn in this level: open sky, no ceiling, not the End. */
	public static boolean active(@Nullable ClientLevel clientLevel) {
		return clientLevel != null && clientLevel.dimensionType().hasSkyLight() && !clientLevel.dimensionType().hasCeiling()
				&& clientLevel.dimension() != Level.END;
	}

	public static boolean active() {
		return active(Minecraft.getInstance().level);
	}

	/** Makes sure the samples belong to the level being played; called on the render thread. */
	static void follow(@Nullable ClientLevel clientLevel) {
		if (clientLevel != level) {
			reset(clientLevel);
		}
	}

	private static synchronized void reset(@Nullable ClientLevel clientLevel) {
		level = clientLevel;
		COLUMNS.clear();
		LOADED.clear();
		PREDICTED.clear();
		TEXTURE_COLOURS.clear();
		PALETTE.clear();
		serverLevel = null;
		changes++;
		LodRenderer.clear();
		if (clientLevel == null) {
			return;
		}
		minY = clientLevel.getMinY();
		seaLevel = clientLevel.getSeaLevel();
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			ServerLevel own = server.getLevel(clientLevel.dimension());
			if (own != null) {
				serverLevel = own;
				seaLevel = own.getChunkSource().getGenerator().getSeaLevel();
			}
		}
		for (Block block : new Block[] {Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.RED_SAND, Blocks.TERRACOTTA, Blocks.SNOW_BLOCK,
				Blocks.STONE, Blocks.MYCELIUM, Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.WATER,
				Blocks.GRAVEL, Blocks.PODZOL, Blocks.MUD}) {
			PALETTE.put(block, textureColour(block.defaultBlockState()));
		}
	}

	private static void loaded(ClientLevel clientLevel, LevelChunk chunk) {
		follow(clientLevel);
		if (!active(clientLevel)) {
			return;
		}
		long key = chunk.getPos().pack();
		COLUMNS.put(key, sample(clientLevel, chunk));
		LOADED.add(key);
		changes++;
	}

	/** Reads a chunk's ground: its height and colour every four blocks. */
	private static int[] sample(ClientLevel clientLevel, LevelChunk chunk) {
		int[] out = new int[SAMPLES * SAMPLES * 2];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < SAMPLES; i++) {
			for (int j = 0; j < SAMPLES; j++) {
				int lx = i * 4 + 2;
				int lz = j * 4 + 2;
				int index = i + j * SAMPLES;
				int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
				if (y < clientLevel.getMinY()) {
					out[index] = UNKNOWN;
					continue;
				}
				pos.set(baseX + lx, y, baseZ + lz);
				BlockState state = chunk.getBlockState(pos);
				int top = y + 1;
				BlockState above = chunk.getBlockState(pos.above());
				if (above.is(Blocks.SNOW)) {
					state = Blocks.SNOW_BLOCK.defaultBlockState();
				}
				out[index] = top;
				out[SAMPLES * SAMPLES + index] = colour(state, clientLevel, pos);
			}
		}
		return out;
	}

	/** The colour a block shows from above in the world: its texture's average, tinted. */
	private static int colour(BlockState state, ClientLevel clientLevel, BlockPos pos) {
		int texture = textureColour(state);
		int tint = -1;
		if (state.getFluidState().is(Fluids.WATER) && !state.isSolidRender()) {
			tint = BiomeColors.getAverageWaterColor(clientLevel, pos);
		} else {
			var source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
			if (source != null) {
				tint = source.colorInWorld(state, clientLevel, pos);
			}
		}
		return tint == -1 ? texture : ARGB.multiply(texture, 0xFF000000 | tint);
	}

	/** A block texture's average colour, taken from the texture on top of the block. */
	static int textureColour(BlockState state) {
		Block block = state.getBlock();
		Integer known = TEXTURE_COLOURS.get(block);
		if (known != null) {
			return known;
		}
		int found = averageOf(topSprite(state));
		if (found == UNKNOWN) {
			found = 0xFF000000 | state.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		}
		TEXTURE_COLOURS.put(block, found);
		return found;
	}

	private static @Nullable TextureAtlasSprite topSprite(BlockState state) {
		try {
			var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
			Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
			String path = state.is(Blocks.WATER) ? "water_still" : id.getPath();
			for (String name : new String[] {"block/" + path + "_top", "block/" + path}) {
				TextureAtlasSprite sprite = atlas.getSprite(Identifier.fromNamespaceAndPath(id.getNamespace(), name));
				if (sprite != null && !sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
					return sprite;
				}
			}
			return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).particleMaterial().sprite();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static int averageOf(@Nullable TextureAtlasSprite sprite) {
		if (sprite == null) {
			return UNKNOWN;
		}
		try {
			var image = ((SpriteContentsAccessor) sprite.contents()).mcsource$originalImage();
			if (image == null || image.isClosed()) {
				return UNKNOWN;
			}
			int width = sprite.contents().width();
			int height = sprite.contents().height();
			int stride = Math.max(1, Math.min(width, height) / 32);
			long r = 0;
			long g = 0;
			long b = 0;
			long n = 0;
			for (int y = 0; y < height; y += stride) {
				for (int x = 0; x < width; x += stride) {
					int pixel = image.getPixel(x, y);
					if (ARGB.alpha(pixel) < 128) {
						continue;
					}
					r += ARGB.red(pixel);
					g += ARGB.green(pixel);
					b += ARGB.blue(pixel);
					n++;
				}
			}
			if (n == 0) {
				return UNKNOWN;
			}
			return ARGB.color(255, (int) (r / n), (int) (g / n), (int) (b / n));
		} catch (RuntimeException e) {
			return UNKNOWN;
		}
	}

	static long key(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	static long pack(int height, int colour) {
		return (long) height << 32 | colour & 0xFFFFFFFFL;
	}

	/**
	 * The ground the world generator would make at a block column: its height
	 * and colour packed together. Only in single player, where the generator
	 * is at hand; trees and buildings are not in it.
	 */
	static long predict(ServerLevel server, int x, int z) {
		long k = key(x, z);
		Long known = PREDICTED.get(k);
		if (known != null) {
			return known;
		}
		long made;
		try {
			made = generate(server, x, z);
		} catch (RuntimeException e) {
			made = pack(seaLevel, PALETTE.getOrDefault(Blocks.GRASS_BLOCK, 0xFF6A8F3A));
		}
		if (PREDICTED.size() > 600_000) {
			PREDICTED.clear();
		}
		PREDICTED.put(k, made);
		return made;
	}

	private static long generate(ServerLevel server, int x, int z) {
		ChunkGenerator generator = server.getChunkSource().getGenerator();
		RandomState random = server.getChunkSource().randomState();
		int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, server, random);
		int sea = generator.getSeaLevel();
		Holder<Biome> biome = server.getUncachedNoiseBiome(x >> 2, Math.max(ground, sea) >> 2, z >> 2);
		if (ground < sea) {
			int water = ARGB.multiply(PALETTE.getOrDefault(Blocks.WATER, 0xFF3F76E4), 0xFF000000 | biome.value().getWaterColor());
			// Deeper water looks darker from above.
			float depth = Math.min((sea - ground) / 24.0F, 1.0F);
			water = ARGB.scaleRGB(water, 1.0F - 0.45F * depth);
			return pack(sea, water);
		}
		String name = biome.getRegisteredName();
		int grass = ARGB.multiply(PALETTE.getOrDefault(Blocks.GRASS_BLOCK, 0xFF8F8F8F), 0xFF000000 | biome.value().getGrassColor(x, z));
		int foliage = biome.value().getFoliageColor();
		int colour;
		int height = ground;
		if (name.contains("snow") || name.contains("frozen") || name.contains("ice") || name.contains("jagged") || name.contains("grove")) {
			colour = PALETTE.getOrDefault(Blocks.SNOW_BLOCK, 0xFFF0F5F5);
		} else if (name.contains("desert") || name.contains("beach")) {
			colour = PALETTE.getOrDefault(Blocks.SAND, 0xFFDBD3A0);
		} else if (name.contains("badlands")) {
			colour = PALETTE.getOrDefault(Blocks.TERRACOTTA, 0xFF985E43);
		} else if (name.contains("stony") || name.contains("peaks")) {
			colour = PALETTE.getOrDefault(Blocks.STONE, 0xFF7D7D7D);
		} else if (name.contains("mushroom")) {
			colour = PALETTE.getOrDefault(Blocks.MYCELIUM, 0xFF6F6265);
		} else if (name.contains("forest") || name.contains("taiga") || name.contains("jungle") || name.contains("woodland")
				|| name.contains("swamp") || name.contains("cherry")) {
			// Wooded land: mostly treetops, standing above the ground.
			Block leaves = name.contains("taiga") ? Blocks.SPRUCE_LEAVES : name.contains("jungle") ? Blocks.JUNGLE_LEAVES : Blocks.OAK_LEAVES;
			int canopy = ARGB.multiply(PALETTE.getOrDefault(leaves, 0xFF808080), 0xFF000000 | foliage);
			colour = ARGB.srgbLerp(0.3F, canopy, grass);
			height = ground + (name.contains("jungle") ? 10 : 6);
		} else {
			colour = grass;
		}
		return pack(height, colour);
	}

}
