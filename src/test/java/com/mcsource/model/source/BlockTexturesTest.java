package com.mcsource.model.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Builds block textures from a tiny set of fake game materials. */
class BlockTexturesTest {
	@TempDir
	Path dir;

	@Test
	void picksMaterialsAndWritesTextures() throws IOException {
		Map<String, byte[]> files = new HashMap<>();
		material(files, "nature/dirtfloor004a", 0xFF604020);
		material(files, "nature/blendgrassdirt001a", 0xFFFF00FF); // blends are skipped by patterns
		material(files, "nature/grassfloor009b", 0xFF406020);
		files.put("materials/nature/patched.vmt", "\"patch\" { \"include\" \"materials/nature/dirtfloor004a.vmt\" }".getBytes(StandardCharsets.US_ASCII));
		GameFiles game = new GameFiles() {
			@Override
			public byte[] read(String path) {
				return files.get(path.toLowerCase());
			}

			@Override
			public List<String> list(String folder) {
				List<String> out = new ArrayList<>(files.keySet());
				out.removeIf(path -> !path.startsWith(folder));
				return out;
			}
		};

		Map<String, String[]> mapping = BlockTextures.parse("""
				# comment
				dirt = nature/dirtfloor003a nature/dirtfloor00*
				grass_block_top = nature/*grass*
				coarse_dirt = nature/patched
				stone = nature/rockwall*
				""");
		Map<String, String> used = BlockTextures.generate(game, mapping, dir);
		assertEquals("nature/dirtfloor004a", used.get("dirt"));
		assertEquals("nature/grassfloor009b", used.get("grass_block_top"));
		assertEquals("nature/patched", used.get("coarse_dirt"));
		assertNull(used.get("stone"));

		Path blocks = dir.resolve("assets/minecraft/textures/block");
		for (String name : new String[] {"dirt", "grass_block_top", "coarse_dirt", "grass_block_side", "grass_block_side_overlay"}) {
			byte[] png = Files.readAllBytes(blocks.resolve(name + ".png"));
			assertEquals((byte) 0x89, png[0]);
			assertEquals(BlockTextures.SIZE, ByteBuffer.wrap(png, 16, 4).getInt());
		}
		assertTrue(Files.notExists(blocks.resolve("stone.png")));
	}

	@Test
	void cutsPlantsFromSpriteSheet() throws IOException {
		Map<String, byte[]> files = new HashMap<>();
		// A 64x64 sheet: a green tuft on the left, a red flower on the right.
		int size = 64;
		int[] sheet = new int[size * size];
		for (int y = 20; y < 60; y++) {
			for (int x = 4; x < 28; x++) {
				sheet[y * size + x] = 0xFF30A020;
			}
			for (int x = 40; x < 52; x++) {
				sheet[y * size + x] = 0xFFC02020;
			}
		}
		files.put("materials/detail/detailsprites.vmt",
				"\"UnlitGeneric\" { \"$basetexture\" \"detail/detailsprites\" }".getBytes(StandardCharsets.US_ASCII));
		files.put("materials/detail/detailsprites.vtf", vtf(size, sheet));
		GameFiles game = new GameFiles() {
			@Override
			public byte[] read(String path) {
				return files.get(path.toLowerCase());
			}

			@Override
			public List<String> list(String folder) {
				return new ArrayList<>(files.keySet());
			}
		};
		Map<String, String> used = BlockTextures.generate(game, BlockTextures.parse("plants = detail/detailsprites\n"), dir);
		assertTrue(used.get("short_grass").startsWith("detail/detailsprites"));
		// Half-Life 2 has no flowers: they become its green sprites, never the red one.
		assertTrue(used.get("poppy").contains("sprite at 4,"));
		assertTrue(used.get("rose_bush").contains("sprite at 4,"));
		Path blocks = dir.resolve("assets/minecraft/textures/block");
		for (String name : new String[] {"short_grass", "tall_grass_top", "tall_grass_bottom", "fern", "poppy", "rose_bush_top", "rose_bush_bottom"}) {
			assertTrue(Files.exists(blocks.resolve(name + ".png")));
		}
	}

	@Test
	void makesWaterFromRipples() throws IOException {
		Map<String, byte[]> files = new HashMap<>();
		int size = 16;
		int[] ripples = new int[size * size];
		for (int i = 0; i < ripples.length; i++) {
			ripples[i] = 0xFF8080FF ^ (i % 7) << 16;
		}
		files.put("materials/nature/water_test.vmt",
				"\"Water\" { \"$normalmap\" \"nature/water_test_normal\" }".getBytes(StandardCharsets.US_ASCII));
		files.put("materials/nature/water_test_normal.vtf", vtf(size, ripples));
		GameFiles game = new GameFiles() {
			@Override
			public byte[] read(String path) {
				return files.get(path.toLowerCase());
			}

			@Override
			public List<String> list(String folder) {
				return new ArrayList<>(files.keySet());
			}
		};
		Map<String, String> used = BlockTextures.generate(game, BlockTextures.parse("water = nature/water_*\n"), dir);
		assertTrue(used.get("water").startsWith("nature/water_test"));
		Path blocks = dir.resolve("assets/minecraft/textures/block");
		assertTrue(Files.exists(blocks.resolve("water_still.png.mcmeta")));
		byte[] png = Files.readAllBytes(blocks.resolve("water_flow.png"));
		assertEquals(64 * 32, ByteBuffer.wrap(png, 20, 4).getInt());
	}

	private static byte[] vtf(int size, int[] argb) {
		ByteBuffer b = ByteBuffer.allocate(80 + size * size * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0, 0x00465456).putInt(4, 7).putInt(8, 2).putInt(12, 80);
		b.putShort(16, (short) size).putShort(18, (short) size).putShort(24, (short) 1);
		b.putInt(52, 12); // BGRA8888
		b.put(56, (byte) 1).putInt(57, -1).putShort(63, (short) 1);
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			b.put(80 + i * 4, (byte) p).put(81 + i * 4, (byte) (p >> 8)).put(82 + i * 4, (byte) (p >> 16)).put(83 + i * 4, (byte) (p >>> 24));
		}
		return b.array();
	}

	/** A material and its 4x4 single-colour texture. */
	private static void material(Map<String, byte[]> files, String name, int argb) {
		files.put("materials/" + name + ".vmt",
				("\"LightmappedGeneric\" { \"$basetexture\" \"" + name + "\" }").getBytes(StandardCharsets.US_ASCII));
		ByteBuffer b = ByteBuffer.allocate(80 + 64).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0, 0x00465456).putInt(4, 7).putInt(8, 2).putInt(12, 80);
		b.putShort(16, (short) 4).putShort(18, (short) 4).putShort(24, (short) 1);
		b.putInt(52, 12); // BGRA8888
		b.put(56, (byte) 1).putInt(57, -1).putShort(63, (short) 1);
		for (int i = 0; i < 16; i++) {
			b.put(80 + i * 4, (byte) argb).put(81 + i * 4, (byte) (argb >> 8)).put(82 + i * 4, (byte) (argb >> 16)).put(83 + i * 4, (byte) 255);
		}
		files.put("materials/" + name + ".vtf", b.array());
	}
}
