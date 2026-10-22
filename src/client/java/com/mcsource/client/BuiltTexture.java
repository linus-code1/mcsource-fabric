package com.mcsource.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3fc;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import com.mcsource.terrain.SmoothTerrain;

/**
 * Half-Life 2 textures on built blocks (bricks, planks, concrete and the
 * like), laid as Source lays a material on a wall: one texture spreads over
 * {@link #SPREAD} by {@link #SPREAD} blocks instead of starting again on
 * every block, and where two different materials meet, each fades a little
 * way into the other across the joint. Ground blends much further; walls
 * only soften the seam. Blocks still showing Minecraft's own small textures
 * are left exactly as they are.
 */
final class BuiltTexture {
	/** Blocks one texture spreads over, each way. */
	static final int SPREAD = 2;
	/** How far into a block the neighbouring material fades, in blocks. */
	private static final float BLEND_WIDTH = 0.3F;
	/** How much of the neighbouring material shows right at the joint. */
	private static final float BLEND_EDGE = 0.4F;
	/** How far the faded strip sits off the face, as SmoothBlockModel's overlays do. */
	private static final float LIFT = 0.012F;
	/** Textures larger than Minecraft's 16 pixels are the player's Half-Life 2 ones. */
	private static final int MINECRAFT_SIZE = 16;

	/** One face of a built block, kept to lay the blends over after the model is drawn. */
	record Face(Direction side, TextureAtlasSprite sprite, int[] light, int[] colour, float[] corners) {
	}

	private BuiltTexture() {
	}

	/** Whether a block is built of a plain material: a full, solid cube not turned any way. */
	static boolean applies(BlockState state) {
		// The mirror's texture is a picture of its surroundings, read by the
		// shader as a whole; spreading or blending it would cut it up.
		return !state.is(SmoothTerrain.SMOOTH) && !state.is(com.mcsource.block.MirrorBlocks.MIRROR) && state.isSolidRender() && !state.hasProperty(BlockStateProperties.AXIS)
				&& !state.hasProperty(BlockStateProperties.FACING) && !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
	}

	private static SpriteFinder finder() {
		return ((FabricTextureAtlas) (Object) Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)).spriteFinder();
	}

	private static boolean halfLife(TextureAtlasSprite sprite) {
		return sprite != null && sprite.contents().width() > MINECRAFT_SIZE;
	}

	/**
	 * Lays a face's texture by the block's place in the world, spread over
	 * several blocks, when it is a whole face with a Half-Life 2 texture.
	 * Returns the face to blend later, or null when it was left alone.
	 */
	static Face spread(MutableQuadView quad, BlockPos pos) {
		Direction side = side(quad);
		if (side == null) {
			return null;
		}
		TextureAtlasSprite sprite = finder().find(quad);
		if (!halfLife(sprite)) {
			return null;
		}
		int[] light = new int[4];
		int[] colour = new int[4];
		float[] uv = new float[2];
		for (int i = 0; i < 4; i++) {
			uv(sprite, side, pos, quad.x(i), quad.y(i), quad.z(i), uv);
			quad.uv(i, uv[0], uv[1]);
		}
		return new Face(side, sprite, light, colour, new float[12]);
	}

	/** Records a face's final light and colour, once sun and shade are on it. */
	static void finish(Face face, MutableQuadView quad) {
		for (int i = 0; i < 4; i++) {
			face.light()[i] = quad.lightmap(i);
			face.colour()[i] = quad.color(i);
			face.corners()[i * 3] = quad.x(i);
			face.corners()[i * 3 + 1] = quad.y(i);
			face.corners()[i * 3 + 2] = quad.z(i);
		}
	}

	/** The side a quad covers whole, or null when it is not a whole block face. */
	private static Direction side(MutableQuadView quad) {
		Vector3fc n = quad.faceNormal();
		Direction side = Direction.getApproximateNearest(n.x(), n.y(), n.z());
		if (Math.abs(n.get(side.getAxis().ordinal())) < 0.99F) {
			return null;
		}
		float plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
		for (int axis = 0; axis < 3; axis++) {
			float lo = Float.MAX_VALUE;
			float hi = -Float.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				float p = quad.posByIndex(i, axis);
				lo = Math.min(lo, p);
				hi = Math.max(hi, p);
			}
			if (axis == side.getAxis().ordinal()) {
				if (Math.abs(lo - plane) > 1.0E-3F || Math.abs(hi - plane) > 1.0E-3F) {
					return null;
				}
			} else if (lo > 1.0E-3F || hi < 1.0F - 1.0E-3F) {
				return null;
			}
		}
		return side;
	}

	/**
	 * Texture coordinates at a point of a block (in the block's own
	 * coordinates): the texture runs along the world, up the way on walls,
	 * so neighbouring blocks continue it.
	 */
	private static void uv(TextureAtlasSprite sprite, Direction side, BlockPos pos, float x, float y, float z, float[] out) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		y = Mth.clamp(y, 0.0F, 1.0F);
		z = Mth.clamp(z, 0.0F, 1.0F);
		float u;
		float v;
		if (side.getAxis() == Direction.Axis.Y) {
			u = (Math.floorMod(pos.getX(), SPREAD) + x) / SPREAD;
			v = (Math.floorMod(pos.getZ(), SPREAD) + z) / SPREAD;
		} else {
			boolean alongX = side.getAxis() == Direction.Axis.Z;
			boolean forward = alongX ? side == Direction.SOUTH : side == Direction.WEST;
			int coordinate = alongX ? pos.getX() : pos.getZ();
			float t = alongX ? x : z;
			if (!forward) {
				t = 1.0F - t;
				coordinate = -coordinate - 1;
			}
			u = (Math.floorMod(coordinate, SPREAD) + t) / SPREAD;
			v = (Math.floorMod(-pos.getY() - 1, SPREAD) + 1.0F - y) / SPREAD;
		}
		out[0] = sprite.getU(u);
		out[1] = sprite.getV(v);
	}

	/**
	 * Fades each different neighbouring material a little way into the face
	 * across the joint, where the neighbour shows the same face beside it.
	 */
	static void blend(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, List<Face> faces) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		float[] uv = new float[2];
		for (Face face : faces) {
			Direction side = face.side();
			for (Direction edge : Direction.values()) {
				if (edge.getAxis() == side.getAxis()) {
					continue;
				}
				BlockState other = level.getBlockState(cursor.setWithOffset(pos, edge));
				if (other == state || !applies(other)) {
					continue;
				}
				// The neighbour's face must show beside this one, not be covered.
				if (level.getBlockState(cursor.move(side)).isSolidRender()) {
					continue;
				}
				TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(other).particleMaterial().sprite();
				if (!halfLife(sprite) || sprite == face.sprite()) {
					continue;
				}
				strip(emitter, pos, face, edge, sprite, uv);
			}
		}
	}

	private static void strip(QuadEmitter emitter, BlockPos pos, Face face, Direction edge, TextureAtlasSprite sprite, float[] uv) {
		Direction side = face.side();
		int sideAxis = side.getAxis().ordinal();
		int edgeAxis = edge.getAxis().ordinal();
		int alongAxis = 3 - sideAxis - edgeAxis;
		float plane = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F + LIFT : -LIFT;
		float outer = edge.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
		float inner = edge.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F - BLEND_WIDTH : BLEND_WIDTH;
		// Corners: two on the joint, two inside; wound to face outwards.
		float[][] corners = {{outer, 0.0F}, {outer, 1.0F}, {inner, 1.0F}, {inner, 0.0F}};
		float[] p = new float[3];
		float[][] points = new float[4][];
		for (int i = 0; i < 4; i++) {
			p[sideAxis] = plane;
			p[edgeAxis] = corners[i][0];
			p[alongAxis] = corners[i][1];
			points[i] = p.clone();
		}
		// Keep the winding facing out of the block.
		float[] a = sub(points[1], points[0]);
		float[] b = sub(points[2], points[0]);
		float facing = (a[1] * b[2] - a[2] * b[1]) * side.getStepX() + (a[2] * b[0] - a[0] * b[2]) * side.getStepY()
				+ (a[0] * b[1] - a[1] * b[0]) * side.getStepZ();
		if (facing < 0.0F) {
			float[] swap = points[1];
			points[1] = points[3];
			points[3] = swap;
		}
		emitter.clear();
		for (int i = 0; i < 4; i++) {
			float[] q = points[i];
			emitter.pos(i, q[0], q[1], q[2]);
			uv(sprite, side, pos, q[0], q[1], q[2], uv);
			emitter.uv(i, uv[0], uv[1]);
			boolean onJoint = Math.abs(q[edgeAxis] - outer) < 1.0E-4F;
			// Light and colour from the face's nearest corner.
			int nearest = nearest(face, q, sideAxis);
			int c = face.colour()[nearest];
			float alpha = onJoint ? BLEND_EDGE : 0.0F;
			emitter.color(i, ARGB.color(Math.round(alpha * 255.0F), ARGB.red(c), ARGB.green(c), ARGB.blue(c)));
			emitter.lightmap(i, face.light()[nearest]);
			emitter.normal(i, side.getStepX(), side.getStepY(), side.getStepZ());
		}
		emitter.nominalFace(side);
		emitter.cullFace(null);
		emitter.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		emitter.ambientOcclusion(TriState.FALSE);
		emitter.tintIndex(-1);
		emitter.emit();
	}

	private static float[] sub(float[] a, float[] b) {
		return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	/** Which of the face's corners is nearest a point on it. */
	private static int nearest(Face face, float[] q, int sideAxis) {
		int best = 0;
		float bestDistance = Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float distance = 0.0F;
			for (int axis = 0; axis < 3; axis++) {
				if (axis != sideAxis) {
					distance += Mth.square(face.corners()[i * 3 + axis] - q[axis]);
				}
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	static List<Face> list() {
		return new ArrayList<>(6);
	}
}
