package com.mcsource.world.hl2;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import com.mcsource.model.source.GameFiles;

/**
 * A world that is one Source map, rebuilt in blocks from the player's own
 * copy of the game, with empty space all round it. The map's name is kept
 * in the world's settings; its blocks are worked out once, when the first
 * chunk of it is made.
 */
public class Hl2MapGenerator extends ChunkGenerator {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");

	public static final MapCodec<Hl2MapGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.getBiomeSource()),
			Codec.STRING.fieldOf("map").forGetter(generator -> generator.map)
	).apply(instance, instance.stable(Hl2MapGenerator::new)));

	private static final int MIN_Y = -64;
	private static final int GEN_DEPTH = 384;

	/** Where the game's files are read from; set by the client, as the server has none of its own. */
	public static volatile Supplier<GameFiles> files = () -> null;
	private static final Map<String, MapVoxels> LOADED = new ConcurrentHashMap<>();
	/** A map that could not be read, so the world isn't left without anything to stand on. */
	private static final MapVoxels NONE = null;

	private final String map;

	public Hl2MapGenerator(BiomeSource biomeSource, String map) {
		super(biomeSource);
		this.map = map;
	}

	public String map() {
		return map;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	/** The map's blocks, read and built the first time they are needed; null if the map can't be read. */
	public MapVoxels voxels() {
		MapVoxels known = LOADED.get(map);
		if (known != null) {
			return known;
		}
		synchronized (LOADED) {
			known = LOADED.get(map);
			if (known != null) {
				return known;
			}
			MapVoxels built = load(map);
			if (built != null) {
				LOADED.put(map, built);
			}
			return built;
		}
	}

	private static MapVoxels load(String map) {
		GameFiles source = files.get();
		if (source == null) {
			LOGGER.warn("No game files to read map {} from", map);
			return NONE;
		}
		try {
			byte[] bytes = source.read("maps/" + map + ".bsp");
			if (bytes == null) {
				LOGGER.warn("Map {} not found in the game files", map);
				return NONE;
			}
			long start = System.nanoTime();
			MapVoxels voxels = new MapVoxels(new Bsp(bytes), source);
			LOGGER.info("Built map {} in blocks: {} x {} x {}, in {} ms", map, voxels.sizeX, voxels.sizeY, voxels.sizeZ,
					(System.nanoTime() - start) / 1_000_000);
			voxels.collision();
			return voxels;
		} catch (Exception e) {
			LOGGER.error("Could not build map {}", map, e);
			return NONE;
		}
	}

	/** The map's exact solid, or null when the map can't be read. */
	public MapCollision collision() {
		MapVoxels voxels = voxels();
		return voxels == null ? null : voxels.collision();
	}

	/** Where a player starts: the map's player start, standing on its floor. */
	public BlockPos spawn() {
		MapVoxels voxels = voxels();
		if (voxels == null) {
			return new BlockPos(0, 65, 0);
		}
		return new BlockPos(voxels.spawnX, MapVoxels.BASE_Y + voxels.spawnY, voxels.spawnZ);
	}

	/** Where a player's feet go at the start, exactly, in world blocks. */
	public double spawnX() {
		MapVoxels voxels = voxels();
		return voxels == null ? 0.5 : voxels.spawnExactX;
	}

	public double spawnFeet() {
		MapVoxels voxels = voxels();
		return voxels == null ? 65.0 : MapVoxels.BASE_Y + voxels.spawnExactY;
	}

	public double spawnZ() {
		MapVoxels voxels = voxels();
		return voxels == null ? 0.5 : voxels.spawnExactZ;
	}

	public float spawnYaw() {
		MapVoxels voxels = voxels();
		return voxels == null ? 0.0F : voxels.spawnYaw;
	}

	private BlockState at(MapVoxels voxels, int x, int y, int z) {
		if (voxels == null) {
			// Nothing could be built: a small stone platform to stand on.
			return y == 64 && Math.abs(x) <= 3 && Math.abs(z) <= 3 ? Blocks.STONE.defaultBlockState() : null;
		}
		return voxels.get(x, y - MapVoxels.BASE_Y, z);
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
			StructureManager structureManager, BiomeManager biomeManager, WorldGenRegion region, Set<Holder<Biome>> biomes) {
		MapVoxels voxels = voxels();
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int chunkMinY = Math.max(chunk.getMinY(), MIN_Y);
		int chunkMaxY = chunk.getMinY() + chunk.getHeight();
		if (voxels != null && (minX + 16 <= 0 || minZ + 16 <= 0 || minX >= voxels.sizeX || minZ >= voxels.sizeZ)) {
			return CompletableFuture.completedFuture(chunk);
		}
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				for (int y = chunkMinY; y < chunkMaxY; y++) {
					BlockState state = at(voxels, minX + lx, y, minZ + lz);
					if (state == null) {
						continue;
					}
					chunk.setBlockState(pos.set(minX + lx, y, minZ + lz), state);
					oceanFloor.update(lx, y, lz, state);
					worldSurface.update(lx, y, lz, state);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		MapVoxels voxels = voxels();
		for (int y = MIN_Y + GEN_DEPTH - 1; y >= MIN_Y; y--) {
			BlockState state = at(voxels, x, y, z);
			if (state != null && type.isOpaque().test(state)) {
				return y + 1;
			}
		}
		return level.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		MapVoxels voxels = voxels();
		BlockState[] states = new BlockState[GEN_DEPTH];
		for (int i = 0; i < GEN_DEPTH; i++) {
			BlockState state = at(voxels, x, MIN_Y + i, z);
			states[i] = state == null ? Blocks.AIR.defaultBlockState() : state;
		}
		return new NoiseColumn(MIN_Y, states);
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
		info.add("MC:Source map " + map);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	/** No trees, flowers or ores: the map is as its maker built it. */
	@Override
	public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
	}

	/** No villages or other structures in the empty space round the map. */
	@Override
	public void createStructures(net.minecraft.core.RegistryAccess registries, net.minecraft.world.level.chunk.ChunkGeneratorStructureState state,
			StructureManager structures, ChunkAccess chunk, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
	}

	@Override
	public int getGenDepth() {
		return GEN_DEPTH;
	}

	@Override
	public int getSeaLevel() {
		return MIN_Y;
	}

	@Override
	public int getMinY() {
		return MIN_Y;
	}
}
