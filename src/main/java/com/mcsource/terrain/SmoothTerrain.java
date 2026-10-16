package com.mcsource.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides which blocks are smoothed and how. Runs identically on the client
 * (meshing) and the server (collision) because it only reads block states.
 */
public final class SmoothTerrain {
	/** Natural terrain that gets a smooth top. Anything else stays a sharp cube. */
	public static final TagKey<Block> SMOOTH = tag("smooth_terrain");
	/** Plants and covers that sink with the smoothed ground below them. */
	public static final TagKey<Block> FOLLOWS = tag("follows_terrain");

	private static final int OPEN = 0;
	private static final int TERRAIN = 1;
	private static final int BUILT = 2;

	private SmoothTerrain() {
	}

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("mcsource", path));
	}

	/** Air, plants, snow layers and other replaceable blocks, but not liquids. */
	public static boolean isOpen(BlockState state) {
		return state.canBeReplaced() && state.getFluidState().isEmpty();
	}

	/**
	 * Packed corner heights for the block at {@code pos}, or {@link SmoothMath#FULL}
	 * when it keeps its full cube.
	 */
	public static int corners(BlockGetter level, BlockPos pos, BlockState state) {
		if (level == EmptyBlockGetter.INSTANCE || !state.is(SMOOTH)) {
			return SmoothMath.FULL;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		if (!isOpen(level.getBlockState(cursor.setWithOffset(pos, 0, 1, 0)))) {
			return SmoothMath.FULL;
		}

		// 3x3 neighbourhood at this level and the level above, indexed [dx + 1 + 3 * (dz + 1)].
		int[] here = new int[9];
		boolean[] aboveBlocked = new boolean[9];
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int i = dx + 1 + 3 * (dz + 1);
				BlockState cell = dx == 0 && dz == 0 ? state : level.getBlockState(cursor.setWithOffset(pos, dx, 0, dz));
				here[i] = isOpen(cell) ? OPEN : cell.is(SMOOTH) ? TERRAIN : BUILT;
				aboveBlocked[i] = !isOpen(level.getBlockState(cursor.setWithOffset(pos, dx, 1, dz)));
			}
		}

		int packed = 0;
		for (int cz = 0; cz <= 1; cz++) {
			for (int cx = 0; cx <= 1; cx++) {
				boolean blocked = false;
				boolean built = false;
				int filled = 0;
				for (int oz = cz - 1; oz <= cz; oz++) {
					for (int ox = cx - 1; ox <= cx; ox++) {
						int i = ox + 1 + 3 * (oz + 1);
						blocked |= aboveBlocked[i];
						built |= here[i] == BUILT;
						if (here[i] == TERRAIN) {
							filled++;
						}
					}
				}
				packed |= SmoothMath.cornerHeight(blocked, built, filled) << ((cx + 2 * cz) * 4);
			}
		}
		return packed;
	}

	/**
	 * How far (in blocks, zero or negative) a plant at {@code pos} should sink to
	 * sit on smoothed ground. Looks through up to two stacked plants, so both
	 * halves of tall grass move together.
	 */
	public static float followOffset(BlockGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos cursor = pos.mutable();
		for (int depth = 0; depth < 3; depth++) {
			cursor.move(0, -1, 0);
			BlockState below = level.getBlockState(cursor);
			if (below.is(FOLLOWS)) {
				continue;
			}
			int packed = corners(level, cursor, below);
			return packed == SmoothMath.FULL ? 0.0F : SmoothMath.meanHeight(packed) - 1.0F;
		}
		return 0.0F;
	}
}
