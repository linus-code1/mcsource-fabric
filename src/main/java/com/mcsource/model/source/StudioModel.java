package com.mcsource.model.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import com.mcsource.model.RiggedModel;

/**
 * Reads a Source engine model: the {@code .mdl} (skeleton, materials,
 * animations), its {@code .vvd} (weighted vertices) and {@code .dx90.vtx}
 * (triangles), the materials' {@code .vmt} files and their {@code .vtf}
 * textures, all from the player's own game files. Animations in a companion
 * {@code .ani} file and in included models are read too.
 *
 * <p>Written for this mod from public descriptions of the file layouts
 * (studio model versions 44 to 49); no Valve code is used.
 */
public final class StudioModel {
	private static final int MAGIC_MDL = 0x54534449; // "IDST"
	private static final int MAGIC_VVD = 0x56534449; // "IDSV"
	private static final int BONE_SIZE = 216;
	private static final int TEXTURE_SIZE = 64;
	private static final int BODYPART_SIZE = 16;
	private static final int MODEL_SIZE = 148;
	private static final int MESH_SIZE = 116;
	private static final int ANIMDESC_SIZE = 100;
	private static final int SEQDESC_SIZE = 212;
	private static final int VERTEX_SIZE = 48;
	private static final int MOVEMENT_SIZE = 44;

	// Per-bone animation flags.
	private static final int ANIM_RAWPOS = 0x01;
	private static final int ANIM_RAWROT = 0x02;
	private static final int ANIM_ANIMPOS = 0x04;
	private static final int ANIM_ANIMROT = 0x08;
	private static final int ANIM_DELTA = 0x10;
	private static final int ANIM_RAWROT2 = 0x20;

	private static final int SEQ_LOOPING = 0x1;
	private static final int ANIMDESC_ALLZEROS = 0x20;
	private static final int ANIMDESC_FRAMEANIM = 0x40;
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final int MAX_TEXTURE = 512;


	private StudioModel() {
	}

	private record Bone(String name, int parent, float[] pos, float[] quat, float[] rot, float[] posScale, float[] rotScale, Matrix4f poseToBone) {
	}

	/** Bones and sequences of one .mdl file, before they are matched to the model being built. */
	private record Skeleton(Bone[] bones, List<RiggedModel.Sequence> sequences) {
	}

	/**
	 * Reads the model at {@code path} (such as {@code models/zombie/classic.mdl}).
	 * {@code textureFilter}, when not null, keeps only triangles whose
	 * material name contains one of its words (for picking the hands out of a
	 * view model); if no material matches, everything is kept.
	 */
	public static RiggedModel load(GameFiles files, String path, String[] textureFilter) throws IOException {
		byte[] mdlBytes = files.read(path);
		if (mdlBytes == null) {
			throw new IOException("Missing " + path);
		}
		ByteBuffer mdl = buffer(mdlBytes);
		checkHeader(mdl, path);
		String base = path.substring(0, path.length() - 4);
		byte[] vvdBytes = files.read(base + ".vvd");
		byte[] vtxBytes = files.read(base + ".dx90.vtx");
		if (vtxBytes == null) {
			vtxBytes = files.read(base + ".vtx");
		}
		if (vvdBytes == null || vtxBytes == null) {
			throw new IOException("Missing .vvd or .vtx beside " + path);
		}
		ByteBuffer vvd = buffer(vvdBytes);
		ByteBuffer vtx = buffer(vtxBytes);
		if (vvd.getInt(0) != MAGIC_VVD) {
			throw new IOException("Bad .vvd for " + path);
		}

		Skeleton skeleton = readSkeleton(files, mdl, base);
		Bone[] bones = skeleton.bones;
		int boneCount = bones.length;
		String[] names = new String[boneCount];
		int[] parents = new int[boneCount];
		Matrix4f[] inverseBind = new Matrix4f[boneCount];
		float[] bindPosition = new float[boneCount * 3];
		float[] bindRotation = new float[boneCount * 4];
		for (int b = 0; b < boneCount; b++) {
			names[b] = bones[b].name;
			parents[b] = bones[b].parent;
			inverseBind[b] = bones[b].poseToBone;
			System.arraycopy(bones[b].pos, 0, bindPosition, b * 3, 3);
			System.arraycopy(bones[b].quat, 0, bindRotation, b * 4, 4);
		}

		// Sequences from included models, matched to this model's bones by name.
		List<RiggedModel.Sequence> sequences = new ArrayList<>(skeleton.sequences);
		int includeCount = mdl.getInt(336);
		int includeIndex = mdl.getInt(340);
		for (int i = 0; i < includeCount; i++) {
			int at = includeIndex + i * 8;
			String includePath = string(mdl, at + mdl.getInt(at + 4));
			try {
				byte[] includeBytes = files.read(includePath);
				if (includeBytes == null && !includePath.startsWith("models/")) {
					includePath = "models/" + includePath;
					includeBytes = files.read(includePath);
				}
				if (includeBytes == null) {
					continue;
				}
				ByteBuffer include = buffer(includeBytes);
				checkHeader(include, includePath);
				Skeleton included = readSkeleton(files, include, includePath.substring(0, includePath.length() - 4));
				for (RiggedModel.Sequence sequence : included.sequences) {
					sequences.add(remap(sequence, included.bones, bones));
				}
			} catch (IOException e) {
				// An include that can't be read only loses its animations.
			}
		}

		// Weighted vertices of the top level of detail.
		Vertex[] vertices = readVertices(vvd);
		int vertexCount = vertices.length;
		float[] positions = new float[vertexCount * 3];
		float[] normals = new float[vertexCount * 3];
		int[] vertexBones = new int[vertexCount * 3];
		float[] vertexWeights = new float[vertexCount * 3];
		float[] uv = new float[vertexCount * 2];
		for (int i = 0; i < vertexCount; i++) {
			Vertex v = vertices[i];
			System.arraycopy(v.pos, 0, positions, i * 3, 3);
			System.arraycopy(v.normal, 0, normals, i * 3, 3);
			for (int k = 0; k < 3; k++) {
				vertexBones[i * 3 + k] = Math.min(Math.max(v.bones[k], 0), boneCount - 1);
				vertexWeights[i * 3 + k] = k < v.boneCount ? v.weights[k] : 0.0F;
			}
			uv[i * 2] = v.u;
			uv[i * 2 + 1] = v.v;
		}

		// Materials and textures.
		String[] materials = readMaterialNames(mdl);
		short[] skins = readSkins(mdl);
		String[] searchPaths = readSearchPaths(mdl);
		RiggedModel.Texture[] textures = new RiggedModel.Texture[materials.length];
		for (int i = 0; i < materials.length; i++) {
			textures[i] = loadMaterial(files, materials[i], searchPaths);
		}
		boolean[] keep = new boolean[materials.length];
		boolean anyKept = false;
		for (int i = 0; i < materials.length; i++) {
			if (textureFilter == null) {
				keep[i] = true;
			} else {
				String lower = materials[i].toLowerCase(Locale.ROOT);
				for (String word : textureFilter) {
					if (lower.contains(word)) {
						keep[i] = true;
					}
				}
			}
			anyKept |= keep[i];
		}
		if (!anyKept) {
			java.util.Arrays.fill(keep, true);
		}

		// Triangles: the .mdl lists body parts, models and meshes; the .vtx
		// holds each mesh's triangles in the same order.
		List<int[]> triangles = new ArrayList<>();
		List<Integer> triangleTextures = new ArrayList<>();
		int partCount = mdl.getInt(232);
		int partIndex = mdl.getInt(236);
		int vtxPartCount = vtx.getInt(28);
		int vtxPartIndex = vtx.getInt(32);
		for (int p = 0; p < Math.min(partCount, vtxPartCount); p++) {
			int part = partIndex + p * BODYPART_SIZE;
			int modelCount = mdl.getInt(part + 4);
			int modelIndex = part + mdl.getInt(part + 12);
			int vtxPart = vtxPartIndex + p * 8;
			int vtxModels = vtx.getInt(vtxPart);
			int vtxModelIndex = vtxPart + vtx.getInt(vtxPart + 4);
			if (modelCount <= 0 || vtxModels <= 0) {
				continue;
			}
			// The first model of each body part is the default look.
			int model = modelIndex;
			int meshCount = mdl.getInt(model + 72);
			int meshIndex = model + mdl.getInt(model + 76);
			int modelVertexStart = mdl.getInt(model + 84) / VERTEX_SIZE;
			int vtxModel = vtxModelIndex;
			int lodIndex = vtxModel + vtx.getInt(vtxModel + 4);
			int vtxMeshCount = vtx.getInt(lodIndex);
			int vtxMeshIndex = lodIndex + vtx.getInt(lodIndex + 4);
			for (int m = 0; m < Math.min(meshCount, vtxMeshCount); m++) {
				int mesh = meshIndex + m * MESH_SIZE;
				int material = mdl.getInt(mesh);
				int texture = material >= 0 && material < skins.length ? skins[material] : material;
				if (texture < 0 || texture >= textures.length) {
					texture = 0;
				}
				if (textures.length > 0 && !keep[texture]) {
					continue;
				}
				int meshVertexStart = modelVertexStart + mdl.getInt(mesh + 12);
				int vtxMesh = vtxMeshIndex + m * 9;
				readStripGroups(vtx, vtxMesh, meshVertexStart, vertexCount, texture, triangles, triangleTextures);
			}
		}

		int triangleCount = triangles.size();
		int[] cornerVertex = new int[triangleCount * 3];
		float[] cornerUv = new float[triangleCount * 6];
		int[] triangleTexture = new int[triangleCount];
		for (int t = 0; t < triangleCount; t++) {
			int[] tri = triangles.get(t);
			triangleTexture[t] = triangleTextures.get(t);
			for (int k = 0; k < 3; k++) {
				cornerVertex[t * 3 + k] = tri[k];
				cornerUv[t * 6 + k * 2] = uv[tri[k] * 2];
				cornerUv[t * 6 + k * 2 + 1] = uv[tri[k] * 2 + 1];
			}
		}
		if (textures.length == 0) {
			textures = new RiggedModel.Texture[] {missingTexture("none")};
		}
		return new RiggedModel(string(mdl, 12), names, parents, inverseBind, bindPosition, bindRotation, positions, vertexBones, vertexWeights,
				normals, vertexBones, vertexWeights, cornerVertex, cornerVertex.clone(), cornerUv, triangleTexture, textures,
				sequences.toArray(new RiggedModel.Sequence[0]));
	}

	private static ByteBuffer buffer(byte[] bytes) {
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void checkHeader(ByteBuffer mdl, String path) throws IOException {
		if (mdl.capacity() < 408 || mdl.getInt(0) != MAGIC_MDL) {
			throw new IOException(path + " is not a model");
		}
		int version = mdl.getInt(4);
		if (version < 44 || version > 49) {
			throw new IOException(path + " is model version " + version + "; versions 44 to 49 are supported");
		}
	}

	private static String string(ByteBuffer data, int offset) {
		if (offset <= 0 || offset >= data.capacity()) {
			return "";
		}
		int end = offset;
		while (end < data.capacity() && data.get(end) != 0) {
			end++;
		}
		byte[] bytes = new byte[end - offset];
		data.get(offset, bytes);
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}

	private static float[] floats(ByteBuffer data, int offset, int count) {
		float[] out = new float[count];
		for (int i = 0; i < count; i++) {
			out[i] = data.getFloat(offset + i * 4);
		}
		return out;
	}

	// ---------------------------------------------------------------- skeleton and animation

	private static Skeleton readSkeleton(GameFiles files, ByteBuffer mdl, String base) throws IOException {
		int boneCount = mdl.getInt(156);
		int boneIndex = mdl.getInt(160);
		if (boneCount <= 0 || boneIndex + (long) boneCount * BONE_SIZE > mdl.capacity()) {
			throw new IOException("Bad bone table");
		}
		Bone[] bones = new Bone[boneCount];
		for (int b = 0; b < boneCount; b++) {
			int at = boneIndex + b * BONE_SIZE;
			Matrix4f poseToBone = new Matrix4f();
			for (int row = 0; row < 3; row++) {
				int r = at + 96 + row * 16;
				poseToBone.setRow(row, new Vector4f(mdl.getFloat(r), mdl.getFloat(r + 4), mdl.getFloat(r + 8), mdl.getFloat(r + 12)));
			}
			bones[b] = new Bone(string(mdl, at + mdl.getInt(at)), mdl.getInt(at + 4), floats(mdl, at + 32, 3), floats(mdl, at + 44, 4),
					floats(mdl, at + 60, 3), floats(mdl, at + 72, 3), floats(mdl, at + 84, 3), poseToBone);
		}

		// Animation data may live in a companion .ani file, in blocks.
		ByteBuffer ani = null;
		int blockCount = mdl.getInt(352);
		int blockIndex = mdl.getInt(356);
		if (blockCount > 0) {
			String aniName = string(mdl, mdl.getInt(348));
			byte[] aniBytes = files.read(aniName.isEmpty() ? base + ".ani" : aniName);
			if (aniBytes != null) {
				ani = buffer(aniBytes);
			}
		}

		int animCount = mdl.getInt(180);
		int animIndex = mdl.getInt(184);
		int seqCount = mdl.getInt(188);
		int seqIndex = mdl.getInt(192);
		List<RiggedModel.Sequence> sequences = new ArrayList<>();
		Map<Integer, float[][]> decoded = new HashMap<>();
		for (int s = 0; s < seqCount; s++) {
			int seq = seqIndex + s * SEQDESC_SIZE;
			String label = string(mdl, seq + mdl.getInt(seq + 4));
			String activity = string(mdl, seq + mdl.getInt(seq + 8));
			boolean looping = (mdl.getInt(seq + 12) & SEQ_LOOPING) != 0;
			int anim = mdl.getShort(seq + mdl.getInt(seq + 60));
			if (anim < 0 || anim >= animCount) {
				continue;
			}
			int desc = animIndex + anim * ANIMDESC_SIZE;
			float fps = mdl.getFloat(desc + 8);
			try {
				float[][] frames = decoded.get(anim);
				if (frames == null) {
					frames = readAnimation(mdl, ani, blockIndex, blockCount, desc, bones);
					decoded.put(anim, frames);
				}
				sequences.add(new RiggedModel.Sequence(label, activity, fps > 0 ? fps : 30.0F, looping, movement(mdl, desc), frames));
			} catch (RuntimeException | IOException e) {
				// A sequence that can't be decoded is left out.
				LOGGER.warn("Left out animation {} of {}: {}", label, base, e.toString());
			}
		}
		return new Skeleton(bones, sequences);
	}

	/** How far an animation moves in one cycle, from its movement records. */
	private static float movement(ByteBuffer mdl, int desc) {
		int count = mdl.getInt(desc + 20);
		if (count <= 0) {
			return 0.0F;
		}
		int last = desc + mdl.getInt(desc + 24) + (count - 1) * MOVEMENT_SIZE;
		float x = mdl.getFloat(last + 32);
		float y = mdl.getFloat(last + 36);
		float z = mdl.getFloat(last + 40);
		return (float) Math.sqrt(x * x + y * y + z * z);
	}

	private static float[][] readAnimation(ByteBuffer mdl, ByteBuffer ani, int blockIndex, int blockCount, int desc, Bone[] bones)
			throws IOException {
		int frameCount = Math.max(1, mdl.getInt(desc + 16));
		int flags = mdl.getInt(desc + 12);
		if ((flags & ANIMDESC_ALLZEROS) != 0) {
			// No data: every bone stays in its bind pose.
			float[][] frames = new float[frameCount][];
			for (int f = 0; f < frameCount; f++) {
				frames[f] = decodeFrame(mdl, -1, 0, bones);
			}
			return frames;
		}
		if ((flags & ANIMDESC_FRAMEANIM) != 0) {
			throw new IOException("frame-based animation data (newer games) is not supported");
		}
		int block = mdl.getInt(desc + 52);
		int index = mdl.getInt(desc + 56);
		int sectionIndex = mdl.getInt(desc + 80);
		int sectionFrames = mdl.getInt(desc + 84);
		float[][] frames = new float[frameCount][];
		for (int f = 0; f < frameCount; f++) {
			int localFrame = f;
			int dataBlock = block;
			int dataIndex = index;
			if (sectionFrames > 0) {
				int section = f / sectionFrames;
				int sectionAt = desc + sectionIndex + section * 8;
				dataBlock = mdl.getInt(sectionAt);
				dataIndex = mdl.getInt(sectionAt + 4);
				localFrame = f - section * sectionFrames;
			}
			ByteBuffer data;
			int start;
			if (dataBlock == 0) {
				data = mdl;
				start = desc + dataIndex;
			} else {
				if (ani == null || dataBlock >= blockCount) {
					throw new IOException("Animation data not found");
				}
				data = ani;
				start = mdl.getInt(blockIndex + dataBlock * 8) + dataIndex;
			}
			frames[f] = decodeFrame(data, start, localFrame, bones);
		}
		return frames;
	}

	/** One frame of every bone: position and rotation, starting from the bind pose. */
	private static float[] decodeFrame(ByteBuffer data, int start, int frame, Bone[] bones) throws IOException {
		float[] out = new float[bones.length * 7];
		for (int b = 0; b < bones.length; b++) {
			System.arraycopy(bones[b].pos, 0, out, b * 7, 3);
			System.arraycopy(bones[b].quat, 0, out, b * 7 + 3, 4);
		}
		if (start < 0) {
			return out;
		}
		Quaternionf q = new Quaternionf();
		int at = start;
		for (int guard = 0; guard < bones.length + 1; guard++) {
			if (at < 0 || at + 4 > data.capacity()) {
				throw new IOException("Animation out of range");
			}
			int bone = Byte.toUnsignedInt(data.get(at));
			int flags = Byte.toUnsignedInt(data.get(at + 1));
			int next = data.getShort(at + 2);
			if (bone < bones.length) {
				Bone info = bones[bone];
				boolean delta = (flags & ANIM_DELTA) != 0;
				int p = at + 4;
				if ((flags & ANIM_RAWROT) != 0) {
					quaternion48(data, p, q);
					setQuat(out, bone, q);
					p += 6;
				}
				if ((flags & ANIM_RAWROT2) != 0) {
					quaternion64(data, p, q);
					setQuat(out, bone, q);
					p += 8;
				}
				if ((flags & ANIM_ANIMROT) != 0) {
					float[] angle = new float[3];
					for (int c = 0; c < 3; c++) {
						int offset = data.getShort(p + c * 2);
						float value = offset == 0 ? 0.0F : rle(data, p + offset, frame) * info.rotScale[c];
						angle[c] = value + (delta ? 0.0F : info.rot[c]);
					}
					q.rotationZYX(angle[2], angle[1], angle[0]);
					setQuat(out, bone, q);
					p += 6;
				}
				if ((flags & ANIM_RAWPOS) != 0) {
					for (int c = 0; c < 3; c++) {
						out[bone * 7 + c] = Float.float16ToFloat(data.getShort(p + c * 2));
					}
					p += 6;
				}
				if ((flags & ANIM_ANIMPOS) != 0) {
					for (int c = 0; c < 3; c++) {
						int offset = data.getShort(p + c * 2);
						float value = offset == 0 ? 0.0F : rle(data, p + offset, frame) * info.posScale[c];
						out[bone * 7 + c] = value + (delta ? 0.0F : info.pos[c]);
					}
				}
			}
			if (next == 0) {
				break;
			}
			at += next;
		}
		return out;
	}

	private static void setQuat(float[] out, int bone, Quaternionf q) {
		out[bone * 7 + 3] = q.x;
		out[bone * 7 + 4] = q.y;
		out[bone * 7 + 5] = q.z;
		out[bone * 7 + 6] = q.w;
	}

	/** A run-length encoded channel value at a frame; past the stored values the last one repeats. */
	private static float rle(ByteBuffer data, int position, int frame) throws IOException {
		int k = frame;
		for (int guard = 0; guard < 4096; guard++) {
			if (position < 0 || position + 2 > data.capacity()) {
				throw new IOException("Animation value out of range");
			}
			int valid = Byte.toUnsignedInt(data.get(position));
			int total = Byte.toUnsignedInt(data.get(position + 1));
			if (total == 0) {
				return 0.0F;
			}
			if (k < total) {
				int slot = valid > k ? k + 1 : valid;
				return data.getShort(position + slot * 2);
			}
			k -= total;
			position += (valid + 1) * 2;
		}
		throw new IOException("Broken animation values");
	}

	/** A rotation packed in 48 bits: x and y in 16 bits, z in 15 bits, and the sign of w. */
	private static void quaternion48(ByteBuffer data, int at, Quaternionf out) {
		int x = Short.toUnsignedInt(data.getShort(at));
		int y = Short.toUnsignedInt(data.getShort(at + 2));
		int zw = Short.toUnsignedInt(data.getShort(at + 4));
		float fx = (x - 32768) / 32768.0F;
		float fy = (y - 32768) / 32768.0F;
		float fz = ((zw & 0x7FFF) - 16384) / 16384.0F;
		float w = (float) Math.sqrt(Math.max(0.0, 1.0 - fx * fx - fy * fy - fz * fz));
		out.set(fx, fy, fz, (zw & 0x8000) != 0 ? -w : w);
	}

	/** A rotation packed in 64 bits: x, y and z in 21 bits each, and the sign of w. */
	private static void quaternion64(ByteBuffer data, int at, Quaternionf out) {
		long bits = data.getLong(at);
		float fx = ((bits & 0x1FFFFF) - 1048576) / 1048576.5F;
		float fy = ((bits >>> 21 & 0x1FFFFF) - 1048576) / 1048576.5F;
		float fz = ((bits >>> 42 & 0x1FFFFF) - 1048576) / 1048576.5F;
		float w = (float) Math.sqrt(Math.max(0.0, 1.0 - fx * fx - fy * fy - fz * fz));
		out.set(fx, fy, fz, (bits >>> 63) != 0 ? -w : w);
	}

	/** An included model's animation, with bones matched to this model's by name. */
	private static RiggedModel.Sequence remap(RiggedModel.Sequence sequence, Bone[] from, Bone[] to) {
		Map<String, Integer> byName = new HashMap<>();
		for (int i = 0; i < from.length; i++) {
			byName.put(from[i].name.toLowerCase(Locale.ROOT), i);
		}
		float[][] frames = new float[sequence.frameCount()][];
		for (int f = 0; f < frames.length; f++) {
			float[] in = sequence.frames()[f];
			float[] out = new float[to.length * 7];
			for (int b = 0; b < to.length; b++) {
				Integer source = byName.get(to[b].name.toLowerCase(Locale.ROOT));
				if (source != null) {
					System.arraycopy(in, source * 7, out, b * 7, 7);
				} else {
					System.arraycopy(to[b].pos, 0, out, b * 7, 3);
					System.arraycopy(to[b].quat, 0, out, b * 7 + 3, 4);
				}
			}
			frames[f] = out;
		}
		return new RiggedModel.Sequence(sequence.label(), sequence.activity(), sequence.fps(), sequence.looping(), sequence.distance(), frames);
	}

	// ---------------------------------------------------------------- vertices and triangles

	private record Vertex(float[] pos, float[] normal, float u, float v, int[] bones, float[] weights, int boneCount) {
	}

	private static Vertex[] readVertices(ByteBuffer vvd) throws IOException {
		int lod0 = vvd.getInt(16);
		int fixupCount = vvd.getInt(48);
		int fixupStart = vvd.getInt(52);
		int dataStart = vvd.getInt(56);
		int total = (vvd.capacity() - dataStart) / VERTEX_SIZE;
		List<Integer> order = new ArrayList<>();
		if (fixupCount > 0) {
			for (int i = 0; i < fixupCount; i++) {
				int at = fixupStart + i * 12;
				if (vvd.getInt(at) >= 0) {
					int source = vvd.getInt(at + 4);
					int count = vvd.getInt(at + 8);
					for (int k = 0; k < count; k++) {
						order.add(source + k);
					}
				}
			}
		} else {
			for (int i = 0; i < lod0; i++) {
				order.add(i);
			}
		}
		Vertex[] out = new Vertex[order.size()];
		for (int i = 0; i < out.length; i++) {
			int index = order.get(i);
			if (index < 0 || index >= total) {
				throw new IOException("Vertex out of range");
			}
			int at = dataStart + index * VERTEX_SIZE;
			float[] weights = floats(vvd, at, 3);
			int[] bones = {vvd.get(at + 12), vvd.get(at + 13), vvd.get(at + 14)};
			int count = Math.max(1, Math.min(3, Byte.toUnsignedInt(vvd.get(at + 15))));
			out[i] = new Vertex(floats(vvd, at + 16, 3), floats(vvd, at + 28, 3), vvd.getFloat(at + 40), vvd.getFloat(at + 44), bones, weights,
					count);
		}
		return out;
	}

	/** A mesh's triangles. Strip groups list vertices and a triangle index list into them. */
	private static void readStripGroups(ByteBuffer vtx, int mesh, int vertexStart, int vertexCount, int texture, List<int[]> triangles,
			List<Integer> textures) throws IOException {
		int groupCount = vtx.getInt(mesh);
		int groupIndex = mesh + vtx.getInt(mesh + 4);
		// Newer files add two fields to each strip group header.
		int groupSize = fits(vtx, groupIndex, groupCount, 25, vertexStart, vertexCount) ? 25 : 33;
		for (int g = 0; g < groupCount; g++) {
			int group = groupIndex + g * groupSize;
			int numVerts = vtx.getInt(group);
			int vertOffset = group + vtx.getInt(group + 4);
			int numIndices = vtx.getInt(group + 8);
			int indexOffset = group + vtx.getInt(group + 12);
			if (vertOffset < 0 || vertOffset + (long) numVerts * 9 > vtx.capacity() || indexOffset < 0
					|| indexOffset + (long) numIndices * 2 > vtx.capacity()) {
				throw new IOException("Triangle data out of range");
			}
			for (int i = 0; i + 2 < numIndices; i += 3) {
				int[] tri = new int[3];
				for (int k = 0; k < 3; k++) {
					int local = Short.toUnsignedInt(vtx.getShort(indexOffset + (i + k) * 2));
					if (local >= numVerts) {
						throw new IOException("Index out of range");
					}
					int original = Short.toUnsignedInt(vtx.getShort(vertOffset + local * 9 + 4));
					tri[k] = vertexStart + original;
					if (tri[k] >= vertexCount) {
						throw new IOException("Vertex out of range");
					}
				}
				triangles.add(tri);
				textures.add(texture);
			}
		}
	}

	/** Whether strip group headers of this size give sensible counts and offsets. */
	private static boolean fits(ByteBuffer vtx, int groupIndex, int groupCount, int size, int vertexStart, int vertexCount) {
		for (int g = 0; g < groupCount; g++) {
			int group = groupIndex + g * size;
			if (group + 25 > vtx.capacity()) {
				return false;
			}
			int numVerts = vtx.getInt(group);
			int vertOffset = group + vtx.getInt(group + 4);
			int numIndices = vtx.getInt(group + 8);
			int indexOffset = group + vtx.getInt(group + 12);
			if (numVerts < 0 || numIndices < 0 || numIndices % 3 != 0 || vertOffset < 0 || vertOffset + (long) numVerts * 9 > vtx.capacity()
					|| indexOffset < 0 || indexOffset + (long) numIndices * 2 > vtx.capacity()) {
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- materials

	private static String[] readMaterialNames(ByteBuffer mdl) {
		int count = mdl.getInt(204);
		int index = mdl.getInt(208);
		String[] names = new String[Math.max(0, count)];
		for (int i = 0; i < names.length; i++) {
			int at = index + i * TEXTURE_SIZE;
			names[i] = string(mdl, at + mdl.getInt(at));
		}
		return names;
	}

	private static short[] readSkins(ByteBuffer mdl) {
		int refs = mdl.getInt(220);
		int index = mdl.getInt(228);
		short[] skins = new short[Math.max(0, refs)];
		for (int i = 0; i < skins.length; i++) {
			skins[i] = mdl.getShort(index + i * 2);
		}
		return skins;
	}

	private static String[] readSearchPaths(ByteBuffer mdl) {
		int count = mdl.getInt(212);
		int index = mdl.getInt(216);
		String[] paths = new String[Math.max(0, count)];
		for (int i = 0; i < paths.length; i++) {
			paths[i] = string(mdl, mdl.getInt(index + i * 4)).replace('\\', '/');
		}
		return paths;
	}

	private static RiggedModel.Texture loadMaterial(GameFiles files, String name, String[] searchPaths) {
		String clean = name.replace('\\', '/');
		for (String folder : searchPaths.length == 0 ? new String[] {""} : searchPaths) {
			String prefix = folder.isEmpty() || folder.endsWith("/") ? folder : folder + "/";
			try {
				RiggedModel.Texture texture = Materials.load(files, prefix + clean, MAX_TEXTURE);
				if (texture != null) {
					return new RiggedModel.Texture(clean, texture.width(), texture.height(), texture.pixels(), texture.masked());
				}
			} catch (IOException e) {
				// Try the next folder.
			}
		}
		return missingTexture(clean);
	}

	/** Grey stands in for a texture that can't be found. */
	private static RiggedModel.Texture missingTexture(String name) {
		int[] pixels = new int[16];
		java.util.Arrays.fill(pixels, 0xFF808080);
		return new RiggedModel.Texture(name, 4, 4, pixels, false);
	}
}
