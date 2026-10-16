package com.mcsource.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
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
	/** Natural terrain that gets smoothed. Anything else stays a sharp cube. */
	public static final TagKey<Block> SMOOTH = tag("smooth_terrain");
	/** Plants and covers that move with the smoothed ground below them. */
	public static final TagKey<Block> FOLLOWS = tag("follows_terrain");

	private static final int OPEN = 0;
	private static final int TERRAIN = 1;
	private static final int BUILT = 2;

	private SmoothTerrain() {
	}

	private static TagKey<Block> tag(String path) {
		return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("mcsource", path));
	}

	/**
	 * Air, plants, snow layers, water and other replaceable blocks, but not
	 * lava. Water counts as open so the ground under lakes and the sea is
	 * smoothed like the ground under air, all but the top layer of water.
	 */
	public static boolean isOpen(BlockState state) {
		return state.canBeReplaced() && (state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER));
	}

	private static int classify(BlockState state) {
		if (isOpen(state)) {
			return OPEN;
		}
		return state.is(SMOOTH) ? TERRAIN : BUILT;
	}

	/**
	 * Like {@link #classify(BlockState)}, but the top layer of water pins the
	 * corners round it. The water surface is drawn flat at the top of its
	 * block, so a shore lowered beside it would leave a gap under the water's
	 * edge.
	 */
	private static int classify(BlockGetter level, BlockPos pos, BlockState state) {
		int kind = classify(state);
		if (kind == OPEN && !state.getFluidState().isEmpty() && level.getFluidState(pos.above()).isEmpty()) {
			return BUILT;
		}
		return kind;
	}

	private static boolean applies(BlockGetter level, BlockState state) {
		return level != EmptyBlockGetter.INSTANCE && state.is(SMOOTH);
	}

	/**
	 * Reads the classes of the blocks from {@code pos + (-1, dy0, -1)} to
	 * {@code pos + (1, dy0 + layers - 1, 1)}, indexed {@code dx + 1 + 3 * (dz + 1) + 9 * (dy - dy0)}.
	 */
	private static int[] neighbourhood(BlockGetter level, BlockPos pos, BlockState self, int dy0, int layers) {
		int[] classes = new int[9 * layers];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int layer = 0; layer < layers; layer++) {
			int dy = dy0 + layer;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int index = dx + 1 + 3 * (dz + 1) + 9 * layer;
					if (dx == 0 && dy == 0 && dz == 0) {
						classes[index] = classify(self);
					} else {
						cursor.setWithOffset(pos, dx, dy, dz);
						classes[index] = classify(level, cursor, level.getBlockState(cursor));
					}
				}
			}
		}
		return classes;
	}

	/**
	 * Offset (eighths) of the grid corner at {@code pos + (cx, cy, cz)}, from a
	 * neighbourhood whose lowest layer is at {@code pos.y + dy0}.
	 */
	private static int cornerOffset(int[] classes, int dy0, int cx, int cy, int cz) {
		int lower = 0;
		int upper = 0;
		for (int oy = cy - 1; oy <= cy; oy++) {
			for (int oz = cz - 1; oz <= cz; oz++) {
				for (int ox = cx - 1; ox <= cx; ox++) {
					int kind = classes[ox + 1 + 3 * (oz + 1) + 9 * (oy - dy0)];
					if (kind == BUILT) {
						// Built blocks and liquids pin the corner so they meet terrain cleanly.
						return 0;
					}
					if (kind == TERRAIN) {
						if (oy < cy) {
							lower++;
						} else {
							upper++;
						}
					}
				}
			}
		}
		return SmoothMath.cornerOffset(lower, upper);
	}

	/**
	 * The 3x3x3 block classes around a smoothed block, for meshing, or null when
	 * the block is not smoothed.
	 */
	public static int[] meshNeighbourhood(BlockGetter level, BlockPos pos, BlockState state) {
		return applies(level, state) ? neighbourhood(level, pos, state, -1, 3) : null;
	}

	/**
	 * The 5x5x5 block classes around a smoothed block, for drawing a smoother
	 * surface than the corners alone give, or null when the block is not
	 * smoothed. Indexed {@code dx + 2 + 5 * (dz + 2) + 25 * (dy + 2)}.
	 */
	public static int[] wideNeighbourhood(BlockGetter level, BlockPos pos, BlockState state) {
		if (!applies(level, state)) {
			return null;
		}
		int[] classes = new int[125];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dy = -2; dy <= 2; dy++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					int index = dx + 2 + 5 * (dz + 2) + 25 * (dy + 2);
					if (dx == 0 && dy == 0 && dz == 0) {
						classes[index] = classify(state);
					} else {
						cursor.setWithOffset(pos, dx, dy, dz);
						classes[index] = classify(level, cursor, level.getBlockState(cursor));
					}
				}
			}
		}
		return classes;
	}

	/**
	 * Offset (eighths) of the grid corner at {@code pos + (gx, gy, gz)}, each
	 * from -1 to 2, from a {@link #wideNeighbourhood}. Gives the same result
	 * as the corners used for collision.
	 */
	public static int wideCornerOffset(int[] wide, int gx, int gy, int gz) {
		int lower = 0;
		int upper = 0;
		for (int oy = gy - 1; oy <= gy; oy++) {
			for (int oz = gz - 1; oz <= gz; oz++) {
				for (int ox = gx - 1; ox <= gx; ox++) {
					int kind = wide[ox + 2 + 5 * (oz + 2) + 25 * (oy + 2)];
					if (kind == BUILT) {
						return 0;
					}
					if (kind == TERRAIN) {
						if (oy < gy) {
							lower++;
						} else {
							upper++;
						}
					}
				}
			}
		}
		return SmoothMath.cornerOffset(lower, upper);
	}

	/**
	 * Direction away from the solid blocks around the grid corner at
	 * {@code pos + (gx, gy, gz)} (each -1 to 2), not normalised; zero when
	 * there is no clear direction.
	 */
	public static void wideCornerGradient(int[] wide, int gx, int gy, int gz, float[] out) {
		float x = 0;
		float y = 0;
		float z = 0;
		for (int oy = gy - 1; oy <= gy; oy++) {
			for (int oz = gz - 1; oz <= gz; oz++) {
				for (int ox = gx - 1; ox <= gx; ox++) {
					if (wide[ox + 2 + 5 * (oz + 2) + 25 * (oy + 2)] != OPEN) {
						x -= ox - gx + 0.5F;
						y -= oy - gy + 0.5F;
						z -= oz - gz + 0.5F;
					}
				}
			}
		}
		out[0] = x;
		out[1] = y;
		out[2] = z;
	}

	/** Whether the block at {@code pos + (dx, dy, dz)} in a wide neighbourhood is open. */
	public static boolean isOpenWide(int[] wide, int dx, int dy, int dz) {
		return wide[dx + 2 + 5 * (dz + 2) + 25 * (dy + 2)] == OPEN;
	}

	/** Whether the block at {@code pos + (dx, dy, dz)} in a mesh neighbourhood is open. */
	public static boolean isOpen(int[] classes, int dx, int dy, int dz) {
		return classes[dx + 1 + 3 * (dz + 1) + 9 * (dy + 1)] == OPEN;
	}

	/**
	 * Offsets (eighths) of all eight corners of a smoothed block, index
	 * {@code cx + 2 * cy + 4 * cz}, or null when nothing moves.
	 */
	public static int[] cornerOffsets(int[] classes) {
		int[] offsets = new int[8];
		boolean moved = false;
		for (int i = 0; i < 8; i++) {
			offsets[i] = cornerOffset(classes, -1, i & 1, i >> 1 & 1, i >> 2 & 1);
			moved |= offsets[i] != 0;
		}
		return moved ? offsets : null;
	}

	/**
	 * A smooth surface normal at each of the eight corners (x, y, z per corner),
	 * pointing away from the solid blocks around it. Every block sharing a
	 * corner gets the same normal, so shading is continuous across blocks.
	 * A corner with no clear direction gets a zero vector.
	 */
	public static float[] cornerNormals(int[] classes) {
		float[] normals = new float[24];
		for (int i = 0; i < 8; i++) {
			int cx = i & 1;
			int cy = i >> 1 & 1;
			int cz = i >> 2 & 1;
			float gx = 0;
			float gy = 0;
			float gz = 0;
			for (int oy = cy - 1; oy <= cy; oy++) {
				for (int oz = cz - 1; oz <= cz; oz++) {
					for (int ox = cx - 1; ox <= cx; ox++) {
						if (classes[ox + 1 + 3 * (oz + 1) + 9 * (oy + 1)] != OPEN) {
							gx += ox - cx + 0.5F;
							gy += oy - cy + 0.5F;
							gz += oz - cz + 0.5F;
						}
					}
				}
			}
			float length = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
			if (length > 1.0E-4F) {
				normals[i * 3] = -gx / length;
				normals[i * 3 + 1] = -gy / length;
				normals[i * 3 + 2] = -gz / length;
			}
		}
		return normals;
	}

	/**
	 * Packed top corner heights for the block at {@code pos}, or
	 * {@link SmoothMath#FULL} when it keeps its full cube. Used for collision on
	 * both sides; matches the top of {@link #cornerOffsets} exactly.
	 */
	public static int corners(BlockGetter level, BlockPos pos, BlockState state) {
		if (!applies(level, state)) {
			return SmoothMath.FULL;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		if (!isOpen(level.getBlockState(cursor.setWithOffset(pos, 0, 1, 0)))) {
			return SmoothMath.FULL;
		}
		int[] classes = neighbourhood(level, pos, state, 0, 2);
		int packed = 0;
		for (int cz = 0; cz <= 1; cz++) {
			for (int cx = 0; cx <= 1; cx++) {
				packed |= (8 + cornerOffset(classes, 0, cx, 1, cz)) << ((cx + 2 * cz) * 4);
			}
		}
		return packed;
	}

	/**
	 * Top corner heights of the smoothed ground a plant or snow layer at
	 * {@code pos} rests on, or {@link SmoothMath#FULL} when the ground is flat.
	 * Looks through up to two stacked plants, so both halves of tall grass
	 * move together.
	 */
	public static int groundCorners(BlockGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos cursor = pos.mutable();
		for (int depth = 0; depth < 3; depth++) {
			cursor.move(0, -1, 0);
			BlockState below = level.getBlockState(cursor);
			if (!below.is(FOLLOWS)) {
				return corners(level, cursor, below);
			}
		}
		return SmoothMath.FULL;
	}
}
