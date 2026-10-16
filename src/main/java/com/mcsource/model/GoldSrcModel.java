package com.mcsource.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A skeletal model read from a GoldSrc (Half-Life 1) {@code .mdl} file, version
 * 10: its bones, the triangles of each body part's first model, its paletted
 * textures and its animation sequences.
 *
 * <p>This mod never ships Valve's models. The files are read at run time from
 * the player's own copy of the game. The reader is written for this mod from
 * public descriptions of the file layout; no Valve code is used.
 */
public final class GoldSrcModel {
	private static final int MAGIC_MODEL = 0x54534449; // "IDST"
	private static final int MAGIC_SEQUENCES = 0x51534449; // "IDSQ"
	private static final int VERSION = 10;

	/** Texture flag: palette index 255 is see-through. */
	public static final int TEXTURE_MASKED = 0x40;
	// Sequence motion flags: the motion bone's travel along these axes is
	// taken out of the animation, since the entity itself moves.
	private static final int MOTION_X = 0x1;
	private static final int MOTION_Y = 0x2;
	private static final int MOTION_Z = 0x4;

	public final String name;
	public final Bone[] bones;
	public final Sequence[] sequences;
	public final Texture[] textures;
	/** Every triangle of the model, already resolved to its texture. */
	public final List<Triangle> triangles;
	/** Vertex positions (x, y, z) in the space of the bone in {@link #vertexBones}. */
	public final float[] vertices;
	public final int[] vertexBones;
	public final float[] normals;
	public final int[] normalBones;

	private GoldSrcModel(String name, Bone[] bones, Sequence[] sequences, Texture[] textures, List<Triangle> triangles, float[] vertices,
			int[] vertexBones, float[] normals, int[] normalBones) {
		this.name = name;
		this.bones = bones;
		this.sequences = sequences;
		this.textures = textures;
		this.triangles = triangles;
		this.vertices = vertices;
		this.vertexBones = vertexBones;
		this.normals = normals;
		this.normalBones = normalBones;
	}

	public record Bone(String name, int parent, float[] value, float[] scale) {
	}

	/**
	 * An animation. {@code frames[f][b * 6 + c]} holds bone b's position (c =
	 * 0-2) and Euler angles in radians (c = 3-5) at frame f.
	 */
	public record Sequence(String label, float fps, boolean looping, int activity, float distance, float[][] frames) {
		public int frameCount() {
			return frames.length;
		}
	}

	/** A texture as ARGB pixels. */
	public record Texture(String name, int flags, int width, int height, int[] pixels) {
	}

	/**
	 * A triangle. Each corner is a vertex index, a normal index, and texture
	 * coordinates in texels of {@code texture}.
	 */
	public record Triangle(int texture, int[] vertex, int[] normal, float[] s, float[] t) {
	}

	/** Finds a sequence whose name starts with one of the given names, tried in order. */
	public Sequence find(String... names) {
		for (String wanted : names) {
			for (Sequence sequence : sequences) {
				if (sequence.label.toLowerCase(Locale.ROOT).startsWith(wanted)) {
					return sequence;
				}
			}
		}
		return null;
	}

	/** Writes the bones' rest pose of {@code sequence} at a (fractional) frame into {@code pose}. */
	public void pose(Sequence sequence, float frame, Pose pose) {
		int count = sequence.frames.length;
		float f = sequence.looping ? Math.floorMod((int) Math.floor(frame), count) + (frame - (float) Math.floor(frame))
				: Math.max(0.0F, Math.min(frame, count - 1));
		int a = Math.min((int) f, count - 1);
		int b = sequence.looping ? (a + 1) % count : Math.min(a + 1, count - 1);
		float t = f - (int) f;
		float[] fa = sequence.frames[a];
		float[] fb = sequence.frames[b];
		Quaternionf qb = new Quaternionf();
		for (int bone = 0; bone < bones.length; bone++) {
			int o = bone * 6;
			for (int c = 0; c < 3; c++) {
				pose.position[bone * 3 + c] = fa[o + c] + (fb[o + c] - fa[o + c]) * t;
			}
			euler(fa, o + 3, pose.rotation[bone]);
			euler(fb, o + 3, qb);
			pose.rotation[bone].slerp(qb, t);
		}
	}

	/** Blends {@code other} into {@code pose} by {@code weight} (0 keeps {@code pose}). */
	public void blend(Pose pose, Pose other, float weight) {
		if (weight <= 0.0F) {
			return;
		}
		for (int bone = 0; bone < bones.length; bone++) {
			for (int c = 0; c < 3; c++) {
				int i = bone * 3 + c;
				pose.position[i] += (other.position[i] - pose.position[i]) * weight;
			}
			pose.rotation[bone].slerp(other.rotation[bone], weight);
		}
	}

	/** Builds each bone's model-space matrix from a pose. */
	public void matrices(Pose pose, Matrix4f[] out) {
		for (int bone = 0; bone < bones.length; bone++) {
			Matrix4f local = new Matrix4f().translation(pose.position[bone * 3], pose.position[bone * 3 + 1], pose.position[bone * 3 + 2])
					.rotate(pose.rotation[bone]);
			int parent = bones[bone].parent;
			// Parents always come before their children in the file.
			out[bone] = parent >= 0 && parent < bone ? new Matrix4f(out[parent]).mul(local) : local;
		}
	}

	/** Euler angles stored as rotations about x, then y, then z. */
	private static void euler(float[] values, int offset, Quaternionf out) {
		out.rotationZYX(values[offset + 2], values[offset + 1], values[offset]);
	}

	/** Bone positions and rotations for one moment of an animation. */
	public static final class Pose {
		final float[] position;
		final Quaternionf[] rotation;

		public Pose(GoldSrcModel model) {
			position = new float[model.bones.length * 3];
			rotation = new Quaternionf[model.bones.length];
			for (int i = 0; i < rotation.length; i++) {
				rotation[i] = new Quaternionf();
			}
		}
	}

	/**
	 * Reads a model. Textures stored in a companion {@code <name>T.mdl} and
	 * animations stored in {@code <name>01.mdl} and so on are read from beside it.
	 */
	public static GoldSrcModel load(Path file) throws IOException {
		ByteBuffer data = read(file);
		if (data.getInt(0) != MAGIC_MODEL) {
			throw new IOException(file + " is not a GoldSrc model");
		}
		int version = data.getInt(4);
		if (version != VERSION) {
			throw new IOException(file + " is model version " + version + ", only version " + VERSION + " (Half-Life 1) is supported");
		}
		String name = string(data, 8, 64);

		Bone[] bones = readBones(data);
		Sequence[] sequences = readSequences(data, file, bones);

		ByteBuffer textureSource = data;
		if (data.getInt(180) == 0) {
			Path textureFile = sibling(file, "T");
			if (Files.isRegularFile(textureFile)) {
				textureSource = read(textureFile);
			}
		}
		Texture[] textures = readTextures(textureSource);
		short[] skins = readSkins(textureSource);

		List<Triangle> triangles = new ArrayList<>();
		List<float[]> vertexList = new ArrayList<>();
		List<float[]> normalList = new ArrayList<>();
		readGeometry(data, skins, textures.length, triangles, vertexList, normalList);
		float[] vertices = new float[vertexList.size() * 3];
		int[] vertexBones = new int[vertexList.size()];
		for (int i = 0; i < vertexList.size(); i++) {
			float[] v = vertexList.get(i);
			System.arraycopy(v, 0, vertices, i * 3, 3);
			vertexBones[i] = (int) v[3];
		}
		float[] normals = new float[normalList.size() * 3];
		int[] normalBones = new int[normalList.size()];
		for (int i = 0; i < normalList.size(); i++) {
			float[] n = normalList.get(i);
			System.arraycopy(n, 0, normals, i * 3, 3);
			normalBones[i] = (int) n[3];
		}
		return new GoldSrcModel(name, bones, sequences, textures, triangles, vertices, vertexBones, normals, normalBones);
	}

	private static ByteBuffer read(Path file) throws IOException {
		return ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static Path sibling(Path file, String suffix) {
		String fileName = file.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String base = dot < 0 ? fileName : fileName.substring(0, dot);
		return file.resolveSibling(base + suffix + ".mdl");
	}

	private static String string(ByteBuffer data, int offset, int length) {
		int end = 0;
		while (end < length && data.get(offset + end) != 0) {
			end++;
		}
		byte[] bytes = new byte[end];
		data.get(offset, bytes);
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}

	private static void check(ByteBuffer data, int offset, int length) throws IOException {
		if (offset < 0 || length < 0 || offset + (long) length > data.capacity()) {
			throw new IOException("Model data out of range");
		}
	}

	private static Bone[] readBones(ByteBuffer data) throws IOException {
		int count = data.getInt(140);
		int index = data.getInt(144);
		check(data, index, count * 112);
		Bone[] bones = new Bone[count];
		for (int i = 0; i < count; i++) {
			int o = index + i * 112;
			float[] value = new float[6];
			float[] scale = new float[6];
			for (int c = 0; c < 6; c++) {
				value[c] = data.getFloat(o + 64 + c * 4);
				scale[c] = data.getFloat(o + 88 + c * 4);
			}
			bones[i] = new Bone(string(data, o, 32), data.getInt(o + 32), value, scale);
		}
		return bones;
	}

	private static Sequence[] readSequences(ByteBuffer data, Path file, Bone[] bones) throws IOException {
		int count = data.getInt(164);
		int index = data.getInt(168);
		check(data, index, count * 176);
		Sequence[] sequences = new Sequence[count];
		ByteBuffer[] groups = new ByteBuffer[Math.max(1, data.getInt(172))];
		groups[0] = data;
		for (int i = 0; i < count; i++) {
			int o = index + i * 176;
			String label = string(data, o, 32);
			float fps = data.getFloat(o + 32);
			boolean looping = (data.getInt(o + 36) & 1) != 0;
			int activity = data.getInt(o + 40);
			int frames = Math.max(1, data.getInt(o + 56));
			int motionType = data.getInt(o + 68);
			int motionBone = data.getInt(o + 72);
			float lx = data.getFloat(o + 76);
			float ly = data.getFloat(o + 80);
			float lz = data.getFloat(o + 84);
			int animIndex = data.getInt(o + 124);
			int group = data.getInt(o + 156);

			ByteBuffer source = data;
			if (group > 0 && group < groups.length) {
				if (groups[group] == null) {
					Path groupFile = sibling(file, String.format(Locale.ROOT, "%02d", group));
					groups[group] = read(groupFile);
					if (groups[group].getInt(0) != MAGIC_SEQUENCES) {
						throw new IOException(groupFile + " is not a GoldSrc sequence group");
					}
				}
				source = groups[group];
			}
			float[][] values = new float[frames][];
			for (int f = 0; f < frames; f++) {
				values[f] = new float[bones.length * 6];
			}
			check(source, animIndex, bones.length * 12);
			for (int b = 0; b < bones.length; b++) {
				int anim = animIndex + b * 12;
				for (int c = 0; c < 6; c++) {
					int offset = Short.toUnsignedInt(source.getShort(anim + c * 2));
					for (int f = 0; f < frames; f++) {
						float raw = offset == 0 ? 0.0F : decode(source, anim + offset, f);
						values[f][b * 6 + c] = bones[b].value[c] + raw * bones[b].scale[c];
					}
				}
				if (b == motionBone) {
					for (float[] frame : values) {
						if ((motionType & MOTION_X) != 0) {
							frame[b * 6] = values[0][b * 6];
						}
						if ((motionType & MOTION_Y) != 0) {
							frame[b * 6 + 1] = values[0][b * 6 + 1];
						}
						if ((motionType & MOTION_Z) != 0) {
							frame[b * 6 + 2] = values[0][b * 6 + 2];
						}
					}
				}
			}
			float distance = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
			sequences[i] = new Sequence(label, fps <= 0.0F ? 10.0F : fps, looping, activity, distance, values);
		}
		return sequences;
	}

	/**
	 * Reads one frame of a run-length encoded channel: runs of a header (count
	 * of stored values, count of frames covered) followed by the stored values.
	 * Frames past the stored values repeat the last one.
	 */
	static float decode(ByteBuffer data, int position, int frame) throws IOException {
		int k = frame;
		for (int guard = 0; guard < 4096; guard++) {
			check(data, position, 2);
			int valid = Byte.toUnsignedInt(data.get(position));
			int total = Byte.toUnsignedInt(data.get(position + 1));
			if (total == 0) {
				return 0.0F;
			}
			if (k < total) {
				int slot = valid > k ? k + 1 : valid;
				check(data, position + slot * 2, 2);
				return data.getShort(position + slot * 2);
			}
			k -= total;
			position += (valid + 1) * 2;
		}
		throw new IOException("Broken animation data");
	}

	private static Texture[] readTextures(ByteBuffer data) throws IOException {
		int count = data.getInt(180);
		int index = data.getInt(184);
		check(data, index, count * 80);
		Texture[] textures = new Texture[count];
		for (int i = 0; i < count; i++) {
			int o = index + i * 80;
			int flags = data.getInt(o + 64);
			int width = data.getInt(o + 68);
			int height = data.getInt(o + 72);
			int pixelsAt = data.getInt(o + 76);
			if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
				throw new IOException("Bad texture size " + width + "x" + height);
			}
			check(data, pixelsAt, width * height + 768);
			int palette = pixelsAt + width * height;
			int[] pixels = new int[width * height];
			for (int p = 0; p < pixels.length; p++) {
				int c = Byte.toUnsignedInt(data.get(pixelsAt + p));
				int r = Byte.toUnsignedInt(data.get(palette + c * 3));
				int g = Byte.toUnsignedInt(data.get(palette + c * 3 + 1));
				int b = Byte.toUnsignedInt(data.get(palette + c * 3 + 2));
				int a = (flags & TEXTURE_MASKED) != 0 && c == 255 ? 0 : 255;
				pixels[p] = a << 24 | r << 16 | g << 8 | b;
			}
			textures[i] = new Texture(string(data, o, 64), flags, width, height, pixels);
		}
		return textures;
	}

	private static short[] readSkins(ByteBuffer data) throws IOException {
		int refs = data.getInt(192);
		int index = data.getInt(200);
		check(data, index, refs * 2);
		short[] skins = new short[refs];
		for (int i = 0; i < refs; i++) {
			skins[i] = data.getShort(index + i * 2);
		}
		return skins;
	}

	private static void readGeometry(ByteBuffer data, short[] skins, int textureCount, List<Triangle> triangles, List<float[]> vertices,
			List<float[]> normals) throws IOException {
		int parts = data.getInt(204);
		int partIndex = data.getInt(208);
		check(data, partIndex, parts * 76);
		for (int p = 0; p < parts; p++) {
			int part = partIndex + p * 76;
			int models = data.getInt(part + 64);
			int modelIndex = data.getInt(part + 72);
			if (models <= 0) {
				continue;
			}
			// The first model of each body part is the default look.
			int model = modelIndex;
			check(data, model, 112);
			int meshes = data.getInt(model + 72);
			int meshIndex = data.getInt(model + 76);
			int vertexCount = data.getInt(model + 80);
			int vertexInfo = data.getInt(model + 84);
			int vertexIndex = data.getInt(model + 88);
			int normalCount = data.getInt(model + 92);
			int normalInfo = data.getInt(model + 96);
			int normalIndex = data.getInt(model + 100);
			check(data, vertexInfo, vertexCount);
			check(data, vertexIndex, vertexCount * 12);
			check(data, normalInfo, normalCount);
			check(data, normalIndex, normalCount * 12);
			int vertexBase = vertices.size();
			int normalBase = normals.size();
			for (int i = 0; i < vertexCount; i++) {
				int o = vertexIndex + i * 12;
				vertices.add(new float[] {data.getFloat(o), data.getFloat(o + 4), data.getFloat(o + 8), Byte.toUnsignedInt(data.get(vertexInfo + i))});
			}
			for (int i = 0; i < normalCount; i++) {
				int o = normalIndex + i * 12;
				normals.add(new float[] {data.getFloat(o), data.getFloat(o + 4), data.getFloat(o + 8), Byte.toUnsignedInt(data.get(normalInfo + i))});
			}
			check(data, meshIndex, meshes * 20);
			for (int m = 0; m < meshes; m++) {
				int mesh = meshIndex + m * 20;
				int commands = data.getInt(mesh + 4);
				int skinRef = data.getInt(mesh + 8);
				int texture = skinRef >= 0 && skinRef < skins.length ? skins[skinRef] : skinRef;
				if (texture < 0 || texture >= textureCount) {
					texture = 0;
				}
				readCommands(data, commands, texture, vertexBase, vertexCount, normalBase, normalCount, triangles);
			}
		}
	}

	/** Triangle strips (positive count) and fans (negative count), ending with a zero count. */
	private static void readCommands(ByteBuffer data, int position, int texture, int vertexBase, int vertexCount, int normalBase, int normalCount,
			List<Triangle> triangles) throws IOException {
		for (int guard = 0; guard < 1 << 20; guard++) {
			check(data, position, 2);
			int count = data.getShort(position);
			position += 2;
			if (count == 0) {
				return;
			}
			boolean fan = count < 0;
			count = Math.abs(count);
			check(data, position, count * 8);
			int[][] corners = new int[count][4];
			for (int i = 0; i < count; i++) {
				for (int c = 0; c < 4; c++) {
					corners[i][c] = data.getShort(position + c * 2);
				}
				if (corners[i][0] < 0 || corners[i][0] >= vertexCount || corners[i][1] < 0 || corners[i][1] >= normalCount) {
					throw new IOException("Triangle refers to a missing vertex");
				}
				position += 8;
			}
			for (int i = 2; i < count; i++) {
				int[] a;
				int[] b;
				int[] c = corners[i];
				if (fan) {
					a = corners[0];
					b = corners[i - 1];
				} else if ((i & 1) == 0) {
					a = corners[i - 2];
					b = corners[i - 1];
				} else {
					// Every other strip triangle swaps two corners to keep the winding.
					a = corners[i - 1];
					b = corners[i - 2];
				}
				int[][] tri = {a, b, c};
				int[] v = new int[3];
				int[] n = new int[3];
				float[] s = new float[3];
				float[] t = new float[3];
				for (int k = 0; k < 3; k++) {
					v[k] = vertexBase + tri[k][0];
					n[k] = normalBase + tri[k][1];
					s[k] = tri[k][2];
					t[k] = tri[k][3];
				}
				triangles.add(new Triangle(texture, v, n, s, t));
			}
		}
		throw new IOException("Broken triangle commands");
	}
}
