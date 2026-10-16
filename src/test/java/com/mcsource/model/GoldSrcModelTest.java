package com.mcsource.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads a tiny hand-made model laid out the way Half-Life 1 model files are. */
class GoldSrcModelTest {
	@TempDir
	Path dir;

	@Test
	void readsBonesMeshTexturesAndAnimation() throws Exception {
		Path file = dir.resolve("tiny.mdl");
		Files.write(file, tinyModel());
		GoldSrcModel model = GoldSrcModel.load(file);

		assertEquals(2, model.bones.length);
		assertEquals("pelvis", model.bones[0].name());
		assertEquals(0, model.bones[1].parent());

		// One strip of four corners makes two triangles, the second wound to match the first.
		assertEquals(2, model.triangles.size());
		assertArrayEquals(new int[] {0, 1, 2}, model.triangles.get(0).vertex());
		assertArrayEquals(new int[] {2, 1, 3}, model.triangles.get(1).vertex());
		assertEquals(1.0F, model.triangles.get(1).s()[2]);

		GoldSrcModel.Texture texture = model.textures[0];
		assertEquals(2, texture.width());
		assertEquals(0xFF0A141E, texture.pixels()[0]);
		// Palette index 255 is see-through in a masked texture.
		assertEquals(0, texture.pixels()[3] >>> 24);

		GoldSrcModel.Sequence walk = model.find("idle", "walk");
		assertNotNull(walk);
		assertEquals(3, walk.frameCount());
		// Run-length data: two stored values covering three frames, the last repeated.
		assertEquals(0.1F, walk.frames()[0][11], 1.0E-6F);
		assertEquals(0.2F, walk.frames()[1][11], 1.0E-6F);
		assertEquals(0.2F, walk.frames()[2][11], 1.0E-6F);

		GoldSrcModel.Pose pose = new GoldSrcModel.Pose(model);
		model.pose(walk, 1.0F, pose);
		Matrix4f[] bones = new Matrix4f[2];
		model.matrices(pose, bones);
		Vector3f p = bones[1].transformPosition(new Vector3f(1, 0, 0));
		assertEquals(5.0F + (float) Math.cos(0.2), p.x, 1.0E-5F);
		assertEquals((float) Math.sin(0.2), p.y, 1.0E-5F);
		assertEquals(10.0F, p.z, 1.0E-5F);
	}

	private static byte[] tinyModel() {
		ByteBuffer b = ByteBuffer.allocate(1856).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0, 0x54534449);
		b.putInt(4, 10);
		name(b, 8, "tiny.mdl");
		b.putInt(140, 2).putInt(144, 256);
		b.putInt(164, 1).putInt(168, 480).putInt(172, 1);
		b.putInt(180, 1).putInt(184, 688).putInt(188, 768);
		b.putInt(192, 1).putInt(196, 1).putInt(200, 1540);
		b.putInt(204, 1).putInt(208, 1544);

		bone(b, 256, "pelvis", -1, new float[] {0, 0, 10, 0, 0, 0}, new float[] {1, 1, 1, 1, 1, 1});
		bone(b, 368, "spine", 0, new float[] {5, 0, 0, 0, 0, 0}, new float[] {1, 1, 1, 0.001F, 0.001F, 0.001F});

		name(b, 480, "walk");
		b.putFloat(480 + 32, 10.0F);
		b.putInt(480 + 36, 1);
		b.putInt(480 + 56, 3);
		b.putInt(480 + 124, 656);
		// Bone 1's z angle: data 12 bytes after its own offsets.
		b.putShort(668 + 10, (short) 12);
		b.put(680, (byte) 2).put(681, (byte) 3);
		b.putShort(682, (short) 100).putShort(684, (short) 200);

		name(b, 688, "skin.bmp");
		b.putInt(688 + 64, GoldSrcModel.TEXTURE_MASKED).putInt(688 + 68, 2).putInt(688 + 72, 2).putInt(688 + 76, 768);
		b.put(768, (byte) 0).put(769, (byte) 1).put(770, (byte) 1).put(771, (byte) 255);
		b.put(772, (byte) 10).put(773, (byte) 20).put(774, (byte) 30);
		b.putShort(1540, (short) 0);

		name(b, 1544, "body");
		b.putInt(1544 + 64, 1).putInt(1544 + 72, 1620);
		b.putInt(1620 + 72, 1).putInt(1620 + 76, 1800);
		b.putInt(1620 + 80, 4).putInt(1620 + 84, 1732).putInt(1620 + 88, 1736);
		b.putInt(1620 + 92, 1).putInt(1620 + 96, 1784).putInt(1620 + 100, 1788);
		for (int i = 0; i < 4; i++) {
			b.put(1732 + i, (byte) 1);
			b.putFloat(1736 + i * 12, 1.0F);
		}
		b.put(1784, (byte) 1);
		b.putFloat(1788 + 8, 1.0F);

		b.putInt(1800, 2).putInt(1804, 1820).putInt(1808, 0);
		b.putShort(1820, (short) 4);
		for (int i = 0; i < 4; i++) {
			int o = 1822 + i * 8;
			b.putShort(o, (short) i).putShort(o + 2, (short) 0).putShort(o + 4, (short) (i & 1)).putShort(o + 6, (short) (i >> 1));
		}
		b.putShort(1854, (short) 0);
		return b.array();
	}

	private static void bone(ByteBuffer b, int at, String name, int parent, float[] value, float[] scale) {
		name(b, at, name);
		b.putInt(at + 32, parent);
		for (int c = 0; c < 6; c++) {
			b.putInt(at + 40 + c * 4, -1);
			b.putFloat(at + 64 + c * 4, value[c]);
			b.putFloat(at + 88 + c * 4, scale[c]);
		}
	}

	private static void name(ByteBuffer b, int at, String name) {
		byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
		for (int i = 0; i < bytes.length; i++) {
			b.put(at + i, bytes[i]);
		}
	}
}
