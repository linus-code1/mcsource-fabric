package com.mcsource.client;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Vector3fc;

import com.mcsource.client.light.OwnLight;
import com.mcsource.client.light.SourceLight;
import com.mcsource.terrain.SmoothMath;
import com.mcsource.terrain.SmoothTerrain;

/**
 * Draws smoothed terrain by bending the block's own quads: each vertex moves up
 * or down with the grid corners around it, so textures, grass tint and overlays
 * all follow, and neighbouring blocks stay joined. Plants and snow on top bend
 * with the ground under them.
 */
public final class SmoothBlockModel extends WrapperBlockStateModel {
	/** How far off the surface lamp light is measured, so the ground itself never stands in the way. */
	private static final float LAMP_PROBE = 0.3F;
	/** How far a thin snow sheet sits above the ground. */
	private static final float SNOW_LIFT = 0.035F;

	public SmoothBlockModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest) {
		if (level != BlockAndTintGetter.EMPTY) {
			com.mcsource.client.prop.PropModels.Mesh prop = com.mcsource.client.prop.PropModels.mesh(state);
			if (prop != null) {
				emitProp(emitter, level, pos, state, random, cullTest, prop);
				return;
			}
			if (state.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
				emitStairs(emitter, level, pos, state, cullTest);
				return;
			}
			if (state.getBlock() instanceof net.minecraft.world.level.block.VineBlock
					|| state.getBlock() instanceof net.minecraft.world.level.block.MultifaceBlock) {
				emitClinging(emitter, level, pos, state, random, cullTest);
				return;
			}
			if (state.is(SmoothTerrain.SMOOTH) && !buried(level, pos)) {
				int[] wide = SmoothTerrain.wideNeighbourhood(level, pos, state);
				float[] grid = wide == null ? null : grid(wide, level, pos);
				if (grid == null && wide != null && (blends(level, pos, state) != null || openAbove(level, pos))) {
					// Flat ground goes the same way as bent ground, so it is lit
					// the same way: lit block by block, flat ground next to bent
					// ground showed as squares of different brightness.
					grid = new float[128];
				}
				if (grid != null) {
					OwnLight.begin();
					try {
						emitBent(emitter, level, pos, state, random, cullTest, wide, grid);
					} finally {
						OwnLight.end();
					}
					return;
				}
			} else if (state.is(SmoothTerrain.FOLLOWS)) {
				int ground = SmoothTerrain.groundCorners(level, pos);
				boolean thinSnow = state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == 1;
				// Plants always go this way, even on flat ground, so they are lit
				// evenly as Source lights its grass sprites.
				emitFollowing(emitter, level, pos, state, random, cullTest, ground, thinSnow);
				return;
			}
		}
		if (level == BlockAndTintGetter.EMPTY) {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
			return;
		}
		boolean terrain = state.is(SmoothTerrain.SMOOTH);
		boolean snowAbove = terrain && level.getBlockState(pos.above()).is(Blocks.SNOW);
		net.minecraft.client.renderer.texture.TextureAtlasSprite rock = terrain ? cliffSprite(state) : null;
		emitter.pushTransform(quad -> {
			if (snowAbove && quad.faceNormal().y() > 0.5F) {
				// Hidden under the snow sheet; see emitBent.
				return false;
			}
			if (rock != null && Math.abs(quad.faceNormal().y()) < 0.5F) {
				// A straight cliff of soft ground: its sides are rock, as Source
				// blends rock into displacements where they turn into cliffs.
				if (quad.tintIndex() >= 0) {
					return false;
				}
				// Mapped as cliffRock maps it, so the two kinds of cliff meet.
				boolean alongX = Math.abs(quad.faceNormal().z()) >= Math.abs(quad.faceNormal().x());
				int cell = Math.floorMod(alongX ? pos.getX() : pos.getZ(), 2);
				for (int i = 0; i < 4; i++) {
					float u = (cell + Mth.clamp(alongX ? quad.x(i) : quad.z(i), 0.0F, 1.0F)) / 2.0F;
					quad.uv(i, rock.getU(u), rock.getV(1.0F - Mth.clamp((quad.y(i) + 0.5F) / 2.0F, 0.0F, 1.0F)));
				}
				applySun(quad, level, pos);
				return true;
			}
			if (terrain) {
				liftLowTop(quad, state);
				spreadTexture(quad, pos);
			}
			applySun(quad, level, pos);
			return true;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	/**
	 * Vines, lichen and the like lying on the face of a smoothed block are
	 * bent with it, so they stay on the face instead of standing off it or
	 * sinking into it where the ground curves.
	 */
	private void emitClinging(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest) {
		float[][] grids = new float[6][];
		boolean[] known = new boolean[6];
		emitter.pushTransform(quad -> {
			Direction side = null;
			for (Direction d : Direction.values()) {
				float edge = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
				boolean near = true;
				for (int i = 0; i < 4 && near; i++) {
					near = Math.abs(quad.posByIndex(i, d.getAxis().ordinal()) - edge) < 0.1F;
				}
				if (near) {
					side = d;
					break;
				}
			}
			if (side == null) {
				return true;
			}
			int at = side.ordinal();
			if (!known[at]) {
				known[at] = true;
				BlockPos support = pos.relative(side);
				BlockState held = level.getBlockState(support);
				if (held.is(SmoothTerrain.SMOOTH) && !buried(level, support)) {
					int[] wide = SmoothTerrain.wideNeighbourhood(level, support, held);
					grids[at] = wide == null ? null : grid(wide, level, support);
				}
			}
			float[] grid = grids[at];
			if (grid == null) {
				return true;
			}
			for (int i = 0; i < 4; i++) {
				float x = quad.x(i);
				float y = quad.y(i);
				float z = quad.z(i);
				// The same point in the supporting block's own coordinates.
				float sx = Mth.clamp(x - side.getStepX(), 0.0F, 1.0F);
				float sy = Mth.clamp(y - side.getStepY(), 0.0F, 1.0F);
				float sz = Mth.clamp(z - side.getStepZ(), 0.0F, 1.0F);
				quad.pos(i, x, y + smoothOffset(grid, sx, sy, sz), z);
			}
			return true;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	/** Whether the ground's top is out in the open, not under a prop or other block. */
	private static boolean openAbove(BlockAndTintGetter level, BlockPos pos) {
		BlockState above = level.getBlockState(pos.above());
		return above.isAir() || above.is(SmoothTerrain.FOLLOWS) || above.getCollisionShape(level, pos.above()).isEmpty();
	}

	/** How dark the ground is right under the middle of a prop. */
	private static final float CONTACT_SHADOW = 0.6F;

	/**
	 * A soft dark patch on the ground under a prop, darkest in the middle and
	 * fading out past its edges, so it sits on the ground instead of looking
	 * pasted over it.
	 */
	private static void contactShadow(QuadEmitter emitter, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, float minX,
			float maxX, float minZ, float maxZ, float drop) {
		float grow = 0.3F;
		float[] xs = {minX - grow, minX + (maxX - minX) * 0.15F, minX + (maxX - minX) * 0.85F, maxX + grow};
		float[] zs = {minZ - grow, minZ + (maxZ - minZ) * 0.15F, minZ + (maxZ - minZ) * 0.85F, maxZ + grow};
		float y = drop + 0.02F;
		for (int a = 0; a < 3; a++) {
			for (int b = 0; b < 3; b++) {
				int[][] corners = {{a, b}, {a, b + 1}, {a + 1, b + 1}, {a + 1, b}};
				for (int i = 0; i < 4; i++) {
					int ia = corners[i][0];
					int ib = corners[i][1];
					boolean inside = ia > 0 && ia < 3 && ib > 0 && ib < 3;
					emitter.pos(i, xs[ia], y, zs[ib]);
					emitter.uv(i, sprite.getU(0.5F), sprite.getV(0.5F));
					emitter.color(i, ARGB.color(inside ? Math.round(CONTACT_SHADOW * 255.0F) : 0, 0, 0, 0));
					emitter.lightmap(i, LightCoordsUtil.FULL_BRIGHT);
					emitter.normal(i, 0.0F, 1.0F, 0.0F);
				}
				emitter.nominalFace(Direction.UP);
				emitter.cullFace(null);
				emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
				emitter.ambientOcclusion(TriState.FALSE);
				emitter.tintIndex(-1);
				emitter.emit();
			}
		}
	}

	/** Steps a stairs block is drawn with, instead of Minecraft's two. */
	private static final int STEPS = 4;

	/**
	 * Stairs drawn as a run of four shallow steps, like the staircases in
	 * Half-Life 2, instead of Minecraft's two big ones. Corner stairs join
	 * the same way Minecraft's do. Only the look changes: walking on them
	 * is as before.
	 */
	private void emitStairs(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, Predicate<Direction> cullTest) {
		Direction facing = state.getValue(net.minecraft.world.level.block.StairBlock.FACING);
		var shape = state.getValue(net.minecraft.world.level.block.StairBlock.SHAPE);
		boolean top = state.getValue(net.minecraft.world.level.block.StairBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP;
		int[] heights = new int[STEPS * STEPS];
		for (int i = 0; i < STEPS; i++) {
			for (int j = 0; j < STEPS; j++) {
				int main = rise(facing, i, j);
				int h = switch (shape) {
					case STRAIGHT -> main;
					case OUTER_LEFT -> Math.min(main, rise(facing.getCounterClockWise(), i, j));
					case OUTER_RIGHT -> Math.min(main, rise(facing.getClockWise(), i, j));
					case INNER_LEFT -> Math.max(main, rise(facing.getCounterClockWise(), i, j));
					case INNER_RIGHT -> Math.max(main, rise(facing.getClockWise(), i, j));
				};
				heights[i + STEPS * j] = h;
			}
		}
		net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = wrapped.particleMaterial().sprite();
		float cell = 1.0F / STEPS;
		// Treads, and the bottom.
		for (int i = 0; i < STEPS; i++) {
			for (int j = 0; j < STEPS; j++) {
				float y = heights[i + STEPS * j] * cell;
				stairFace(emitter, level, pos, cullTest, sprite, top, Direction.UP, y >= 1.0F ? Direction.UP : null,
						i * cell, y, j * cell, (i + 1) * cell, y, (j + 1) * cell);
			}
		}
		stairFace(emitter, level, pos, cullTest, sprite, top, Direction.DOWN, Direction.DOWN, 0, 0, 0, 1, 0, 1);
		// Risers and sides: wherever a column stands above the one beside it, or at the block's edge.
		for (int i = 0; i < STEPS; i++) {
			for (int j = 0; j < STEPS; j++) {
				int h = heights[i + STEPS * j];
				for (Direction side : Direction.Plane.HORIZONTAL) {
					int ni = i + side.getStepX();
					int nj = j + side.getStepZ();
					boolean edge = ni < 0 || nj < 0 || ni >= STEPS || nj >= STEPS;
					int other = edge ? 0 : heights[ni + STEPS * nj];
					if (other >= h) {
						continue;
					}
					float x0 = i * cell;
					float z0 = j * cell;
					float x1 = x0 + cell;
					float z1 = z0 + cell;
					switch (side) {
						case EAST -> x0 = x1;
						case WEST -> x1 = x0;
						case SOUTH -> z0 = z1;
						default -> z1 = z0;
					}
					stairFace(emitter, level, pos, cullTest, sprite, top, side, edge ? side : null, x0, other * cell, z0, x1, h * cell, z1);
				}
			}
		}
	}

	/** Height (in steps) of the stairs rising towards {@code toward}, at column (i, j). */
	private static int rise(Direction toward, int i, int j) {
		int along = switch (toward) {
			case EAST -> i;
			case WEST -> STEPS - 1 - i;
			case SOUTH -> j;
			default -> STEPS - 1 - j;
		};
		return along + 1;
	}

	/**
	 * One flat face of the stairs between two corners, facing {@code face}.
	 * Upside-down stairs are the same faces mirrored top to bottom.
	 */
	private static void stairFace(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, Predicate<Direction> cullTest,
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, boolean flip, Direction face, Direction cull, float x0,
			float y0, float z0, float x1, float y1, float z1) {
		Direction shown = flip && face.getAxis() == Direction.Axis.Y ? face.getOpposite() : face;
		Direction culled = cull == null ? null : flip && cull.getAxis() == Direction.Axis.Y ? cull.getOpposite() : cull;
		// Faces on the block's sides carry their cull face; the renderer hides
		// them where a neighbour covers them.
		float[][] corners = switch (face) {
			case UP -> new float[][] {{x0, y0, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}};
			case DOWN -> new float[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
			case NORTH -> new float[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
			case SOUTH -> new float[][] {{x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}};
			case WEST -> new float[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
			case EAST -> new float[][] {{x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}};
		};
		for (int c = 0; c < 4; c++) {
			// Upside down: mirrored in y, and wound the other way so the face still points out.
			float[] p = corners[flip ? 3 - c : c];
			float x = p[0];
			float y = flip ? 1.0F - p[1] : p[1];
			float z = p[2];
			emitter.pos(c, x, y, z);
			float u;
			float v;
			if (face.getAxis() == Direction.Axis.Y) {
				u = x;
				v = z;
			} else {
				u = face.getAxis() == Direction.Axis.X ? z : x;
				v = 1.0F - y;
			}
			emitter.uv(c, sprite.getU(u), sprite.getV(v));
			emitter.color(c, -1);
		}
		emitter.nominalFace(shown);
		emitter.cullFace(culled);
		emitter.chunkLayer(ChunkSectionLayer.SOLID);
		emitter.ambientOcclusion(TriState.DEFAULT);
		emitter.tintIndex(-1);
		applySun(emitter, level, pos);
		emitter.emit();
	}

	/**
	 * A furniture block drawn as its Half-Life 2 prop, turned the way the
	 * block faces. A bed's prop covers both of its blocks and is drawn by the
	 * head. The prop is lit by the brightest cell around it, since a solid
	 * block (a crafting table) has no light of its own inside it.
	 */
	private void emitProp(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, com.mcsource.client.prop.PropModels.Mesh mesh) {
		if (state.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
				&& state.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
			return;
		}
		if (mesh.keepSlanted()) {
			// The block's own slanted cards (a campfire's flames) stay on the prop.
			emitter.pushTransform(quad -> {
				Vector3fc normal = quad.faceNormal();
				return Math.abs(normal.x()) > 0.2F && Math.abs(normal.z()) > 0.2F;
			});
			try {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
			} finally {
				emitter.popTransform();
			}
		}
		float angle = 0.0F;
		var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
		if (mesh.turns() && state.hasProperty(facing)) {
			angle = (float) Math.toRadians(state.getValue(facing).toYRot());
		}
		float cos = Mth.cos(angle);
		float sin = Mth.sin(angle);
		var atlas = net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
		net.minecraft.client.renderer.texture.TextureAtlasSprite[] sprites =
				new net.minecraft.client.renderer.texture.TextureAtlasSprite[mesh.sprites().length];
		for (int i = 0; i < sprites.length; i++) {
			sprites[i] = atlas.getSprite(net.minecraft.resources.Identifier.parse(mesh.sprites()[i]));
		}
		int light = LightCoordsUtil.getLightCoords(level, pos);
		for (Direction direction : Direction.values()) {
			light = LightCoordsUtil.max(light, LightCoordsUtil.getLightCoords(level, pos.relative(direction)));
		}
		int sky = LightCoordsUtil.sky(light);
		int block = LightCoordsUtil.block(light);
		float[] positions = mesh.positions();
		float[] uvs = mesh.uvs();
		// Sit down onto smoothed ground that curves away below the block.
		float drop = groundDrop(level, pos);
		float minX = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		for (int t = 0; t < mesh.triangles(); t++) {
			int material = mesh.texture()[t];
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = sprites[material];
			for (int c = 0; c < 4; c++) {
				// Source models wind their triangles the other way round from Minecraft.
				int k = c == 3 ? 0 : 2 - c;
				float x = positions[t * 9 + k * 3] - 0.5F;
				float y = positions[t * 9 + k * 3 + 1];
				float z = positions[t * 9 + k * 3 + 2] - 0.5F;
				float wx = 0.5F + x * cos - z * sin;
				float wz = 0.5F + x * sin + z * cos;
				minX = Math.min(minX, wx);
				maxX = Math.max(maxX, wx);
				minZ = Math.min(minZ, wz);
				maxZ = Math.max(maxZ, wz);
				emitter.pos(c, wx, y + drop, wz);
				emitter.uv(c, sprite.getU(uvs[t * 6 + k * 2]), sprite.getV(uvs[t * 6 + k * 2 + 1]));
				emitter.lightmap(c, light);
			}
			Vector3fc normal = emitter.faceNormal();
			for (int c = 0; c < 4; c++) {
				int colour = sky == 0 ? -1 : SunLight.multiplier(level, pos.getX() + emitter.x(c), pos.getY() + emitter.y(c),
						pos.getZ() + emitter.z(c), normal.x(), normal.y(), normal.z(), sky, block);
				emitter.color(c, colour);
			}
			emitter.cullFace(null);
			emitter.nominalFace(null);
			emitter.chunkLayer(mesh.masked()[material] ? ChunkSectionLayer.CUTOUT : ChunkSectionLayer.SOLID);
			emitter.ambientOcclusion(TriState.FALSE);
			emitter.shadeMode(ShadeMode.ENHANCED);
			emitter.tintIndex(-1);
			emitter.emit();
		}
		if (minX < maxX && sky + block > 0) {
			contactShadow(emitter, sprites[0], minX, maxX, minZ, maxZ, drop);
		}
	}

	/**
	 * Plants and snow on top of smoothed ground: each vertex rides the ground
	 * surface under it, so they bend over slopes instead of floating flat. A
	 * single snow layer is drawn as a thin sheet lying on the ground, without
	 * the little walls round its edge, cut into pieces like the ground so it
	 * follows the same curve and the ground never shows through it.
	 */
	private void emitFollowing(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, int ground, boolean thinSnow) {
		int lightHere = LightCoordsUtil.getLightCoords(level, pos);
		GroundSurface surface = new GroundSurface(level, pos, ground);
		MutableMesh captured = CAPTURED.get();
		captured.clear();
		QuadEmitter capture = captured.emitter();
		emitter.pushTransform(quad -> {
			if (thinSnow && quad.faceNormal().y() < 0.5F) {
				return false;
			}
			capture.copyFrom(quad);
			capture.emit();
			return false;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] pu = new float[4];
		float[] pv = new float[4];
		int[] pc = new int[4];
		captured.forEachMutable(quad -> {
			// Flat quads lying on the ground (snow) are cut up to follow its curve;
			// standing ones (plants) only need their corners moved.
			boolean lying = quad.faceNormal().y() > 0.5F;
			int pieces = lying ? PIECES : 1;
			for (int i = 0; i < 4; i++) {
				px[i] = quad.x(i);
				py[i] = quad.y(i);
				pz[i] = quad.z(i);
				pu[i] = quad.u(i);
				pv[i] = quad.v(i);
				pc[i] = quad.color(i);
			}
			for (int a = 0; a < pieces; a++) {
				for (int b = 0; b < pieces; b++) {
					emitter.copyFrom(quad);
					for (int i = 0; i < 4; i++) {
						float s = (a + (i >= 2 ? 1 : 0)) / (float) pieces;
						float t = (b + (i == 1 || i == 2 ? 1 : 0)) / (float) pieces;
						float w0 = (1 - s) * (1 - t);
						float w1 = (1 - s) * t;
						float w2 = s * t;
						float w3 = s * (1 - t);
						float x = px[0] * w0 + px[1] * w1 + px[2] * w2 + px[3] * w3;
						float y = py[0] * w0 + py[1] * w1 + py[2] * w2 + py[3] * w3;
						float z = pz[0] * w0 + pz[1] * w1 + pz[2] * w2 + pz[3] * w3;
						float groundY = surface.height(Mth.clamp(x, 0.0F, 1.0F), Mth.clamp(z, 0.0F, 1.0F));
						float bentY = thinSnow ? groundY + SNOW_LIFT : y + groundY;
						emitter.pos(i, x, bentY, z);
						emitter.uv(i, pu[0] * w0 + pu[1] * w1 + pu[2] * w2 + pu[3] * w3, pv[0] * w0 + pv[1] * w1 + pv[2] * w2 + pv[3] * w3);
						// Plants and snow take the sun as if facing up, like the ground.
						float skyHere = SourceLight.skyLevel(lightHere);
						float blockHere = SourceLight.blockLevel(lightHere);
						if (lying) {
							// A sheet lying over several blocks is lit smoothly across
							// them, as the ground is, not one flat level a block.
							float[] smooth = SmoothLight.at(level, pos.getX() + Mth.clamp(x, 0.0F, 1.0F), pos.getY() + Mth.clamp(bentY, 0.0F, 1.0F),
									pos.getZ() + Mth.clamp(z, 0.0F, 1.0F));
							float lamps = SourceLight.lampsAt(level, pos.getX() + x, pos.getY() + bentY + LAMP_PROBE, pos.getZ() + z);
							if (lamps >= 0.0F) {
								smooth[0] = lamps * 15.0F;
							}
							blockHere = smooth[0];
							skyHere = smooth[1];
							emitter.lightmap(i, SmoothLight.pack(smooth));
						}
						int sun = SunLight.multiplier(level, pos.getX() + x, pos.getY() + bentY, pos.getZ() + z, 0.0F, 1.0F, 0.0F,
								skyHere, blockHere);
						emitter.color(i, ARGB.multiply(blendColor(pc, w0, w1, w2, w3), sun));
						emitter.normal(i, 0.0F, 1.0F, 0.0F);
					}
					emitter.ambientOcclusion(TriState.FALSE);
					if (lying) {
						// Cut into triangles the same way as the ground under it, or the
						// two surfaces cross each other and flicker.
						splitAlongFlatterDiagonal(emitter);
					}
					// Lit as if facing up, like the ground under it. Without this the
					// renderer shades each triangle by the way it happens to face:
					// the crossed cards of a grass tuft come out light and dark, and
					// a snow sheet bent over a slope turned into a patchwork of white
					// and half-dark grey triangles.
					emitter.shadeDirectionOverride(Direction.UP);
					if (lying) {
						OwnLight.begin();
						try {
							emitter.emit();
						} finally {
							OwnLight.end();
						}
					} else {
						emitter.emit();
					}
				}
			}
		});
		captured.clear();
	}

	/**
	 * The top of the ground under a plant or snow layer, exactly as the ground
	 * itself is drawn, in blocks relative to the plant's own block.
	 */
	/**
	 * How far below {@code pos} (in blocks, 0 or less) the lowest point of the
	 * drawn ground under it is, sampled over the middle of the block, so
	 * things standing there (props, trees) can sit down onto it.
	 */
	public static float groundDrop(BlockAndTintGetter level, BlockPos pos) {
		GroundSurface surface = new GroundSurface(level, pos, SmoothTerrain.groundCorners(level, pos));
		float low = 0.0F;
		for (float x = 0.15F; x <= 0.86F; x += 0.35F) {
			for (float z = 0.15F; z <= 0.86F; z += 0.35F) {
				low = Math.min(low, surface.height(x, z));
			}
		}
		return Math.max(low, -0.75F);
	}

	private static final class GroundSurface {
		private final float base;
		private final float[] grid;
		private final float[] corners;

		GroundSurface(BlockAndTintGetter level, BlockPos pos, int packed) {
			BlockPos below = SmoothTerrain.ground(level, pos);
			BlockState ground = level.getBlockState(below);
			float[] bent = null;
			if (ground.is(SmoothTerrain.SMOOTH)) {
				int[] wide = SmoothTerrain.wideNeighbourhood(level, below, ground);
				bent = wide == null ? null : grid(wide, level, below);
			}
			this.base = below.getY() + 1 - pos.getY();
			this.grid = bent;
			if (ground.is(SmoothTerrain.SMOOTH)) {
				this.corners = null;
			} else {
				// Not smoothed ground (or none): the collision corners, as before.
				this.corners = new float[4];
				for (int i = 0; i < 4; i++) {
					corners[i] = SmoothMath.corner(packed, i) / 8.0F - 1.0F;
				}
			}
		}

		float height(float x, float z) {
			if (corners != null) {
				return Mth.lerp(z, Mth.lerp(x, corners[0], corners[1]), Mth.lerp(x, corners[2], corners[3]));
			}
			return base + (grid == null ? 0.0F : smoothOffset(grid, x, 1.0F, z));
		}
	}

	/**
	 * The drawn ground's height under {@code pos} at points across the block
	 * ({@code xs}, {@code zs} from 0 to 1), in blocks relative to the bottom of
	 * {@code pos}: what an entity's shadow lies on.
	 */
	public static void groundHeights(BlockAndTintGetter level, BlockPos pos, float[] xs, float[] zs, float[] out) {
		GroundSurface surface = new GroundSurface(level, pos, SmoothTerrain.groundCorners(level, pos));
		for (int i = 0; i < out.length; i++) {
			out[i] = surface.height(Mth.clamp(xs[i], 0.0F, 1.0F), Mth.clamp(zs[i], 0.0F, 1.0F));
		}
	}

	/** Ground textures stretch over this many blocks each way, as Source's do over several metres. */
	static final int SPREAD = 2;

	/**
	 * Stretches a terrain face's texture over {@link #SPREAD} blocks: each
	 * block shows the part of the texture its place in the world picks, so the
	 * texture runs on unbroken across neighbouring blocks and repeats less
	 * often. Up and down faces stretch both ways; sides only sideways, so the
	 * grass band along the top of a grass block's side stays where it is.
	 */
	static void spreadTexture(MutableQuadView quad, BlockPos pos) {
		Vector3fc normal = quad.faceNormal();
		float u0 = Float.MAX_VALUE;
		float u1 = -Float.MAX_VALUE;
		float v0 = Float.MAX_VALUE;
		float v1 = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			u0 = Math.min(u0, quad.u(i));
			u1 = Math.max(u1, quad.u(i));
			v0 = Math.min(v0, quad.v(i));
			v1 = Math.max(v1, quad.v(i));
		}
		if (u1 - u0 < 1.0E-6F || v1 - v0 < 1.0E-6F) {
			return;
		}
		// The texture is laid by the block's place in the world, not by the
		// block's model: stone and other ground blocks come in randomly turned
		// and mirrored models, and following those, each block showed its part
		// of the texture turned its own way, so the ground looked like a
		// chequerboard of mismatched squares.
		if (Math.abs(normal.y()) > 0.5F) {
			int cx = Math.floorMod(pos.getX(), SPREAD);
			int cz = Math.floorMod(pos.getZ(), SPREAD);
			for (int i = 0; i < 4; i++) {
				float x = Mth.clamp(quad.x(i), 0.0F, 1.0F);
				float z = Mth.clamp(quad.z(i), 0.0F, 1.0F);
				quad.uv(i, u0 + (cx + x) / SPREAD * (u1 - u0), v0 + (cz + z) / SPREAD * (v1 - v0));
			}
			return;
		}
		// Sides: sideways by the world, looking at the face from outside; up and
		// down as the model has it, so a grass side keeps its band on top.
		boolean alongX = Math.abs(normal.z()) >= Math.abs(normal.x());
		boolean forward = alongX ? normal.z() > 0.0F : normal.x() < 0.0F;
		int cell = cell(pos, alongX ? 0 : 2, forward);
		for (int i = 0; i < 4; i++) {
			float t = Mth.clamp(alongX ? quad.x(i) : quad.z(i), 0.0F, 1.0F);
			if (!forward) {
				t = 1.0F - t;
			}
			quad.uv(i, u0 + (cell + t) / SPREAD * (u1 - u0), quad.v(i));
		}
	}

	/** Which of the {@link #SPREAD} parts of the texture a block shows along an axis. */
	private static int cell(BlockPos pos, int axis, boolean forward) {
		int coordinate = axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
		return Math.floorMod(forward ? coordinate : -coordinate - 1, SPREAD);
	}

	/**
	 * Paths and farmland are a sixteenth lower than the ground; their tops are
	 * raised level with it so they run into the ground around them without a
	 * step at their edge.
	 */
	private static void liftLowTop(MutableQuadView quad, BlockState state) {
		if (!state.is(Blocks.DIRT_PATH) && !state.is(Blocks.FARMLAND)) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			if (quad.y(i) > 0.9F) {
				quad.pos(i, quad.x(i), 1.0F, quad.z(i));
			}
		}
	}

	/** Sun and shadow for an ordinary block face, lit by the cell it faces. */
	private static void applySun(MutableQuadView quad, BlockAndTintGetter level, BlockPos pos) {
		Vector3fc normal = quad.faceNormal();
		Direction face = quad.lightFace();
		int light = LightCoordsUtil.getLightCoords(level, quad.cullFace() == null && !touchesSide(quad, face) ? pos : pos.relative(face));
		int sky = LightCoordsUtil.sky(light);
		if (sky == 0) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			// Light blended between blocks: one level for the whole face showed
			// each block as a square of its own brightness.
			float[] smooth = SmoothLight.at(level, pos.getX() + Mth.clamp(quad.x(i), 0.0F, 1.0F), pos.getY() + Mth.clamp(quad.y(i), 0.0F, 1.0F),
					pos.getZ() + Mth.clamp(quad.z(i), 0.0F, 1.0F));
			int sun = SunLight.multiplier(level, pos.getX() + quad.x(i), pos.getY() + quad.y(i), pos.getZ() + quad.z(i),
					normal.x(), normal.y(), normal.z(), smooth[1], smooth[0]);
			quad.color(i, ARGB.multiply(quad.color(i), sun));
		}
	}

	/** Whether a quad lies on the block's outer side in the given direction. */
	private static boolean touchesSide(MutableQuadView quad, Direction face) {
		float edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
		for (int i = 0; i < 4; i++) {
			if (Math.abs(quad.posByIndex(i, face.getAxis().ordinal()) - edge) > 1.0E-3F) {
				return false;
			}
		}
		return true;
	}

	/** Each side of a bent face is cut into this many pieces, so the surface can curve. */
	private static final int PIECES = 3;
	private static final ThreadLocal<MutableMesh> CAPTURED = ThreadLocal.withInitial(() -> Renderer.get().mutableMesh());

	/** Index into a 4x4x4 grid of corners from -1 to 2 on each axis. */
	private static int node(int gx, int gy, int gz) {
		return gx + 1 + 4 * (gz + 1) + 16 * (gy + 1);
	}

	/**
	 * Offsets in blocks of the 4x4x4 grid corners around a block, from -1 to
	 * 2 on each axis, or null when none of them moves.
	 */
	private static float[] grid(int[] wide, BlockAndTintGetter level, BlockPos pos) {
		// Offsets, then (from 64) 1 for corners pinned by a built block beside them.
		float[] grid = new float[128];
		boolean moved = false;
		int[] heights = null;
		for (int gy = -1; gy <= 2; gy++) {
			for (int gz = -1; gz <= 2; gz++) {
				for (int gx = -1; gx <= 2; gx++) {
					int offset = SmoothTerrain.wideCornerOffset(wide, gx, gy, gz);
					float value = offset / 8.0F;
					if (offset == 0 && SmoothTerrain.wideFlatGround(wide, gx, gy, gz)) {
						if (heights == null) {
							heights = columnHeights(level, pos);
						}
						value = rounding(heights, gx, gy, gz);
					}
					grid[node(gx, gy, gz)] = value;
					if (SmoothTerrain.widePinned(wide, gx, gy, gz)) {
						grid[64 + node(gx, gy, gz)] = 1.0F;
					}
					moved |= value != 0.0F;
				}
			}
		}
		return moved ? grid : null;
	}

	/** Most a flat ground corner is moved to round off a terrace, in blocks. */
	private static final float ROUNDING = 0.3F;

	/** Ground heights of the 7x7 columns round a block, from -3 to 3 on x and z, index {@code dx + 3 + 7 * (dz + 3)}. */
	private static int[] columnHeights(BlockAndTintGetter level, BlockPos pos) {
		int[] heights = new int[49];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dz = -3; dz <= 3; dz++) {
			for (int dx = -3; dx <= 3; dx++) {
				heights[dx + 3 + 7 * (dz + 3)] = SmoothTerrain.columnHeight(level, pos, dx, dz, cursor);
			}
		}
		return heights;
	}

	/**
	 * How far a corner in flat ground moves (drawing only): towards the
	 * average height of the ground round it, so the flat tops and feet of
	 * terraces round off into the ramps between them instead of meeting
	 * them at a crease. Weighted 1, 3, 3, 1 over the four columns each way.
	 */
	private static float rounding(int[] heights, int gx, int gy, int gz) {
		int[] w = {1, 3, 3, 1};
		float sum = 0.0F;
		for (int j = 0; j < 4; j++) {
			for (int i = 0; i < 4; i++) {
				int h = heights[gx - 2 + i + 3 + 7 * (gz - 2 + j + 3)];
				sum += w[i] * w[j] * Mth.clamp(h, gy - 2, gy + 2);
			}
		}
		return Mth.clamp(sum / 64.0F - gy, -ROUNDING, ROUNDING);
	}

	/** Whether every side of the block is covered by a solid block, so nothing of it shows. */
	private static boolean buried(BlockAndTintGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (Direction direction : Direction.values()) {
			BlockState next = level.getBlockState(cursor.setWithOffset(pos, direction));
			if (SmoothTerrain.isOpen(next) || !next.canOcclude()) {
				return false;
			}
		}
		return true;
	}

	/** Catmull-Rom weights for the four nodes around a point {@code t} (0 to 1) between the middle two. */
	private static void weights(float t, float[] w) {
		float t2 = t * t;
		float t3 = t2 * t;
		w[0] = 0.5F * (-t + 2.0F * t2 - t3);
		w[1] = 0.5F * (2.0F - 5.0F * t2 + 3.0F * t3);
		w[2] = 0.5F * (t + 4.0F * t2 - 3.0F * t3);
		w[3] = 0.5F * (-t2 + t3);
	}

	/**
	 * The surface offset at a point in the block: across the ground a smooth
	 * curve through the corner offsets (which collision follows exactly), so
	 * ramps round off into the flat ground at their top and foot instead of
	 * creasing.
	 */
	private static float smoothOffset(float[] grid, float x, float y, float z) {
		float[] wx = new float[4];
		float[] wy = new float[4];
		float[] wz = new float[4];
		weights(x, wx);
		weights(z, wz);
		// Straight up and down the offset is blended linearly. A curve would
		// bulge the side of a smoothed step out past the slope above it, and
		// that sliver of side shows as a thin dark line along every step.
		wy[0] = 0.0F;
		wy[1] = 1.0F - y;
		wy[2] = y;
		wy[3] = 0.0F;
		float sum = 0.0F;
		for (int j = 0; j < 4; j++) {
			for (int k = 0; k < 4; k++) {
				float wyz = wy[j] * wz[k];
				for (int i = 0; i < 4; i++) {
					sum += wx[i] * wyz * grid[i + 4 * k + 16 * j];
				}
			}
		}
		// The curve passes through every corner but swings between them; where
		// a built block (a crate, planks, a wall) pins the corners, that swing
		// opened a gap along its foot. Hold the surface to the pinned corners
		// near them: none of the swing along an edge pinned at both ends.
		float pinned = 0.0F;
		for (int j = 1; j <= 2; j++) {
			float fy = j == 1 ? 1.0F - y : y;
			for (int k = 1; k <= 2; k++) {
				float fz = k == 1 ? 1.0F - z : z;
				for (int i = 1; i <= 2; i++) {
					float fx = i == 1 ? 1.0F - x : x;
					pinned += fx * fy * fz * grid[64 + i + 4 * k + 16 * j];
				}
			}
		}
		if (pinned > 0.0F) {
			// The plain blend of the four corners, which stays at the pinned ones.
			float linear = 0.0F;
			for (int j = 1; j <= 2; j++) {
				float fy = j == 1 ? 1.0F - y : y;
				for (int k = 1; k <= 2; k++) {
					float fz = k == 1 ? 1.0F - z : z;
					for (int i = 1; i <= 2; i++) {
						float fx = i == 1 ? 1.0F - x : x;
						linear += fx * fy * fz * grid[i + 4 * k + 16 * j];
					}
				}
			}
			// Fully the plain blend along a pinned edge, easing back to the curve.
			float hold = Mth.clamp(pinned * 2.0F, 0.0F, 1.0F);
			sum = sum + (linear - sum) * hold;
		}
		return Mth.clamp(sum, -0.5F, 0.5F);
	}

	private void emitBent(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
			Predicate<Direction> cullTest, int[] wide, float[] grid) {
		float[] normals = smoothNormals(wide);
		int[] classes = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					classes[dx + 1 + 3 * (dz + 1) + 9 * (dy + 1)] = SmoothTerrain.isOpenWide(wide, dx, dy, dz) ? 1 : 0;
				}
			}
		}
		float[] blockLight = new float[8];
		float[] skyLight = new float[8];
		float[] shade = new float[8];
		cornerLighting(level, pos, classes, blockLight, skyLight, shade);

		// Collect the block's own quads, then draw each cut into pieces that
		// follow the curved surface.
		// Under a snow layer the top is never seen: the snow sheet lies on
		// exactly the same surface, and two surfaces that close together fight
		// over which is in front once they are a few dozen blocks away.
		boolean snowAbove = level.getBlockState(pos.above()).is(Blocks.SNOW);
		MutableMesh captured = CAPTURED.get();
		captured.clear();
		QuadEmitter capture = captured.emitter();
		float[] topBounds = {0.0F, 0.0F, 0.0F, 0.0F};
		emitter.pushTransform(quad -> {
			if (snowAbove && quad.faceNormal().y() > 0.5F) {
				return false;
			}
			if (quad.faceNormal().y() > 0.5F) {
				topBounds[0] = Float.MAX_VALUE;
				topBounds[1] = -Float.MAX_VALUE;
				topBounds[2] = Float.MAX_VALUE;
				topBounds[3] = -Float.MAX_VALUE;
				for (int i = 0; i < 4; i++) {
					topBounds[0] = Math.min(topBounds[0], quad.u(i));
					topBounds[1] = Math.max(topBounds[1], quad.u(i));
					topBounds[2] = Math.min(topBounds[2], quad.v(i));
					topBounds[3] = Math.max(topBounds[3], quad.v(i));
				}
			}
			capture.copyFrom(quad);
			liftLowTop(capture, state);
			spreadTexture(capture, pos);
			capture.emit();
			return false;
		});
		try {
			super.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
		java.util.List<Blend> blends = blends(level, pos, state);
		net.minecraft.client.renderer.texture.TextureAtlasSprite rock = cliffSprite(state);
		GroundTop ground = groundTop(state, snowAbove);
		// Grass hangs a little way down over the lip of a cliff, as Source
		// blends it over the top of its rock faces, instead of stopping at a
		// hard edge where the top turns down.
		boolean lipHere = ground != null && rock != null && openAbove(level, pos);
		float[] ox = new float[4];
		float[] oy = new float[4];
		float[] oz = new float[4];
		int[] oc = new int[4];
		int[] ol = new int[4];
		float[] on = new float[12];
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] pu = new float[4];
		float[] pv = new float[4];
		int[] pc = new int[4];
		int[] pl = new int[4];
		captured.forEachMutable(quad -> {
			for (int i = 0; i < 4; i++) {
				px[i] = quad.x(i);
				py[i] = quad.y(i);
				pz[i] = quad.z(i);
				pu[i] = quad.u(i);
				pv[i] = quad.v(i);
				pc[i] = quad.color(i);
				pl[i] = quad.lightmap(i);
			}
			Vector3fc face = quad.faceNormal();
			float fx = face.x();
			float fy = face.y();
			float fz = face.z();
			boolean top = fy > 0.5F;
			// A side whose block shows another texture on top (a grass block's
			// dirt side) is drawn as the ground's top where it lies in a gentle
			// slope and as rock where it is steep, never as the side texture:
			// bent into the slope, a strip of dirt side showed as a brown band.
			boolean sideGround = !top && ground != null;
			if (sideGround && quad.tintIndex() >= 0) {
				// The side's tinted overlay (the grass fringe) is not needed.
				return;
			}
			// One rock layer per face: a grass side's tinted overlay quad gets none.
			boolean rockHere = rock != null && (top || quad.tintIndex() < 0);
			for (int a = 0; a < PIECES; a++) {
				for (int b = 0; b < PIECES; b++) {
					emitter.copyFrom(quad);
					for (int i = 0; i < 4; i++) {
						// Corners in order (0,0) (0,1) (1,1) (1,0), as vertices 0 to 3 run.
						float s = (a + (i >= 2 ? 1 : 0)) / (float) PIECES;
						float t = (b + (i == 1 || i == 2 ? 1 : 0)) / (float) PIECES;
						float w0 = (1 - s) * (1 - t);
						float w1 = (1 - s) * t;
						float w2 = s * t;
						float w3 = s * (1 - t);
						float x = px[0] * w0 + px[1] * w1 + px[2] * w2 + px[3] * w3;
						float y = py[0] * w0 + py[1] * w1 + py[2] * w2 + py[3] * w3;
						float z = pz[0] * w0 + pz[1] * w1 + pz[2] * w2 + pz[3] * w3;
						float lx = Mth.clamp(x, 0.0F, 1.0F);
						float ly = Mth.clamp(y, 0.0F, 1.0F);
						float lz = Mth.clamp(z, 0.0F, 1.0F);
						float bentY = y + smoothOffset(grid, lx, ly, lz);
						emitter.pos(i, x, bentY, z);
						emitter.uv(i, pu[0] * w0 + pu[1] * w1 + pu[2] * w2 + pu[3] * w3, pv[0] * w0 + pv[1] * w1 + pv[2] * w2 + pv[3] * w3);

						float[] n = new float[3];
						trilinear(normals, 3, lx, ly, lz, n);
						float length = Mth.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
						if (length < 1.0E-3F) {
							n[0] = fx;
							n[1] = fy;
							n[2] = fz;
						} else {
							n[0] /= length;
							n[1] /= length;
							n[2] /= length;
						}
						// Every vertex gets a normal: a quad missing one is shaded flat
						// from its face normal, which shows each facet.
						emitter.normal(i, n[0], n[1], n[2]);

						// Light, shade and sun blended from the corners, so they change
						// smoothly across the face instead of in steps.
						// Light from every open block round about, blended between
						// blocks, so it runs on across block edges instead of in squares.
						float[] one = new float[1];
						float[] smooth = SmoothLight.at(level, pos.getX() + lx, pos.getY() + ly, pos.getZ() + lz);
						// Lamps by the exact point, so their light fades smoothly
						// across the ground instead of block by block.
						float lamps = SourceLight.lampsAt(level, pos.getX() + x + n[0] * LAMP_PROBE, pos.getY() + bentY + n[1] * LAMP_PROBE,
								pos.getZ() + z + n[2] * LAMP_PROBE);
						float block = lamps >= 0.0F ? lamps * 15.0F : smooth[0];
						float sky = smooth[1];
						trilinear(shade, 1, lx, ly, lz, one);
						int grey = Math.round(255.0F * one[0]);
						int original = blend(pl, w0, w1, w2, w3);
						int light = Math.max(Math.round(block * 16.0F), original & 0xFFFF)
								| Math.max(Math.round(sky * 16.0F), original >>> 16 & 0xFFFF) << 16;
						emitter.lightmap(i, light);
						int color = blendColor(pc, w0, w1, w2, w3);
						color = ARGB.multiply(color, ARGB.color(255, grey, grey, grey));
						int sun = SunLight.multiplier(level, pos.getX() + x, pos.getY() + bentY, pos.getZ() + z, n[0], n[1], n[2], sky, block);
						emitter.color(i, ARGB.multiply(color, sun));
					}
					// Vanilla ambient occlusion assumes faces lie on the block's sides and
					// darkens moved faces wrongly; light comes from cornerLighting instead.
					// Enhanced shading lights each vertex by its smooth normal, so slopes
					// shade evenly across block borders.
					emitter.ambientOcclusion(TriState.FALSE);
					emitter.shadeMode(ShadeMode.ENHANCED);
					splitAlongFlatterDiagonal(emitter);
					boolean blendHere = top && blends != null;
					float[] alpha = null;
					{
						for (int i = 0; i < 4; i++) {
							ox[i] = emitter.x(i);
							oy[i] = emitter.y(i);
							oz[i] = emitter.z(i);
							oc[i] = emitter.color(i);
							ol[i] = emitter.lightmap(i);
							on[i * 3] = emitter.normalX(i);
							on[i * 3 + 1] = emitter.normalY(i);
							on[i * 3 + 2] = emitter.normalZ(i);
						}
					}
					boolean allRock = false;
					if (sideGround) {
						alpha = rock == null ? new float[4] : rockAlpha(ox, oy, oz, on, ground != null);
						if (lipHere) {
							for (int i = 0; i < 4; i++) {
								alpha[i] = Math.min(alpha[i], 1.0F - lip(grid, pos, ox[i], oy[i], oz[i]));
							}
						}
						float mean = (alpha[0] + alpha[1] + alpha[2] + alpha[3]) / 4.0F;
						float[] u = new float[4];
						float[] v = new float[4];
						if (rock != null && mean >= 0.5F) {
							// Mostly rock: the face is rock, with the ground's top faded
							// over it where it is less steep.
							rockUv(rock, pos, ox, oy, oz, on, u, v);
							for (int i = 0; i < 4; i++) {
								emitter.uv(i, u[i], v[i]);
							}
							emitter.tintIndex(-1);
							emitter.emit();
							float[] rest = new float[4];
							for (int i = 0; i < 4; i++) {
								rest[i] = 1.0F - alpha[i];
							}
							groundOverlay(emitter, ground, ox, oy, oz, oc, ol, on, rest);
						} else {
							// Mostly ground: the face is the ground's top, with rock
							// faded over it where it is steeper.
							groundUv(ground, ox, oy, oz, u, v);
							for (int i = 0; i < 4; i++) {
								emitter.uv(i, u[i], v[i]);
							}
							emitter.tintIndex(ground.tint());
							emitter.emit();
							if (rock != null) {
								cliffRock(emitter, rock, pos, ox, oy, oz, oc, ol, on, alpha);
							}
						}
						continue;
					}
					if (rockHere) {
						alpha = rockAlpha(ox, oy, oz, on, ground != null);
						allRock = alpha[0] > 0.996F && alpha[1] > 0.996F && alpha[2] > 0.996F && alpha[3] > 0.996F;
						if (allRock) {
							// Wholly rock: draw the face itself as rock, with nothing laid
							// over it, the same as a straight cliff.
							float[] u = new float[4];
							float[] v = new float[4];
							rockUv(rock, pos, ox, oy, oz, on, u, v);
							for (int i = 0; i < 4; i++) {
								emitter.uv(i, u[i], v[i]);
							}
							emitter.tintIndex(-1);
						}
					}
					emitter.emit();
					if (top && !allRock && topBounds[1] > topBounds[0]) {
						breakTiling(emitter, topBounds, pos, ox, oy, oz, oc, ol, on, quad.tintIndex());
					}
					if (blendHere && !allRock) {
						for (Blend blend : blends) {
							blend.emit(emitter, ox, oy, oz, oc, ol, on);
						}
					}
					if (rockHere && !allRock) {
						cliffRock(emitter, rock, pos, ox, oy, oz, oc, ol, on, alpha);
					}
				}
			}
		});
		captured.clear();
		waterEdge(emitter, level, pos);
	}

	/**
	 * Shores slope down under the water beside them, so the water's surface is
	 * carried on over a shore block at the water's height: where the ground
	 * dips below it you see water, and where the ground rises above it hides
	 * it, so the waterline follows the shore's curve instead of block edges.
	 */
	private static void waterEdge(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos) {
		BlockState above = level.getBlockState(pos.above());
		if (!SmoothTerrain.isOpen(above) || !above.getFluidState().isEmpty()) {
			return;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		float height = -1.0F;
		BlockPos water = null;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				cursor.setWithOffset(pos, dx, 0, dz);
				var fluid = level.getFluidState(cursor);
				if (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(cursor.above()).isEmpty()) {
					continue;
				}
				float h = fluid.getHeight(level, cursor);
				if (h > height) {
					height = h;
					water = cursor.immutable();
				}
			}
		}
		if (water == null) {
			return;
		}
		var sprite = net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
				.getSprite(net.minecraft.resources.Identifier.withDefaultNamespace("block/water_still"));
		int tint = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, water);
		int light = LightCoordsUtil.max(LightCoordsUtil.getLightCoords(level, pos.above()), LightCoordsUtil.getLightCoords(level, water));
		float[][] corners = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		for (int i = 0; i < 4; i++) {
			float x = corners[i][0];
			float z = corners[i][1];
			emitter.pos(i, x, height - 0.001F, z);
			emitter.uv(i, sprite.getU(x), sprite.getV(z));
			emitter.color(i, ARGB.opaque(tint));
			emitter.lightmap(i, light);
			emitter.normal(i, 0.0F, 1.0F, 0.0F);
		}
		emitter.nominalFace(Direction.UP);
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		emitter.ambientOcclusion(TriState.FALSE);
		emitter.shadeMode(ShadeMode.ENHANCED);
		emitter.tintIndex(-1);
		emitter.emit();
	}

	/**
	 * How much of the ground's top hangs over a side point just below the top
	 * of the block, from 1 at the lip fading to 0 a ragged distance down.
	 */
	private static float lip(float[] grid, BlockPos pos, float x, float y, float z) {
		float lx = Mth.clamp(x, 0.0F, 1.0F);
		float lz = Mth.clamp(z, 0.0F, 1.0F);
		float surface = 1.0F + smoothOffset(grid, lx, 1.0F, lz);
		float depth = surface - y;
		float wx = pos.getX() + lx;
		float wz = pos.getZ() + lz;
		float reach = 0.15F + 0.45F * (noise(wx / 1.3F + wz / 2.1F, pos.getY() * 0.7F, 11) * 0.7F + noise(wx * 1.9F, wz * 1.9F, 12) * 0.3F);
		float t = Mth.clamp(depth / reach, 0.0F, 1.0F);
		return 1.0F - t * t * (3.0F - 2.0F * t);
	}

	/** The second layer's texture repeats over this many blocks each way. */
	private static final int TILING_SPREAD = 5;
	/** Debugging: leaves out the layer that breaks up the ground texture's repeat. */
	private static final boolean NO_TILING = Boolean.getBoolean("mcsource.noTiling");

	/**
	 * Lays a second copy of a top's texture over one piece of it, turned a
	 * quarter round and spread over {@link #TILING_SPREAD} blocks, faded in
	 * and out in broad soft patches, so the ground's texture no longer reads as
	 * the same square laid over and over. It fades out where the ground turns
	 * steep, going by the smooth normals, which neighbouring blocks agree on.
	 */
	private static void breakTiling(QuadEmitter emitter, float[] bounds, BlockPos pos, float[] px, float[] py, float[] pz, int[] colour,
			int[] light, float[] normal, int tint) {
		if (NO_TILING) {
			return;
		}
		float[] alpha = new float[4];
		boolean any = false;
		for (int i = 0; i < 4; i++) {
			float wx = pos.getX() + Mth.clamp(px[i], 0.0F, 1.0F);
			float wz = pos.getZ() + Mth.clamp(pz[i], 0.0F, 1.0F);
			float n = noise(wx / 6.0F, wz / 6.0F, 7) * 0.7F + noise(wx / 2.5F, wz / 2.5F, 8) * 0.3F;
			float t = Mth.clamp((n - 0.35F) / 0.3F, 0.0F, 1.0F);
			float steep = 1.0F - Mth.clamp(normal[i * 3 + 1], 0.0F, 1.0F);
			float k = Mth.clamp((steep - 0.2F) / 0.25F, 0.0F, 1.0F);
			alpha[i] = 0.85F * t * t * (3.0F - 2.0F * t) * (1.0F - k * k * (3.0F - 2.0F * k));
			any |= alpha[i] > 0.004F;
		}
		if (!any) {
			return;
		}
		emitter.clear();
		for (int i = 0; i < 4; i++) {
			float lift = ROCK_LIFT * 0.5F;
			emitter.pos(i, px[i] + normal[i * 3] * lift, py[i] + normal[i * 3 + 1] * lift, pz[i] + normal[i * 3 + 2] * lift);
			float cx = Math.floorMod(pos.getX(), TILING_SPREAD) + Mth.clamp(px[i], 0.0F, 1.0F);
			float cz = Math.floorMod(pos.getZ(), TILING_SPREAD) + Mth.clamp(pz[i], 0.0F, 1.0F);
			float u = cz / TILING_SPREAD;
			float v = 1.0F - cx / TILING_SPREAD;
			emitter.uv(i, bounds[0] + u * (bounds[1] - bounds[0]), bounds[2] + v * (bounds[3] - bounds[2]));
			emitter.color(i, ARGB.color(Math.round(alpha[i] * 255.0F), ARGB.red(colour[i]), ARGB.green(colour[i]), ARGB.blue(colour[i])));
			emitter.lightmap(i, light[i]);
			emitter.normal(i, normal[i * 3], normal[i * 3 + 1], normal[i * 3 + 2]);
		}
		emitter.nominalFace(null);
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		emitter.ambientOcclusion(TriState.FALSE);
		emitter.shadeMode(ShadeMode.ENHANCED);
		emitter.tintIndex(tint);
		emitter.emit();
	}

	/** Smooth value noise from 0 to 1. */
	private static float noise(float x, float z, int seed) {
		int x0 = Mth.floor(x);
		int z0 = Mth.floor(z);
		float fx = x - x0;
		float fz = z - z0;
		fx = fx * fx * (3.0F - 2.0F * fx);
		fz = fz * fz * (3.0F - 2.0F * fz);
		float a = Mth.lerp(fx, hash(x0, z0, seed), hash(x0 + 1, z0, seed));
		float b = Mth.lerp(fx, hash(x0, z0 + 1, seed), hash(x0 + 1, z0 + 1, seed));
		return Mth.lerp(fz, a, b);
	}

	private static float hash(int x, int z, int seed) {
		int h = x * 374761393 + z * 668265263 + seed * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/**
	 * The rock that shows through on steep faces of soft ground (grass, dirt,
	 * sand), as Source blends rock into its displacements where they turn
	 * into cliffs; null for ground that is rock already.
	 */
	private static net.minecraft.client.renderer.texture.TextureAtlasSprite cliffSprite(BlockState state) {
		String rock;
		if (state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE)) {
			rock = "minecraft:block/sandstone";
		} else if (state.is(Blocks.RED_SAND) || state.is(Blocks.RED_SANDSTONE)) {
			rock = "minecraft:block/red_sandstone";
		} else if (state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM)) {
			rock = "minecraft:block/netherrack";
		} else if (state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
				|| state.is(Blocks.MYCELIUM) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.STONE) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF) || state.is(Blocks.CALCITE)) {
			// One rock for every cliff of ordinary ground. Minecraft's ground is
			// full of pockets of other blocks (granite, gravel, dirt in stone),
			// and on a smoothed cliff each pocket showed its own Half-Life 2
			// texture as a dark or light blotch.
			rock = "minecraft:block/stone";
		} else {
			// Any other ground (netherrack, terracotta, end stone...) is its own
			// rock: its steep faces take its own texture laid on from the side,
			// so the texture laid from above never stretches down them in streaks.
			return ownSprite(state);
		}
		return net.minecraft.client.Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
				.getSprite(net.minecraft.resources.Identifier.parse(rock));
	}

	/** A block's own texture, or null when it has none of its own name. */
	private static net.minecraft.client.renderer.texture.TextureAtlasSprite ownSprite(BlockState state) {
		net.minecraft.client.renderer.texture.TextureAtlas atlas = net.minecraft.client.Minecraft.getInstance().getAtlasManager()
				.getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
		net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
		for (String suffix : new String[] {""}) {
			net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = atlas
					.getSprite(id.withPath("block/" + id.getPath() + suffix));
			if (!sprite.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation())) {
				return sprite;
			}
		}
		return null;
	}

	/**
	 * How much rock shows at each corner of one piece of a bent face, from
	 * 0 to 1, by how steep the smoothed ground is there. It follows the
	 * smooth normals alone, which run on unbroken from piece to piece, so the
	 * rock fades in without hard edges.
	 */
	private static float[] rockAlpha(float[] px, float[] py, float[] pz, float[] normal, boolean grassy) {
		// Grass keeps to hillsides much steeper than bare ground does: a hill
		// of one-block steps stays green, and only real cliffs turn to rock.
		float from = grassy ? GRASS_ROCK_FROM : ROCK_FROM;
		float full = grassy ? GRASS_ROCK_FULL : ROCK_FULL;
		float[] alpha = new float[4];
		for (int i = 0; i < 4; i++) {
			float steep = 1.0F - Math.abs(normal[i * 3 + 1]);
			float t = Mth.clamp((steep - from) / (full - from), 0.0F, 1.0F);
			alpha[i] = t * t * (3.0F - 2.0F * t);
		}
		return alpha;
	}

	/**
	 * Rock texture coordinates for the corners of a piece: projected from
	 * the side the slope faces most, one tile over two blocks: across, by the
	 * block's place in the world, and up, over the two blocks' height a bent
	 * block can reach (half a block below its bottom to half a block above
	 * its top), so it never stretches.
	 */
	private static void rockUv(net.minecraft.client.renderer.texture.TextureAtlasSprite rock, BlockPos pos, float[] px, float[] py,
			float[] pz, float[] normal, float[] u, float[] v) {
		// Projected along the way the piece itself faces, so the texture runs
		// across it; going by the smoothed normals, a piece at the corner of a
		// cliff could be projected edge-on and smear into streaks.
		float ax = px[2] - px[0];
		float ay = py[2] - py[0];
		float az = pz[2] - pz[0];
		float bx = px[3] - px[1];
		float by = py[3] - py[1];
		float bz = pz[3] - pz[1];
		float nx = Math.abs(ay * bz - az * by);
		float nz = Math.abs(ax * by - ay * bx);
		if (nx + nz < 1.0E-5F) {
			nx = 0.0F;
			nz = 0.0F;
			for (int i = 0; i < 4; i++) {
				nx += Math.abs(normal[i * 3]);
				nz += Math.abs(normal[i * 3 + 2]);
			}
		}
		boolean alongX = nz >= nx;
		int cell = Math.floorMod(alongX ? pos.getX() : pos.getZ(), 2);
		for (int i = 0; i < 4; i++) {
			u[i] = rock.getU((cell + Mth.clamp(alongX ? px[i] : pz[i], 0.0F, 1.0F)) / 2.0F);
			v[i] = rock.getV(1.0F - Mth.clamp((py[i] + 0.5F) / 2.0F, 0.0F, 1.0F));
		}
	}

	/** Steepness (1 minus the normal's height) where rock starts to show, and where it covers the ground fully. */
	private static final float ROCK_FROM = 0.25F;
	private static final float ROCK_FULL = 0.6F;
	/** The same for grassy ground (grass, podzol, mycelium, nylium, snowy tops). */
	private static final float GRASS_ROCK_FROM = 0.45F;
	private static final float GRASS_ROCK_FULL = 0.8F;
	/** How far an overlay sits off the face beneath it, in blocks. */
	private static final float ROCK_LIFT = 0.005F;

	/**
	 * Lays rock over one piece of a steep face, faded in by how steep the
	 * ground is at each corner, with the rock texture wrapped round the
	 * hillside so it doesn't stretch.
	 */
	/** The texture on top of a ground block, and whether it takes the grass colour. */
	private record GroundTop(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, int tint) {
	}

	/**
	 * The top of a ground block whose sides look different from its top (a
	 * grass block's dirt sides), or null when its sides are its top already.
	 */
	private static GroundTop groundTop(BlockState state, boolean snowAbove) {
		String top;
		int tint = -1;
		if (snowAbove && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM))) {
			top = "block/snow";
		} else if (state.is(Blocks.GRASS_BLOCK)) {
			top = "block/grass_block_top";
			tint = 0;
		} else if (state.is(Blocks.PODZOL)) {
			top = "block/podzol_top";
		} else if (state.is(Blocks.MYCELIUM)) {
			top = "block/mycelium_top";
		} else if (state.is(Blocks.DIRT_PATH)) {
			top = "block/dirt_path_top";
		} else if (state.is(Blocks.CRIMSON_NYLIUM)) {
			top = "block/crimson_nylium";
		} else if (state.is(Blocks.WARPED_NYLIUM)) {
			top = "block/warped_nylium";
		} else if (state.is(Blocks.SANDSTONE)) {
			top = "block/sandstone_top";
		} else if (state.is(Blocks.RED_SANDSTONE)) {
			top = "block/red_sandstone_top";
		} else {
			return null;
		}
		return new GroundTop(net.minecraft.client.Minecraft.getInstance().getAtlasManager()
				.getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(net.minecraft.resources.Identifier.withDefaultNamespace(top)),
				tint);
	}

	/**
	 * The ground's top texture on a piece: laid from above where the piece
	 * lies flat enough, as on the block's top, and from the side where it is
	 * steep (spread over two blocks' height, as rock is), so it never
	 * smears into streaks.
	 */
	private static void groundUv(GroundTop ground, float[] px, float[] py, float[] pz, float[] u, float[] v) {
		float ax = px[2] - px[0];
		float ay = py[2] - py[0];
		float az = pz[2] - pz[0];
		float bx = px[3] - px[1];
		float by = py[3] - py[1];
		float bz = pz[3] - pz[1];
		float cx = ay * bz - az * by;
		float cy = az * bx - ax * bz;
		float cz = ax * by - ay * bx;
		float length = Mth.sqrt(cx * cx + cy * cy + cz * cz);
		boolean flat = length < 1.0E-5F || Math.abs(cy) / length >= 0.5F;
		boolean alongX = Math.abs(cz) >= Math.abs(cx);
		for (int i = 0; i < 4; i++) {
			if (flat) {
				u[i] = ground.sprite().getU(Mth.clamp(px[i], 0.0F, 1.0F));
				v[i] = ground.sprite().getV(Mth.clamp(pz[i], 0.0F, 1.0F));
			} else {
				u[i] = ground.sprite().getU(Mth.clamp(alongX ? px[i] : pz[i], 0.0F, 1.0F));
				v[i] = ground.sprite().getV(1.0F - Mth.clamp((py[i] + 0.5F) / 2.0F, 0.0F, 1.0F));
			}
		}
	}

	/** The ground's top faded over one piece of a face by {@code alpha} at each corner. */
	private static void groundOverlay(QuadEmitter emitter, GroundTop ground, float[] px, float[] py, float[] pz, int[] colour, int[] light,
			float[] normal, float[] alpha) {
		boolean any = false;
		for (int i = 0; i < 4; i++) {
			any |= alpha[i] > 0.004F;
		}
		if (!any) {
			return;
		}
		float[] u = new float[4];
		float[] v = new float[4];
		groundUv(ground, px, py, pz, u, v);
		emitter.clear();
		for (int i = 0; i < 4; i++) {
			emitter.pos(i, px[i] + normal[i * 3] * ROCK_LIFT, py[i] + normal[i * 3 + 1] * ROCK_LIFT, pz[i] + normal[i * 3 + 2] * ROCK_LIFT);
			emitter.uv(i, u[i], v[i]);
			emitter.color(i, ARGB.color(Math.round(alpha[i] * 255.0F), ARGB.red(colour[i]), ARGB.green(colour[i]), ARGB.blue(colour[i])));
			emitter.lightmap(i, light[i]);
			emitter.normal(i, normal[i * 3], normal[i * 3 + 1], normal[i * 3 + 2]);
		}
		emitter.nominalFace(null);
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		emitter.ambientOcclusion(TriState.FALSE);
		emitter.shadeMode(ShadeMode.ENHANCED);
		emitter.tintIndex(ground.tint());
		emitter.emit();
	}

	private static void cliffRock(QuadEmitter emitter, net.minecraft.client.renderer.texture.TextureAtlasSprite rock, BlockPos pos,
			float[] px, float[] py, float[] pz, int[] colour, int[] light, float[] normal, float[] alpha) {
		boolean any = false;
		for (int i = 0; i < 4; i++) {
			any |= alpha[i] > 0.004F;
		}
		if (!any) {
			return;
		}
		float[] u = new float[4];
		float[] v = new float[4];
		rockUv(rock, pos, px, py, pz, normal, u, v);
		emitter.clear();
		for (int i = 0; i < 4; i++) {
			// Lift the overlay a little off the face along its normal.
			emitter.pos(i, px[i] + normal[i * 3] * ROCK_LIFT, py[i] + normal[i * 3 + 1] * ROCK_LIFT, pz[i] + normal[i * 3 + 2] * ROCK_LIFT);
			emitter.uv(i, u[i], v[i]);
			emitter.color(i, ARGB.color(Math.round(alpha[i] * 255.0F), ARGB.red(colour[i]), ARGB.green(colour[i]), ARGB.blue(colour[i])));
			emitter.lightmap(i, light[i]);
			emitter.normal(i, normal[i * 3], normal[i * 3 + 1], normal[i * 3 + 2]);
		}
		emitter.nominalFace(null);
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		emitter.ambientOcclusion(TriState.FALSE);
		emitter.shadeMode(ShadeMode.ENHANCED);
		emitter.tintIndex(-1);
		emitter.emit();
	}

	/** How far into a block a neighbouring ground texture fades, in blocks. */
	private static final float BLEND_WIDTH = 0.75F;
	/** How much of a neighbour's texture shows right at the shared edge. */
	private static final float BLEND_EDGE = 0.5F;
	private static final ThreadLocal<MutableMesh> NEIGHBOUR = ThreadLocal.withInitial(() -> Renderer.get().mutableMesh());

	/**
	 * A neighbouring ground texture laid over the edge of a block's top and
	 * faded out across it, as Source blends two textures across a
	 * displacement: where grass meets stone, each runs a little way into the
	 * other instead of stopping at a hard block edge.
	 */
	private record Blend(Direction side, float[] u, float[] v, float[] x, float[] z, int tint) {
		void emit(QuadEmitter emitter, float[] px, float[] py, float[] pz, int[] colour, int[] light, float[] normal) {
			float[] alpha = new float[4];
			boolean any = false;
			for (int i = 0; i < 4; i++) {
				float distance = switch (side) {
					case EAST -> 1.0F - px[i];
					case WEST -> px[i];
					case SOUTH -> 1.0F - pz[i];
					default -> pz[i];
				};
				float t = Mth.clamp(1.0F - distance / BLEND_WIDTH, 0.0F, 1.0F);
				alpha[i] = BLEND_EDGE * t * t * (3.0F - 2.0F * t);
				any |= alpha[i] > 0.004F;
			}
			if (!any) {
				return;
			}
			emitter.clear();
			for (int i = 0; i < 4; i++) {
				emitter.pos(i, px[i], py[i] + ROCK_LIFT, pz[i]);
				float[] uv = uvAt(px[i], pz[i]);
				emitter.uv(i, uv[0], uv[1]);
				int c = ARGB.multiply(colour[i], tint);
				emitter.color(i, ARGB.color(Math.round(alpha[i] * 255.0F), ARGB.red(c), ARGB.green(c), ARGB.blue(c)));
				emitter.lightmap(i, light[i]);
				emitter.normal(i, normal[i * 3], normal[i * 3 + 1], normal[i * 3 + 2]);
			}
			emitter.nominalFace(Direction.UP);
			emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
			emitter.ambientOcclusion(TriState.FALSE);
			emitter.shadeMode(ShadeMode.ENHANCED);
			emitter.tintIndex(-1);
			emitter.emit();
		}

		/** Texture coordinates at a point on the top face, from the neighbour's top face corners. */
		private float[] uvAt(float px, float pz) {
			// The top face's corners sit at the corners of the unit square; blend between them.
			float[] out = new float[2];
			float total = 0.0F;
			for (int i = 0; i < 4; i++) {
				float w = (1.0F - Math.abs(x[i] - px)) * (1.0F - Math.abs(z[i] - pz));
				out[0] += u[i] * w;
				out[1] += v[i] * w;
				total += w;
			}
			if (total > 0.0F) {
				out[0] /= total;
				out[1] /= total;
			}
			return out;
		}
	}

	/**
	 * The neighbouring ground textures to blend over this block's top, or
	 * null when there are none: a different smoothed block beside it, at the
	 * same height or one up or down, with open space above.
	 */
	private static java.util.List<Blend> blends(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		if (!SmoothTerrain.isOpen(level.getBlockState(pos.above()))) {
			return null;
		}
		java.util.List<Blend> out = null;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			for (int dy : new int[] {0, 1, -1}) {
				cursor.setWithOffset(pos, side.getStepX(), dy, side.getStepZ());
				BlockState next = level.getBlockState(cursor);
				if (!next.is(SmoothTerrain.SMOOTH) || !SmoothTerrain.isOpen(level.getBlockState(cursor.above()))) {
					continue;
				}
				if (next.getBlock() != state.getBlock()) {
					Blend blend = topOf(level, cursor.immutable(), next, pos, side);
					if (blend != null) {
						if (out == null) {
							out = new java.util.ArrayList<>();
						}
						out.add(blend);
					}
				}
				break;
			}
		}
		return out;
	}

	/** The top face of a neighbouring block, its texture spread as if it lay at {@code at}. */
	private static Blend topOf(BlockAndTintGetter level, BlockPos neighbour, BlockState state, BlockPos at, Direction side) {
		BlockStateModel model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		if (model == null) {
			return null;
		}
		MutableMesh mesh = NEIGHBOUR.get();
		mesh.clear();
		RandomSource random = RandomSource.create(state.getSeed(neighbour));
		// The empty level gives the model's own unbent quads.
		((net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel) model).emitQuads(mesh.emitter(), BlockAndTintGetter.EMPTY,
				neighbour, state, random, direction -> false);
		Blend[] found = new Blend[1];
		mesh.forEachMutable(quad -> {
			if (found[0] != null || quad.faceNormal().y() < 0.5F) {
				return;
			}
			spreadTexture(quad, at);
			float[] u = new float[4];
			float[] v = new float[4];
			float[] x = new float[4];
			float[] z = new float[4];
			for (int i = 0; i < 4; i++) {
				u[i] = quad.u(i);
				v[i] = quad.v(i);
				x[i] = Math.round(quad.x(i));
				z[i] = Math.round(quad.z(i));
			}
			int tint = -1;
			if (quad.tintIndex() >= 0) {
				var source = net.minecraft.client.Minecraft.getInstance().getBlockColors().getTintSource(state, quad.tintIndex());
				if (source != null) {
					tint = 0xFF000000 | source.colorInWorld(state, level, neighbour);
				}
			}
			found[0] = new Blend(side, u, v, x, z, tint);
		});
		mesh.clear();
		return found[0];
	}

	/** Packed light blended per channel. */
	private static int blend(int[] light, float w0, float w1, float w2, float w3) {
		float block = (light[0] & 0xFFFF) * w0 + (light[1] & 0xFFFF) * w1 + (light[2] & 0xFFFF) * w2 + (light[3] & 0xFFFF) * w3;
		float sky = (light[0] >>> 16) * w0 + (light[1] >>> 16) * w1 + (light[2] >>> 16) * w2 + (light[3] >>> 16) * w3;
		return Math.round(block) | Math.round(sky) << 16;
	}

	private static int blendColor(int[] color, float w0, float w1, float w2, float w3) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			float c = (color[0] >>> shift & 0xFF) * w0 + (color[1] >>> shift & 0xFF) * w1 + (color[2] >>> shift & 0xFF) * w2
					+ (color[3] >>> shift & 0xFF) * w3;
			out |= Math.min(255, Math.round(c)) << shift;
		}
		return out;
	}

	/** Trilinear blend of per-corner values ({@code size} each, corner index cx + 2 * cy + 4 * cz). */
	private static void trilinear(float[] values, int size, float x, float y, float z, float[] out) {
		for (int k = 0; k < size; k++) {
			out[k] = 0.0F;
		}
		for (int corner = 0; corner < 8; corner++) {
			float w = ((corner & 1) != 0 ? x : 1 - x) * ((corner & 2) != 0 ? y : 1 - y) * ((corner & 4) != 0 ? z : 1 - z);
			for (int k = 0; k < size; k++) {
				out[k] += values[corner * size + k] * w;
			}
		}
	}

	/**
	 * Surface normals at the block's eight corners, each blended from the
	 * corner and the 26 around it, so shading turns gently over the terrain
	 * instead of in the eight directions one corner alone can give.
	 */
	private static float[] smoothNormals(int[] wide) {
		float[][] raw = new float[64][3];
		for (int gy = -1; gy <= 2; gy++) {
			for (int gz = -1; gz <= 2; gz++) {
				for (int gx = -1; gx <= 2; gx++) {
					SmoothTerrain.wideCornerGradient(wide, gx, gy, gz, raw[node(gx, gy, gz)]);
				}
			}
		}
		float[] out = new float[24];
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			float x = 0;
			float y = 0;
			float z = 0;
			for (int oy = -1; oy <= 1; oy++) {
				for (int oz = -1; oz <= 1; oz++) {
					for (int ox = -1; ox <= 1; ox++) {
						float w = (ox == 0 ? 2 : 1) * (oy == 0 ? 2 : 1) * (oz == 0 ? 2 : 1);
						float[] g = raw[node(cx + ox, cy + oy, cz + oz)];
						x += g[0] * w;
						y += g[1] * w;
						z += g[2] * w;
					}
				}
			}
			float length = Mth.sqrt(x * x + y * y + z * z);
			if (length > 1.0E-4F) {
				out[corner * 3] = x / length;
				out[corner * 3 + 1] = y / length;
				out[corner * 3 + 2] = z / length;
			}
		}
		return out;
	}

	/**
	 * A bent quad is drawn as two triangles split along the 0-2 diagonal. When
	 * the other diagonal is flatter, rotate the vertices so the fold runs along
	 * it; that keeps ridges and valleys following the terrain and avoids
	 * stretched creases across the texture.
	 */
	private static void splitAlongFlatterDiagonal(MutableQuadView quad) {
		float diagonal02 = Math.abs(quad.y(0) - quad.y(2));
		float diagonal13 = Math.abs(quad.y(1) - quad.y(3));
		if (diagonal02 <= diagonal13 + 1.0E-4F) {
			return;
		}
		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] u = new float[4];
		float[] v = new float[4];
		int[] color = new int[4];
		int[] lightmap = new int[4];
		float[] nx = new float[4];
		float[] ny = new float[4];
		float[] nz = new float[4];
		boolean[] hasNormal = new boolean[4];
		for (int i = 0; i < 4; i++) {
			px[i] = quad.x(i);
			py[i] = quad.y(i);
			pz[i] = quad.z(i);
			u[i] = quad.u(i);
			v[i] = quad.v(i);
			color[i] = quad.color(i);
			lightmap[i] = quad.lightmap(i);
			hasNormal[i] = quad.hasNormal(i);
			nx[i] = quad.normalX(i);
			ny[i] = quad.normalY(i);
			nz[i] = quad.normalZ(i);
		}
		for (int i = 0; i < 4; i++) {
			int from = (i + 1) & 3;
			quad.pos(i, px[from], py[from], pz[from]);
			quad.uv(i, u[from], v[from]);
			quad.color(i, color[from]);
			quad.lightmap(i, lightmap[from]);
			if (hasNormal[from]) {
				quad.normal(i, nx[from], ny[from], nz[from]);
			}
		}
	}

	/**
	 * Smooth light for each of the block's eight corners: the light of the
	 * brightest open block around that corner, so moved faces never sample light from
	 * inside solid ground. Corners with less open space around them are shaded
	 * slightly darker, standing in for ambient occlusion.
	 */
	private static void cornerLighting(BlockAndTintGetter level, BlockPos pos, int[] open, float[] blockLight, float[] skyLight,
			float[] shade) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[] cellLight = new int[27];
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int index = dx + 1 + 3 * (dz + 1) + 9 * (dy + 1);
					if (open[index] != 0) {
						cellLight[index] = LightCoordsUtil.getLightCoords(level, cursor.setWithOffset(pos, dx, dy, dz));
					}
				}
			}
		}
		int fallback = LightCoordsUtil.getLightCoords(level, cursor.setWithOffset(pos, 0, 1, 0));
		for (int corner = 0; corner < 8; corner++) {
			int cx = corner & 1;
			int cy = corner >> 1 & 1;
			int cz = corner >> 2 & 1;
			int count = 0;
			int blockMax = 0;
			int skyMax = 0;
			for (int oy = cy - 1; oy <= cy; oy++) {
				for (int oz = cz - 1; oz <= cz; oz++) {
					for (int ox = cx - 1; ox <= cx; ox++) {
						int index = ox + 1 + 3 * (oz + 1) + 9 * (oy + 1);
						if (open[index] != 0) {
							int cell = cellLight[index];
							count++;
							blockMax = Math.max(blockMax, LightCoordsUtil.block(cell));
							skyMax = Math.max(skyMax, LightCoordsUtil.sky(cell));
						}
					}
				}
			}
			// The brightest open cell, not the average: the renderer never lets a
			// face be darker than the cell it faces, so an average would be raised
			// on some faces and not others, and the facets would show.
			if (count == 0) {
				blockMax = LightCoordsUtil.block(fallback);
				skyMax = LightCoordsUtil.sky(fallback);
			}
			blockLight[corner] = blockMax;
			skyLight[corner] = skyMax;
			shade[corner] = 0.92F + 0.08F * Math.min(count, 4) / 4.0F;
		}
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		// Smoothed geometry depends on the neighbours, so it must not be shared.
		if (state.is(SmoothTerrain.SMOOTH) || state.is(SmoothTerrain.FOLLOWS) || state.getBlock() instanceof net.minecraft.world.level.block.VineBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.MultifaceBlock) {
			return null;
		}
		return super.createGeometryKey(level, pos, state, random);
	}
}
