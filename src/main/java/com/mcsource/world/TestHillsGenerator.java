package com.mcsource.world;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
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

/**
 * Test world: rolling hills of natural terrain (smoothed) with a stone brick
 * house near spawn (kept sharp), so both looks can be compared side by side.
 */
public class TestHillsGenerator extends ChunkGenerator {
	public static final MapCodec<TestHillsGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.getBiomeSource())
	).apply(instance, instance.stable(TestHillsGenerator::new)));

	private static final int MIN_Y = -64;
	private static final int GEN_DEPTH = 384;
	private static final int SEA_LEVEL = 63;

	// The house occupies x and z from HOUSE_MIN to HOUSE_MAX, inside chunk (0, 0).
	private static final int HOUSE_MIN = 3;
	private static final int HOUSE_MAX = 12;
	private static final int HOUSE_WALL_HEIGHT = 4;

	public TestHillsGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	/** Ground height: the y of the top grass block. */
	static int groundHeight(int x, int z) {
		double h = 70.0
				+ 7.0 * Math.sin(x * 0.045) * Math.cos(z * 0.05)
				+ 4.0 * Math.sin((x + z) * 0.021 + 1.3)
				+ 2.5 * Math.sin(x * 0.13 - z * 0.07);
		return Mth.floor(h);
	}

	private static boolean inHouse(int x, int z) {
		return x >= HOUSE_MIN && x <= HOUSE_MAX && z >= HOUSE_MIN && z <= HOUSE_MAX;
	}

	private static int houseFloor() {
		return groundHeight((HOUSE_MIN + HOUSE_MAX) / 2, (HOUSE_MIN + HOUSE_MAX) / 2);
	}

	/** The full column of block states from MIN_Y upwards; trailing entries are air. */
	static BlockState[] column(int x, int z) {
		int ground = groundHeight(x, z);
		int top = Math.max(ground, houseFloor() + HOUSE_WALL_HEIGHT);
		BlockState[] states = new BlockState[top - MIN_Y + 1];
		Arrays.fill(states, Blocks.AIR.defaultBlockState());

		for (int y = MIN_Y; y <= ground; y++) {
			BlockState state;
			if (y == MIN_Y) {
				state = Blocks.BEDROCK.defaultBlockState();
			} else if (y < ground - 3) {
				state = Blocks.STONE.defaultBlockState();
			} else if (y < ground) {
				state = Blocks.DIRT.defaultBlockState();
			} else {
				state = Blocks.GRASS_BLOCK.defaultBlockState();
			}
			states[y - MIN_Y] = state;
		}

		if (inHouse(x, z)) {
			int floor = houseFloor();
			for (int y = ground + 1; y < floor; y++) {
				states[y - MIN_Y] = Blocks.STONE_BRICKS.defaultBlockState();
			}
			for (int y = floor + 1; y <= ground; y++) {
				states[y - MIN_Y] = Blocks.AIR.defaultBlockState();
			}
			states[floor - MIN_Y] = Blocks.OAK_PLANKS.defaultBlockState();

			boolean wall = x == HOUSE_MIN || x == HOUSE_MAX || z == HOUSE_MIN || z == HOUSE_MAX;
			int mid = (HOUSE_MIN + HOUSE_MAX) / 2;
			boolean doorway = z == HOUSE_MAX && (x == mid || x == mid + 1);
			if (wall) {
				for (int y = floor + 1; y <= floor + HOUSE_WALL_HEIGHT; y++) {
					boolean opening = doorway && y <= floor + 2;
					states[y - MIN_Y] = opening ? Blocks.AIR.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
				}
			}
		}
		return states;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
			StructureManager structureManager, BiomeManager biomeManager, WorldGenRegion region, Set<Holder<Biome>> biomes) {
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight();

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				BlockState[] states = column(minX + lx, minZ + lz);
				for (int i = 0; i < states.length; i++) {
					BlockState state = states[i];
					int y = MIN_Y + i;
					if (state.isAir() || y < chunkMinY || y >= chunkMaxY) {
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
		BlockState[] states = column(x, z);
		for (int i = states.length - 1; i >= 0; i--) {
			if (type.isOpaque().test(states[i])) {
				return MIN_Y + i + 1;
			}
		}
		return level.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		return new NoiseColumn(MIN_Y, column(x, z));
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
		info.add("MC:Source test hills, ground " + groundHeight(pos.getX(), pos.getZ()));
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getGenDepth() {
		return GEN_DEPTH;
	}

	@Override
	public int getSeaLevel() {
		return SEA_LEVEL;
	}

	@Override
	public int getMinY() {
		return MIN_Y;
	}
}
