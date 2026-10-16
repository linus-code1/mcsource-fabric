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
