package com.mcsource.client.prop;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/**
 * Where a door drawn as a Half-Life 2 door model goes. The model covers
 * both of the door's blocks and is drawn by the lower one; the upper one
 * draws nothing. It is placed from Minecraft's own door shapes, so it stands
 * exactly where the door collides: the shape of the door now gives the side
 * of the block it is on, and the corner where its closed and open shapes
 * meet is the hinge it turns about.
 */
public final class DoorProps {
	private DoorProps() {
	}

	/** Whether this is the upper block of a door, which the lower block draws. */
	public static boolean upper(BlockState state) {
		return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
	}

	/**
	 * Where the hinge edge of the door's middle is in the block (x and z) and
	 * which way the door runs from it (a unit x and z), or null if this is not
	 * a door. A door mesh's x runs along that way and its z through the door.
	 */
	public static float[] placement(BlockState state) {
		if (!(state.getBlock() instanceof DoorBlock)) {
			return null;
		}
		AABB closed = shape(state.setValue(DoorBlock.OPEN, false));
		AABB open = shape(state.setValue(DoorBlock.OPEN, true));
		if (closed == null || open == null) {
			return null;
		}
		AABB now = state.getValue(DoorBlock.OPEN) ? open : closed;
		double hingeX = (Math.max(closed.minX, open.minX) + Math.min(closed.maxX, open.maxX)) / 2.0;
		double hingeZ = (Math.max(closed.minZ, open.minZ) + Math.min(closed.maxZ, open.maxZ)) / 2.0;
		float middleX = (float) (now.minX + now.maxX) / 2.0F;
		float middleZ = (float) (now.minZ + now.maxZ) / 2.0F;
		if (now.getXsize() >= now.getZsize()) {
			boolean fromWest = hingeX < middleX;
			return new float[] {(float) (fromWest ? now.minX : now.maxX), middleZ, fromWest ? 1.0F : -1.0F, 0.0F};
		}
		boolean fromNorth = hingeZ < middleZ;
		return new float[] {middleX, (float) (fromNorth ? now.minZ : now.maxZ), 0.0F, fromNorth ? 1.0F : -1.0F};
	}

	private static AABB shape(BlockState state) {
		var shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		return shape.isEmpty() ? null : shape.bounds();
	}
}
