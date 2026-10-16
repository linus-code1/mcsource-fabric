package com.mcsource.model.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
	 * The built-in list, one line per Minecraft block texture. Lines in the
	 * player's own list replace these.
	 */
	public static final String DEFAULTS = """
			# The mod's list of Half-Life 2 materials for Minecraft block textures.
			# Each line: minecraft_texture = material material ...
			# Materials are paths under materials/ in the game (without .vmt); * matches anything.
			# The first one found is used.
			# Grass, ferns and flowers are cut out of Half-Life 2's detail sprite sheet (its grass is sprites too).
			plants = detail/detailsprites detail/detailsprites_2 detail/detailsprites*
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
			if (name.equals("plants")) {
				plants(files, entry.getValue(), materials, folder, used);
				continue;
			}
			String chosen = null;
			int[] image = null;
			for (String pattern : entry.getValue()) {
				for (String material : candidates(pattern, materials)) {
					RiggedModel.Texture texture = Materials.load(files, material, SIZE * 2);
					if (texture != null && suits(name, texture)) {
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

	/**
	 * Whether a texture can stand in for a Minecraft one. Leaves and grass
	 * must be mostly green and not too dark, so bare branches or dead grass
	 * found by a pattern are passed over.
	 */
	private static boolean suits(String name, RiggedModel.Texture texture) {
		if (tint(name) == 0) {
			return true;
		}
		long r = 0;
		long g = 0;
		long b = 0;
		int count = 0;
		for (int p : texture.pixels()) {
			if ((p >>> 24) < 128) {
				continue;
			}
			r += p >> 16 & 255;
			g += p >> 8 & 255;
			b += p & 255;
			count++;
		}
		if (count < texture.pixels().length / 4) {
			return false;
		}
		return g >= r && g >= b && (r + g + b) / (3.0 * count) > 30;
	}

	private static List<String> candidates(String pattern, List<String> materials) {
		return candidates(pattern, materials, true);
	}

	private static List<String> candidates(String pattern, List<String> materials, boolean skipSpecial) {
		String lower = pattern.toLowerCase(Locale.ROOT);
		if (!lower.contains("*")) {
			return List.of(lower);
		}
		Pattern regex = Pattern.compile(Pattern.quote(lower).replace("*", "\\E.*\\Q"));
		List<String> out = new ArrayList<>();
		for (String material : materials) {
			if (regex.matcher(material).matches() && !(skipSpecial && SKIP.matcher(material).matches())) {
				out.add(material);
				if (out.size() >= 8) {
					break;
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------ plants

	/** Plant textures and the kind of sprite each takes. */
	private static final String[][] GREEN_PLANTS = {
		{"short_grass", "wide"}, {"fern", "any"}, {"tall_grass", "tall"}, {"large_fern", "any"}, {"short_dry_grass", "wide"},
		{"bush", "wide"}, {"sugar_cane", "tall"},
	};
	private static final String[][] FLOWERS = {
		{"poppy", "red"}, {"red_tulip", "red"}, {"dandelion", "yellow"}, {"sunflower_front", "yellow"}, {"cornflower", "blue"},
		{"blue_orchid", "blue"}, {"oxeye_daisy", "white"}, {"azure_bluet", "white"}, {"lily_of_the_valley", "white"},
		{"white_tulip", "white"}, {"allium", "purple"}, {"pink_tulip", "purple"}, {"orange_tulip", "red"},
	};
	/** Plant textures Minecraft tints with the grass colour. */
	private static final java.util.Set<String> GRASS_TINTED = java.util.Set.of("short_grass", "fern", "tall_grass_top",
		"tall_grass_bottom", "large_fern_top", "large_fern_bottom", "sugar_cane", "bush");

	/** One sprite on a sheet: its bounds and average colour. */
	private record Sprite(int x, int y, int w, int h, int pixels, float r, float g, float b) {
		String colour() {
			float max = Math.max(r, Math.max(g, b));
			float min = Math.min(r, Math.min(g, b));
			if (max - min < 0.12F) {
				return max > 0.6F ? "white" : "grey";
			}
			if (g >= r && g >= b) {
				return r > g * 0.95F && b < g * 0.6F ? "yellow" : "green";
			}
			if (r >= g && r >= b) {
				if (b > g * 1.1F) {
					return "purple";
				}
				return g > r * 0.7F ? (b < g * 0.6F ? "yellow" : "brown") : "red";
			}
			return r > g ? "purple" : "blue";
		}
	}

	/**
	 * Cuts grass, ferns and flowers out of a sprite sheet: every separate
	 * patch of opaque pixels is a sprite, and sprites are matched to plants
	 * by colour and shape.
	 */
	private static void plants(GameFiles files, String[] patterns, List<String> materials, Path folder, Map<String, String> used)
			throws IOException {
		RiggedModel.Texture sheet = null;
		String chosen = null;
		for (String pattern : patterns) {
			for (String material : candidates(pattern, materials, false)) {
				RiggedModel.Texture texture = Materials.load(files, material, 1024, true);
				if (texture != null) {
					sheet = texture;
					chosen = material;
					break;
				}
			}
			if (sheet != null) {
				break;
			}
		}
		if (sheet == null) {
			used.put("plants", null);
			return;
		}
		List<Sprite> sprites = findSprites(sheet);
		List<Sprite> green = new ArrayList<>();
		Map<String, List<Sprite>> coloured = new LinkedHashMap<>();
		for (Sprite sprite : sprites) {
			String colour = sprite.colour();
			if (colour.equals("green")) {
				green.add(sprite);
			} else {
				coloured.computeIfAbsent(colour, c -> new ArrayList<>()).add(sprite);
			}
		}
		if (green.isEmpty()) {
			used.put("plants", null);
			return;
		}
		// Biggest first.
		green.sort((a, b) -> Integer.compare(b.pixels, a.pixels));
		int next = 0;
		for (String[] plant : GREEN_PLANTS) {
			Sprite pick = pickShape(green, plant[1], next++);
			String name = plant[0];
			if (name.equals("tall_grass") || name.equals("large_fern")) {
				int[] tall = spriteImage(sheet, pick, SIZE, SIZE * 2);
				writePlant(folder, name + "_top", java.util.Arrays.copyOfRange(tall, 0, SIZE * SIZE));
				writePlant(folder, name + "_bottom", java.util.Arrays.copyOfRange(tall, SIZE * SIZE, SIZE * SIZE * 2));
			} else {
				writePlant(folder, name, spriteImage(sheet, pick, SIZE, SIZE));
			}
			used.put(name, chosen + " (sprite at " + pick.x + "," + pick.y + ")");
		}
		List<Sprite> brown = coloured.getOrDefault("brown", List.of());
		if (!brown.isEmpty()) {
			writePlant(folder, "dead_bush", spriteImage(sheet, brown.getFirst(), SIZE, SIZE));
			used.put("dead_bush", chosen + " (sprite at " + brown.getFirst().x + "," + brown.getFirst().y + ")");
		}
		Map<String, Integer> nextOfColour = new HashMap<>();
		for (String[] flower : FLOWERS) {
			List<Sprite> options = coloured.getOrDefault(flower[1], List.of());
			if (options.isEmpty()) {
				used.put(flower[0], null);
				continue;
			}
			int index = nextOfColour.merge(flower[1], 1, Integer::sum) - 1;
			Sprite pick = options.get(index % options.size());
			writePlant(folder, flower[0], spriteImage(sheet, pick, SIZE, SIZE));
			used.put(flower[0], chosen + " (sprite at " + pick.x + "," + pick.y + ")");
		}
	}

	/** The {@code index}th sprite of a shape among {@code sprites}, wrapping round when there are too few. */
	private static Sprite pickShape(List<Sprite> sprites, String shape, int index) {
		List<Sprite> fitting = new ArrayList<>();
		for (Sprite sprite : sprites) {
			boolean ok = switch (shape) {
				case "wide" -> sprite.w >= sprite.h * 0.8F;
				case "tall" -> sprite.h >= sprite.w * 1.3F;
				default -> true;
			};
			if (ok) {
				fitting.add(sprite);
			}
		}
		if (fitting.isEmpty()) {
			fitting = sprites;
		}
		return fitting.get(index % fitting.size());
	}

	private static void writePlant(Path folder, String name, int[] image) throws IOException {
		if (GRASS_TINTED.contains(name)) {
			untint(image, GRASS_TINT);
		}
		writePng(folder.resolve(name + ".png"), image, SIZE);
	}

	/** Separate patches of opaque pixels, small specks left out. */
	private static List<Sprite> findSprites(RiggedModel.Texture sheet) {
		int w = sheet.width();
		int h = sheet.height();
		int[] px = sheet.pixels();
		// Grow the opaque area a little so the blades of one tuft join up.
		boolean[] solid = new boolean[w * h];
		int grow = Math.max(1, w / 256);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if ((px[y * w + x] >>> 24) < 96) {
					continue;
				}
				for (int dy = -grow; dy <= grow; dy++) {
					for (int dx = -grow; dx <= grow; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
							solid[ny * w + nx] = true;
						}
					}
				}
			}
		}
		int[] label = new int[w * h];
		int[] stack = new int[w * h];
		List<Sprite> out = new ArrayList<>();
		int minSide = Math.max(4, w / 40);
		int next = 1;
		for (int start = 0; start < w * h; start++) {
			if (!solid[start] || label[start] != 0) {
				continue;
			}
			int top = 0;
			stack[top++] = start;
			label[start] = next;
			int minX = w;
			int minY = h;
			int maxX = 0;
			int maxY = 0;
			int count = 0;
			double r = 0;
			double g = 0;
			double b = 0;
			while (top > 0) {
				int i = stack[--top];
				int x = i % w;
				int y = i / w;
				int p = px[i];
				if ((p >>> 24) >= 96) {
					// Bounds of the sprite's own pixels, not the grown area.
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
					count++;
					r += (p >> 16 & 255) / 255.0;
					g += (p >> 8 & 255) / 255.0;
					b += (p & 255) / 255.0;
				}
				int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] step : steps) {
					int nx = x + step[0];
					int ny = y + step[1];
					if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
						int n = ny * w + nx;
						if (solid[n] && label[n] == 0) {
							label[n] = next;
							stack[top++] = n;
						}
					}
				}
			}
			next++;
			int sw = maxX - minX + 1;
			int sh = maxY - minY + 1;
			if (sw >= minSide && sh >= minSide && count > 0) {
				out.add(new Sprite(minX, minY, sw, sh, count, (float) (r / count), (float) (g / count), (float) (b / count)));
			}
		}
		return out;
	}

	/** A sprite scaled to fit {@code w} by {@code h}, standing on the bottom edge and centred. */
	private static int[] spriteImage(RiggedModel.Texture sheet, Sprite sprite, int w, int h) {
		int[] out = new int[w * h];
		float scale = Math.min((float) w / sprite.w, (float) h / sprite.h);
		int dw = Math.max(1, Math.round(sprite.w * scale));
		int dh = Math.max(1, Math.round(sprite.h * scale));
		int ox = (w - dw) / 2;
		int oy = h - dh;
		int sheetW = sheet.width();
		for (int y = 0; y < dh; y++) {
			for (int x = 0; x < dw; x++) {
				int sx = Math.min(sprite.x + (int) (x / scale), sprite.x + sprite.w - 1);
				int sy = Math.min(sprite.y + (int) (y / scale), sprite.y + sprite.h - 1);
				int p = sheet.pixels()[sy * sheetW + sx];
				// Minecraft's cut-out drops pixels below 10% alpha; make edges firm.
				int a = p >>> 24;
				out[(oy + y) * w + ox + x] = a < 96 ? 0 : (p | 0xFF000000);
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
