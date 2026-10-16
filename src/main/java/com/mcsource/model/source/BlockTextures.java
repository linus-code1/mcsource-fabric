package com.mcsource.model.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import com.mcsource.model.RiggedModel;

/**
 * Builds Minecraft block textures from Half-Life 2's materials, read from the
 * player's own game files: for each Minecraft texture, the first material in
 * its list that exists is decoded, made square and written as a PNG in a
 * resource pack folder. Nothing from Valve ships with the mod.
 *
 * <p>Each list holds material paths (relative to {@code materials/}) and
 * {@code *} patterns, tried in order; a pattern takes the first match in
 * alphabetical order, skipping blends, water, decals and other special
 * materials.
 */
public final class BlockTextures {
	/** Largest texture size written. Half-Life 2's are mostly 512; one block shows the whole texture. */
	public static final int SIZE = 256;

	/** Minecraft's default biome tints, divided back out of tinted textures so they keep Half-Life 2's colours in plains. */
	private static final int GRASS_TINT = 0x91BD59;
	private static final int FOLIAGE_TINT = 0x77AB2F;

	private static final Pattern SKIP = Pattern.compile(
			".*(blend|water|sky|decal|tools/|dev/|_normal|_bump|ssbump|overlay|sprite|refract|glow|_mask|_spec|_phong|editor|nodraw|detail|_noise|_illum).*");

	/**
	 * The default list, one line per Minecraft block texture. It is saved to
	 * the player's config folder, where it can be edited.
	 */
	public static final String DEFAULTS = """
			# Half-Life 2 materials used for Minecraft block textures.
			# Each line: minecraft_texture = material material ...
			# Materials are paths under materials/ in the game (without .vmt); * matches anything.
			# The first one found is used. Delete a line to keep Minecraft's texture.
			grass_block_top = nature/grassfloor002a nature/grassfloor003a nature/grassfloor00* nature/*grass* *grass*
			dirt = nature/dirtfloor003a nature/dirtfloor004a nature/dirtfloor00* nature/*dirt* *dirt*
			coarse_dirt = nature/dirtfloor005c nature/dirtfloor006a nature/dirtfloor01* nature/*dirt* *dirt*
			rooted_dirt = nature/dirtfloor006a nature/*dirt* *dirt*
			podzol_top = nature/forestfloor* nature/*leaves* nature/dirtfloor006a nature/*dirt*
			mud = nature/mudfloor* nature/*mud* *mud* nature/*dirt*
			farmland = nature/*dirt* *dirt*
			farmland_moist = nature/*mud* nature/*dirt* *dirt*
			stone = nature/rockwall004a nature/rockwall00* nature/rockfloor00* nature/*rock* nature/cliff* *rock*
			andesite = nature/rockfloor005a nature/rockfloor00* nature/*rock* *rock*
			diorite = nature/rockwall006a nature/rockwall0* nature/*rock* *rock*
			granite = nature/rockwall_cave* nature/rockwall0* nature/*rock* *rock*
			tuff = nature/rockfloor0* nature/*rock* *rock*
			deepslate = nature/rockwall_cave* nature/*cave* nature/rockwall0* *rock*
			deepslate_top = nature/rockwall_cave* nature/*cave* nature/rockwall0* *rock*
			cobbled_deepslate = stone/stonefloor0* nature/*cobble* *cobble* nature/rockfloor0*
			bedrock = nature/rockwall_cave* nature/rockwall0* *rock*
			cobblestone = nature/cobble* stone/*cobble* *cobble* stone/stonefloor00* nature/rockfloor00*
			mossy_cobblestone = stone/*moss* nature/*moss* *moss* *cobble*
			smooth_stone = concrete/concretefloor001a concrete/concretefloor0* concrete/*
			stone_bricks = stone/stonewall0* stone/*wall* stone/* *stonewall*
			mossy_stone_bricks = stone/*moss* *moss* stone/stonewall0*
			cracked_stone_bricks = stone/*crack* *crack* stone/stonewall0*
			chiseled_stone_bricks = stone/stonewall0* stone/*
			bricks = brick/brickwall001a brick/brickwall0* brick/* *brick*
			gravel = nature/gravelfloor002a nature/gravelfloor00* nature/*gravel* *gravel*
			sand = nature/sandfloor009a nature/sandfloor0* nature/*sand* *sand*
			red_sand = nature/sandfloor0* nature/*sand* *sand*
			sandstone = stone/*sand* nature/*sandstone* *sandstone* concrete/concretewall0*
			sandstone_top = stone/*sand* nature/*sandstone* *sandstone* concrete/concretefloor0*
			sandstone_bottom = stone/*sand* nature/*sandstone* *sandstone* concrete/concretefloor0*
			clay = *clay* concrete/concretefloor0*
			snow = nature/snowfloor00* nature/*snow* *snow*
			snow_block = nature/snowfloor00* nature/*snow* *snow*
			ice = nature/ice* */ice0* nature/*frozen*
			packed_ice = nature/ice* */ice0* nature/*frozen*
			oak_planks = wood/woodfloor005a wood/woodfloor007a wood/woodfloor00* wood/*plank* wood/*floor* wood/*
			spruce_planks = wood/woodfloor008a wood/woodfloor0* wood/*floor* wood/*
			birch_planks = wood/woodwall009a wood/woodwall0* wood/*wall* wood/*
			jungle_planks = wood/woodfloor001a wood/woodfloor0* wood/*
			acacia_planks = wood/woodfloor002a wood/woodfloor0* wood/*
			dark_oak_planks = wood/woodfloor006a wood/woodfloor0* wood/*
			oak_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			oak_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			spruce_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			spruce_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			birch_log = nature/*birch* *birch* nature/*bark* *bark*
			birch_log_top = wood/*stump* *stump* wood/woodfloor0*
			oak_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			birch_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			spruce_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			jungle_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			acacia_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			dark_oak_leaves = nature/*leaves* *leaves* *foliage* *leaf*
			terracotta = plaster/plasterwall0* plaster/* *plaster*
			white_concrete = concrete/concretewall001a concrete/concretewall0* concrete/*
			light_gray_concrete = concrete/concretewall0* concrete/*
			gray_concrete = concrete/concretefloor0* concrete/*
			polished_andesite = concrete/concretefloor0* concrete/*
			polished_diorite = concrete/concretewall0* concrete/*
			polished_granite = stone/*floor* tile/* concrete/*
			iron_block = metal/metalwall001a metal/metalwall0* metal/*wall* metal/*
			quartz_block_side = plaster/plasterwall0* tile/* plaster/*
			quartz_block_top = tile/tilefloor0* plaster/plasterwall0* tile/*
			""";

	private BlockTextures() {
	}

	/** Reads a list in the {@link #DEFAULTS} format. */
	public static Map<String, String[]> parse(String text) {
		Map<String, String[]> out = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
				continue;
			}
			String key = trimmed.substring(0, equals).trim();
			String[] patterns = trimmed.substring(equals + 1).trim().split("\\s+");
			if (!key.isEmpty() && patterns.length > 0 && !patterns[0].isEmpty()) {
				out.put(key, patterns);
			}
		}
		return out;
	}

	/**
	 * Writes {@code assets/minecraft/textures/block/<name>.png} under
	 * {@code packRoot} for every texture whose material is found. Returns
	 * which material each texture uses, then the textures with none, as
	 * {@code name -> material or null}.
	 */
	public static Map<String, String> generate(GameFiles files, Map<String, String[]> mapping, Path packRoot) throws IOException {
		List<String> materials = new ArrayList<>();
		for (String path : files.list("materials/")) {
			if (path.endsWith(".vmt")) {
				materials.add(path.substring("materials/".length(), path.length() - ".vmt".length()));
			}
		}
		materials.sort(null);
		Path folder = packRoot.resolve("assets/minecraft/textures/block");
		Files.createDirectories(folder);

		Map<String, String> used = new LinkedHashMap<>();
		Map<String, int[]> made = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> entry : mapping.entrySet()) {
			String name = entry.getKey();
			String chosen = null;
			int[] image = null;
			for (String pattern : entry.getValue()) {
				for (String material : candidates(pattern, materials)) {
					RiggedModel.Texture texture = Materials.load(files, material, SIZE * 2);
					if (texture != null) {
						chosen = material;
						image = square(texture);
						break;
					}
				}
				if (image != null) {
					break;
				}
			}
			used.put(name, chosen);
			if (image == null) {
				continue;
			}
			int tint = tint(name);
			if (tint != 0) {
				untint(image, tint);
			}
			made.put(name, image);
			writePng(folder.resolve(name + ".png"), image, SIZE);
		}

		// Grass and snowy grass block sides: the dirt with a band of the top
		// along its upper edge, which Minecraft would otherwise tint and lay
		// over the side.
		int[] dirt = made.get("dirt");
		if (dirt != null) {
			int[] grass = made.get("grass_block_top");
			if (grass != null) {
				int[] colored = grass.clone();
				retint(colored, GRASS_TINT);
				writePng(folder.resolve("grass_block_side.png"), band(dirt, colored), SIZE);
				writePng(folder.resolve("grass_block_side_overlay.png"), new int[SIZE * SIZE], SIZE);
			}
			int[] snow = made.get("snow");
			if (snow != null) {
				writePng(folder.resolve("grass_block_snow.png"), band(dirt, snow), SIZE);
			}
		}
		return used;
	}

	private static List<String> candidates(String pattern, List<String> materials) {
		String lower = pattern.toLowerCase(Locale.ROOT);
		if (!lower.contains("*")) {
			return List.of(lower);
		}
		Pattern regex = Pattern.compile(Pattern.quote(lower).replace("*", "\\E.*\\Q"));
		List<String> out = new ArrayList<>();
		for (String material : materials) {
			if (regex.matcher(material).matches() && !SKIP.matcher(material).matches()) {
				out.add(material);
				if (out.size() >= 8) {
					break;
				}
			}
		}
		return out;
	}

	/** Tinted textures: Minecraft multiplies them by the biome colour. */
	private static int tint(String name) {
		if (name.equals("grass_block_top")) {
			return GRASS_TINT;
		}
		if (name.endsWith("_leaves") && !name.startsWith("cherry") && !name.startsWith("azalea")) {
			return switch (name) {
				case "birch_leaves" -> 0x80A755;
				case "spruce_leaves" -> 0x619961;
				default -> FOLIAGE_TINT;
			};
		}
		return 0;
	}

	private static void untint(int[] image, int tint) {
		float[] t = {(tint >> 16 & 255) / 255.0F, (tint >> 8 & 255) / 255.0F, (tint & 255) / 255.0F};
		for (int i = 0; i < image.length; i++) {
			int p = image[i];
			int r = Math.min(255, Math.round((p >> 16 & 255) / t[0]));
			int g = Math.min(255, Math.round((p >> 8 & 255) / t[1]));
			int b = Math.min(255, Math.round((p & 255) / t[2]));
			image[i] = (p & 0xFF000000) | r << 16 | g << 8 | b;
		}
	}

	private static void retint(int[] image, int tint) {
		for (int i = 0; i < image.length; i++) {
			int p = image[i];
			int r = (p >> 16 & 255) * (tint >> 16 & 255) / 255;
			int g = (p >> 8 & 255) * (tint >> 8 & 255) / 255;
			int b = (p & 255) * (tint & 255) / 255;
			image[i] = (p & 0xFF000000) | r << 16 | g << 8 | b;
		}
	}

	/** {@code base} with the top of {@code top} along its upper edge, with a ragged lower edge. */
	private static int[] band(int[] base, int[] top) {
		int[] out = base.clone();
		int depth = SIZE * 3 / 16;
		for (int x = 0; x < SIZE; x++) {
			// A repeatable ragged edge, the same at both sides so blocks join up.
			double wave = Math.sin(x * 2 * Math.PI / SIZE * 5) * 0.5 + Math.sin(x * 2 * Math.PI / SIZE * 13 + 1.3) * 0.3;
			int rows = depth + (int) Math.round(wave * SIZE / 32);
			for (int y = 0; y < rows; y++) {
				out[y * SIZE + x] = top[y * SIZE + x];
			}
		}
		return out;
	}

	/** Resamples a texture to {@link #SIZE} by {@link #SIZE}, wrapping at the edges so it still tiles. */
	private static int[] square(RiggedModel.Texture texture) {
		int w = texture.width();
		int h = texture.height();
		int[] in = texture.pixels();
		int[] out = new int[SIZE * SIZE];
		// Average the source texels each output texel covers (box filter), or
		// interpolate when the source is smaller.
		float sx = (float) w / SIZE;
		float sy = (float) h / SIZE;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int x0 = (int) Math.floor(x * sx);
				int y0 = (int) Math.floor(y * sy);
				int nx = Math.max(1, Math.round(sx));
				int ny = Math.max(1, Math.round(sy));
				long a = 0;
				long r = 0;
				long g = 0;
				long b = 0;
				for (int j = 0; j < ny; j++) {
					for (int i = 0; i < nx; i++) {
						int p = in[Math.floorMod(y0 + j, h) * w + Math.floorMod(x0 + i, w)];
						a += p >>> 24;
						r += p >> 16 & 255;
						g += p >> 8 & 255;
						b += p & 255;
					}
				}
				int n = nx * ny;
				out[y * SIZE + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}

	/** Writes ARGB pixels as an 8-bit RGBA PNG. */
	public static void writePng(Path file, int[] argb, int size) throws IOException {
		byte[] raw = new byte[size * (size * 4 + 1)];
		int at = 0;
		for (int y = 0; y < size; y++) {
			raw[at++] = 0; // no filter
			for (int x = 0; x < size; x++) {
				int p = argb[y * size + x];
				raw[at++] = (byte) (p >> 16);
				raw[at++] = (byte) (p >> 8);
				raw[at++] = (byte) p;
				raw[at++] = (byte) (p >>> 24);
			}
		}
		Deflater deflater = new Deflater(6);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		while (!deflater.finished()) {
			compressed.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		ByteBuffer header = ByteBuffer.allocate(13);
		header.putInt(size).putInt(size).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(png, "IHDR", header.array());
		chunk(png, "IDAT", compressed.toByteArray());
		chunk(png, "IEND", new byte[0]);
		Files.write(file, png.toByteArray());
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
