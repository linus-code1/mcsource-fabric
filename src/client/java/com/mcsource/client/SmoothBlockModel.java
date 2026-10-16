package com.mcsource.client;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.terrain.SmoothMath;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Draws smoothed terrain by bending the block's own quads: each vertex moves up
 * or down with the grid corners around it, so textures, grass tint and overlays
 * all follow, and neighbouring blocks stay joined. Plants on top move with the
 * ground under them.
 */
public final class SmoothBlockModel extends WrapperBlockStateModel {
	public SmoothBlockModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest) {
		if (level != BlockAndTintGetter.EMPTY) {
			if (state.is(SmoothTerrain.SMOOTH)) {
				int[] offsets = SmoothTerrain.cornerOffsets(level, pos, state);
				if (offsets != null) {
					emitter.pushTransform(quad -> {
						for (int i = 0; i < 4; i++) {
							float x = quad.x(i);
							float y = quad.y(i);
							float z = quad.z(i);
							float offset = SmoothMath.offsetAt(offsets,
									Mth.clamp(x, 0.0F, 1.0F), Mth.clamp(y, 0.0F, 1.0F), Mth.clamp(z, 0.0F, 1.0F));
							quad.pos(i, x, y + offset, z);
						}
						return true;
					});
					try {
						super.emitQuads(emitter, level, pos, state, random, cullTest);
					} finally {
						emitter.popTransform();
					}
					return;
				}
			} else if (state.is(SmoothTerrain.FOLLOWS)) {
				float offset = SmoothTerrain.followOffset(level, pos);
				if (offset != 0.0F) {
					emitter.pushTransform(quad -> {
						for (int i = 0; i < 4; i++) {
							quad.pos(i, quad.x(i), quad.y(i) + offset, quad.z(i));
						}
						return true;
					});
					try {
						super.emitQuads(emitter, level, pos, state, random, cullTest);
					} finally {
						emitter.popTransform();
					}
					return;
				}
			}
		}
		super.emitQuads(emitter, level, pos, state, random, cullTest);
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		// Smoothed geometry depends on the neighbours, so it must not be shared.
		if (state.is(SmoothTerrain.SMOOTH) || state.is(SmoothTerrain.FOLLOWS)) {
			return null;
		}
		return super.createGeometryKey(level, pos, state, random);
	}
}
