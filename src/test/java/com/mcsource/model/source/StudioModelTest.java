package com.mcsource.model.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcsource.model.RiggedModel;

/**
 * Reads a tiny hand-built Source model (one bone, one triangle, a texture
 * and a two-frame animation) packed in a VPK, the way models are read from
 * the player's game files.
 */
class StudioModelTest {
	@TempDir
	Path dir;

	@Test
	void readsModelFromPack() throws Exception {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("models/test/thing.mdl", mdl());
		files.put("models/test/thing.vvd", vvd());
		files.put("models/test/thing.dx90.vtx", vtx());
		files.put("materials/models/test/skin.vmt",
				"\"VertexLitGeneric\"\n{\n\t\"$basetexture\" \"models/test/skin_color\"\n}\n".getBytes(StandardCharsets.US_ASCII));
		files.put("materials/models/test/skin_color.vtf", vtf());
		Path pack = dir.resolve("test_dir.vpk");
		Files.write(pack, vpk(files));

		Vpk vpk = new Vpk(pack);
		assertEquals(5, vpk.size());
		RiggedModel model = StudioModel.load(vpk, "models/test/thing.mdl", null);

		assertEquals(1, model.boneCount());
		assertEquals("root", model.boneNames[0]);
		assertEquals(1, model.triangleCount());
		assertEquals(10.0F, model.positions[3], 1e-6F);
		assertEquals(1.0F, model.cornerUv[2], 1e-6F);
		assertEquals(1, model.textures.length);
		assertEquals(4, model.textures[0].width());
		assertEquals(0xFF336699, model.textures[0].pixels()[0]);

		RiggedModel.Sequence idle = model.find("idle");
		assertNotNull(idle);
		assertEquals(2, idle.frameCount());
		assertEquals(10.0F, idle.fps(), 1e-6F);
		// The second frame turns the bone a quarter turn about z, so x becomes y.
		RiggedModel.Pose pose = new RiggedModel.Pose(model);
		model.pose(idle, 1.0F, pose);
		Matrix4f[] bones = new Matrix4f[1];
		model.skinning(pose, bones);
		Vector3f moved = bones[0].transformPosition(new Vector3f(10, 0, 0));
		assertEquals(0.0F, moved.x, 1e-3F);
		assertEquals(10.0F, moved.y, 1e-3F);
	}

	private static ByteBuffer le(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void putString(ByteBuffer b, int at, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
		b.put(at, bytes);
	}

	private static byte[] mdl() {
		ByteBuffer b = le(1400);
		b.putInt(0, 0x54534449).putInt(4, 48);
		putString(b, 12, "test/thing.mdl");
		int bone = 408;
		int texture = 624;
		int cd = 688;
		int skins = 692;
		int part = 696;
		int model = 712;
		int mesh = 860;
		int anim = 976;
		int animData = 1076;
		int seq = 1092;
		int animIndexes = 1304;
		int strings = 1308;
		b.putInt(156, 1).putInt(160, bone);
		b.putInt(180, 1).putInt(184, anim);
		b.putInt(188, 1).putInt(192, seq);
		b.putInt(204, 1).putInt(208, texture);
		b.putInt(212, 1).putInt(216, cd);
		b.putInt(220, 1).putInt(224, 1).putInt(228, skins);
		b.putInt(232, 1).putInt(236, part);

		// Strings.
		int root = strings;
		putString(b, root, "root");
		int skin = root + 5;
		putString(b, skin, "skin");
		int folder = skin + 5;
		putString(b, folder, "models\\test\\");
		int label = folder + 13;
		putString(b, label, "idle");
		int activity = label + 5;
		putString(b, activity, "ACT_IDLE");

		b.putInt(bone, root - bone).putInt(bone + 4, -1);
		b.putFloat(bone + 56, 1.0F); // quaternion w
		b.putFloat(bone + 84 + 8, (float) (Math.PI / 2) / 16384.0F); // z rotation scale
		b.putFloat(bone + 96, 1.0F).putFloat(bone + 96 + 20, 1.0F).putFloat(bone + 96 + 40, 1.0F); // pose to bone: identity

		b.putInt(texture, skin - texture);
		b.putInt(cd, folder);
		b.putShort(skins, (short) 0);

		b.putInt(part + 4, 1).putInt(part + 8, 1).putInt(part + 12, model - part);
		b.putInt(model + 72, 1).putInt(model + 76, mesh - model).putInt(model + 80, 3).putInt(model + 84, 0);
		b.putInt(mesh, 0).putInt(mesh + 8, 3).putInt(mesh + 12, 0);

		b.putFloat(anim + 8, 10.0F).putInt(anim + 16, 2).putInt(anim + 56, animData - anim);
		// Bone 0, animated rotation, last bone.
		b.put(animData, (byte) 0).put(animData + 1, (byte) 0x08).putShort(animData + 2, (short) 0);
		int values = animData + 4;
		b.putShort(values + 4, (short) 6); // z channel's values follow the three offsets
		b.put(values + 6, (byte) 2).put(values + 7, (byte) 2).putShort(values + 8, (short) 0).putShort(values + 10, (short) 16384);

		b.putInt(seq + 4, label - seq).putInt(seq + 8, activity - seq).putInt(seq + 12, 1).putInt(seq + 60, animIndexes - seq);
		b.putShort(animIndexes, (short) 0);
		return b.array();
	}

	private static byte[] vvd() {
		ByteBuffer b = le(64 + 3 * 48);
		b.putInt(0, 0x56534449).putInt(4, 4).putInt(12, 1).putInt(16, 3).putInt(56, 64);
		float[][] pos = {{0, 0, 0}, {10, 0, 0}, {0, 0, 10}};
		float[][] uv = {{0, 0}, {1, 0}, {0, 1}};
		for (int i = 0; i < 3; i++) {
			int at = 64 + i * 48;
			b.putFloat(at, 1.0F).put(at + 15, (byte) 1);
			b.putFloat(at + 16, pos[i][0]).putFloat(at + 20, pos[i][1]).putFloat(at + 24, pos[i][2]);
			b.putFloat(at + 36, 1.0F);
			b.putFloat(at + 40, uv[i][0]).putFloat(at + 44, uv[i][1]);
		}
		return b.array();
	}

	private static byte[] vtx() {
		ByteBuffer b = le(131);
		b.putInt(0, 7).putInt(20, 1).putInt(28, 1).putInt(32, 36);
		b.putInt(36, 1).putInt(40, 8); // body part -> model at 44
		b.putInt(44, 1).putInt(48, 8); // model -> lod at 52
		b.putInt(52, 1).putInt(56, 12); // lod -> mesh at 64
		b.putInt(64, 1).putInt(68, 9); // mesh -> strip group at 73
		b.putInt(73, 3).putInt(77, 25).putInt(81, 3).putInt(85, 52); // vertices at 98, indices at 125
		for (int i = 0; i < 3; i++) {
			b.putShort(98 + i * 9 + 4, (short) i);
			b.putShort(125 + i * 2, (short) i);
		}
		return b.array();
	}

	private static byte[] vtf() {
		ByteBuffer b = le(80 + 64);
		b.putInt(0, 0x00465456).putInt(4, 7).putInt(8, 2).putInt(12, 80);
		b.putShort(16, (short) 4).putShort(18, (short) 4).putShort(24, (short) 1);
		b.putInt(52, 12); // BGRA8888
		b.put(56, (byte) 1).putInt(57, -1).putShort(63, (short) 1);
		for (int i = 0; i < 16; i++) {
			b.put(80 + i * 4, (byte) 0x99).put(81 + i * 4, (byte) 0x66).put(82 + i * 4, (byte) 0x33).put(83 + i * 4, (byte) 0x80);
		}
		return b.array();
	}

	/** A version 1 VPK holding every file in the directory file itself. */
	private static byte[] vpk(Map<String, byte[]> files) {
		ByteArrayOutputStream tree = new ByteArrayOutputStream();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			String path = file.getKey();
			int slash = path.lastIndexOf('/');
			int dot = path.lastIndexOf('.');
			// One extension and folder per file keeps this simple; the format allows that.
			writeString(tree, path.substring(dot + 1));
			writeString(tree, path.substring(0, slash));
			writeString(tree, path.substring(slash + 1, dot));
			ByteBuffer entry = le(18);
			entry.putInt(0, 0).putShort(4, (short) 0).putShort(6, (short) 0x7FFF).putInt(8, data.size()).putInt(12, file.getValue().length)
					.putShort(16, (short) 0xFFFF);
			tree.writeBytes(entry.array());
			tree.write(0); // end of files in folder
			tree.write(0); // end of folders
			data.writeBytes(file.getValue());
		}
		tree.write(0); // end of extensions
		ByteBuffer header = le(12);
		header.putInt(0, 0x55AA1234).putInt(4, 1).putInt(8, tree.size());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header.array());
		out.writeBytes(tree.toByteArray());
		out.writeBytes(data.toByteArray());
		return out.toByteArray();
	}

	private static void writeString(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
		out.write(0);
	}
}
