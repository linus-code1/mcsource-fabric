package com.mcsource.model;

import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A skinned, animated model in one common shape, whichever file it came from
 * (Half-Life 1 {@code .mdl}, or a Source engine {@code .mdl} with its
 * {@code .vvd}, {@code .vtx} and materials).
 *
 * <p>Vertices are stored in model space and weighted to up to three bones.
 * Each bone carries its inverse bind matrix, which takes a model-space vertex
 * into that bone's space. Animations hold every bone's local position and
 * rotation per frame.
 */
public final class RiggedModel {
	public final String name;
	public final String[] boneNames;
	public final int[] parents;
	/** Model space to bone space in the bind pose, per bone. */
	public final Matrix4f[] inverseBind;
	/** Bind pose local position (3 per bone) and rotation (x, y, z, w per bone). */
	public final float[] bindPosition;
	public final float[] bindRotation;

	/** Vertex positions, 3 per vertex, with up to 3 bone weights each. */
	public final float[] positions;
	public final int[] positionBones;
	public final float[] positionWeights;
	/** Normals, 3 per normal, with up to 3 bone weights each. */
	public final float[] normals;
	public final int[] normalBones;
	public final float[] normalWeights;

	/** Triangle corners: vertex index, normal index and texture coordinates (0 to 1 across the texture). */
	public final int[] cornerVertex;
	public final int[] cornerNormal;
	public final float[] cornerUv;
	/** Texture of each triangle. */
	public final int[] triangleTexture;

	public final Texture[] textures;
	public final Sequence[] sequences;

	public RiggedModel(String name, String[] boneNames, int[] parents, Matrix4f[] inverseBind, float[] bindPosition, float[] bindRotation,
			float[] positions, int[] positionBones, float[] positionWeights, float[] normals, int[] normalBones, float[] normalWeights,
			int[] cornerVertex, int[] cornerNormal, float[] cornerUv, int[] triangleTexture, Texture[] textures, Sequence[] sequences) {
		this.name = name;
		this.boneNames = boneNames;
		this.parents = parents;
		this.inverseBind = inverseBind;
		this.bindPosition = bindPosition;
		this.bindRotation = bindRotation;
		this.positions = positions;
		this.positionBones = positionBones;
		this.positionWeights = positionWeights;
		this.normals = normals;
		this.normalBones = normalBones;
		this.normalWeights = normalWeights;
		this.cornerVertex = cornerVertex;
		this.cornerNormal = cornerNormal;
		this.cornerUv = cornerUv;
		this.triangleTexture = triangleTexture;
		this.textures = textures;
		this.sequences = sequences;
	}

	public int boneCount() {
		return boneNames.length;
	}

	public int triangleCount() {
		return triangleTexture.length;
	}

	/** A texture as ARGB pixels. {@code masked} textures use their alpha as a cut-out. */
	public record Texture(String name, int width, int height, int[] pixels, boolean masked) {
	}

	/**
	 * An animation. {@code frames[f][b * 7 + c]} holds bone b's local position
	 * (c = 0-2) and rotation quaternion x, y, z, w (c = 3-6) at frame f.
	 * {@code distance} is how far the animation walks in one cycle, in model
	 * units, or 0.
	 */
	public record Sequence(String label, String activity, float fps, boolean looping, float distance, float[][] frames) {
		public int frameCount() {
			return frames.length;
		}

		boolean matches(String wanted) {
			return label.toLowerCase(Locale.ROOT).startsWith(wanted) || activity.toLowerCase(Locale.ROOT).equals(wanted);
		}
	}

	/** Finds a sequence whose name starts with (or whose activity is) one of the given names, tried in order. */
	public Sequence find(String... names) {
		for (String wanted : names) {
			String lower = wanted.toLowerCase(Locale.ROOT);
			for (Sequence sequence : sequences) {
				if (sequence.matches(lower)) {
					return sequence;
				}
			}
		}
		return null;
	}

	/** Bone positions and rotations for one moment of an animation. */
	public static final class Pose {
		final float[] position;
		final Quaternionf[] rotation;

		public Pose(RiggedModel model) {
			position = new float[model.boneCount() * 3];
			rotation = new Quaternionf[model.boneCount()];
			for (int i = 0; i < rotation.length; i++) {
				rotation[i] = new Quaternionf();
			}
		}
	}

	/** Writes the bones of {@code sequence} at a (fractional) frame into {@code pose}. */
	public void pose(Sequence sequence, float frame, Pose pose) {
		int count = sequence.frames.length;
		float f;
		if (sequence.looping) {
			float base = (float) Math.floor(frame);
			f = Math.floorMod((int) base, count) + (frame - base);
		} else {
			f = Math.max(0.0F, Math.min(frame, count - 1));
		}
		int a = Math.min((int) f, count - 1);
		int b = sequence.looping ? (a + 1) % count : Math.min(a + 1, count - 1);
		float t = f - (int) f;
		float[] fa = sequence.frames[a];
		float[] fb = sequence.frames[b];
		Quaternionf qb = new Quaternionf();
		for (int bone = 0; bone < boneCount(); bone++) {
			int o = bone * 7;
			for (int c = 0; c < 3; c++) {
				pose.position[bone * 3 + c] = fa[o + c] + (fb[o + c] - fa[o + c]) * t;
			}
			pose.rotation[bone].set(fa[o + 3], fa[o + 4], fa[o + 5], fa[o + 6]);
			qb.set(fb[o + 3], fb[o + 4], fb[o + 5], fb[o + 6]);
			pose.rotation[bone].slerp(qb, t);
		}
	}

	/** The bind pose, for models without animations. */
	public void bindPose(Pose pose) {
		for (int bone = 0; bone < boneCount(); bone++) {
			System.arraycopy(bindPosition, bone * 3, pose.position, bone * 3, 3);
			pose.rotation[bone].set(bindRotation[bone * 4], bindRotation[bone * 4 + 1], bindRotation[bone * 4 + 2], bindRotation[bone * 4 + 3]);
		}
	}

	/** Blends {@code other} into {@code pose} by {@code weight} (0 keeps {@code pose}). */
	public void blend(Pose pose, Pose other, float weight) {
		if (weight <= 0.0F) {
			return;
		}
		for (int bone = 0; bone < boneCount(); bone++) {
			for (int c = 0; c < 3; c++) {
				int i = bone * 3 + c;
				pose.position[i] += (other.position[i] - pose.position[i]) * weight;
			}
			pose.rotation[bone].slerp(other.rotation[bone], weight);
		}
	}

	/** Blends {@code other} into {@code pose} by {@code weight}, only for the bones marked in {@code bones}. */
	public void blend(Pose pose, Pose other, float weight, boolean[] bones) {
		if (weight <= 0.0F) {
			return;
		}
		for (int bone = 0; bone < boneCount(); bone++) {
			if (!bones[bone]) {
				continue;
			}
			for (int c = 0; c < 3; c++) {
				int i = bone * 3 + c;
				pose.position[i] += (other.position[i] - pose.position[i]) * weight;
			}
			pose.rotation[bone].slerp(other.rotation[bone], weight);
		}
	}

	/** Each bone's skinning matrix for a pose: model-space bind vertex to posed model space. */
	public void skinning(Pose pose, Matrix4f[] out) {
		Matrix4f[] world = new Matrix4f[boneCount()];
		for (int bone = 0; bone < boneCount(); bone++) {
			Matrix4f local = new Matrix4f().translation(pose.position[bone * 3], pose.position[bone * 3 + 1], pose.position[bone * 3 + 2])
					.rotate(pose.rotation[bone]);
			int parent = parents[bone];
			// Parents always come before their children.
			world[bone] = parent >= 0 && parent < bone ? new Matrix4f(world[parent]).mul(local) : local;
		}
		for (int bone = 0; bone < boneCount(); bone++) {
			out[bone] = new Matrix4f(world[bone]).mul(inverseBind[bone]);
		}
	}
}
