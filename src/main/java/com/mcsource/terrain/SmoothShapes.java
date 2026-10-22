package com.mcsource.terrain;

import java.util.concurrent.atomic.AtomicReferenceArray;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Collision and outline shapes that follow the smooth surface, cached per corner combination. */
public final class SmoothShapes {
	private static final AtomicReferenceArray<VoxelShape> CACHE = new AtomicReferenceArray<>(1 << 16);

	private SmoothShapes() {
	}

	public static VoxelShape shape(int packed) {
		VoxelShape shape = CACHE.get(packed);
		if (shape == null) {
			shape = build(packed);
			CACHE.set(packed, shape);
		}
		return shape;
	}

	private static VoxelShape build(int packed) {
		int n = SmoothMath.COLUMNS;
		VoxelShape result = Shapes.empty();
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				double top = SmoothMath.columnHeight16(packed, i, j) / 16.0;
				result = Shapes.joinUnoptimized(result,
						Shapes.box((double) i / n, 0.0, (double) j / n, (double) (i + 1) / n, top, (double) (j + 1) / n),
						BooleanOp.OR);
			}
		}
		return result.optimize();
	}
}
