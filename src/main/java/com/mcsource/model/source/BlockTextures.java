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
			# Mossy cobblestone and mossy stone bricks are made from cobblestone and stone bricks with the grass grown into them.
			plants = detail/detailsprites detail/detailsprites_2 detail/detailsprites*
			# Water is made from the ripple normal map of one of Half-Life 2's water materials.
			water = nature/water_canals_water2 nature/water_canals03 nature/water_coast01 nature/water_* nature/*water* *water*
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
			smooth_stone = concrete/concretefloor001a concrete/concretefloor0* concrete/*
			stone_bricks = stone/stonewall0* stone/*wall* stone/* *stonewall*
			cracked_stone_bricks = stone/*crack* *crack* stone/stonewall0*
			chiseled_stone_bricks = stone/stonewall0* stone/*
			bricks = brick/brickwall001a brick/brickwall0* brick/* *brick*
			gravel = nature/gravelfloor002a nature/gravelfloor00* nature/*gravel* *gravel*
			sand = nature/sandfloor009a nature/sandfloor0* nature/*sand* *sand*
			red_sand = nature/sandfloor0* nature/*sand* *sand*
			sandstone = stone/*sand* nature/*sandstone* *sandstone* concrete/concretewall0*
			sandstone_top = stone/*sand* nature/*sandstone* *sandstone* concrete/concretefloor0*
			sandstone_bottom = stone/*sand* nature/*sandstone* *sandstone* concrete/concretefloor0*
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
			oak_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			birch_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			spruce_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			jungle_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			acacia_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			dark_oak_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			jungle_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			jungle_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			acacia_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			acacia_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			dark_oak_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			dark_oak_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			mangrove_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			mangrove_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			cherry_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			cherry_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			pale_oak_log = nature/*bark* wood/*bark* *bark* wood/woodbeam*
			pale_oak_log_top = wood/*stump* *stump* wood/*end* wood/woodfloor0*
			stripped_oak_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_oak_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_spruce_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_spruce_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_birch_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_birch_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_jungle_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_jungle_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_acacia_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_acacia_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_dark_oak_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_dark_oak_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_mangrove_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_mangrove_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_cherry_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_cherry_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			stripped_pale_oak_log = wood/woodbeam* wood/*beam* wood/woodwall0* wood/*
			stripped_pale_oak_log_top = wood/*stump* wood/*end* wood/woodfloor0*
			mangrove_planks = wood/woodfloor0* wood/*floor* wood/*
			cherry_planks = wood/woodfloor0* wood/*floor* wood/*
			pale_oak_planks = wood/woodfloor0* wood/*floor* wood/*
			bamboo_planks = wood/woodfloor0* wood/*floor* wood/*
			mangrove_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			cherry_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			pale_oak_leaves = models/props_foliage/*leaves* models/props_foliage/*leaf* models/props_foliage/*bush* models/props_foliage/*tree* nature/*leaves* *leaves* *foliage*
			dirt_path_top = nature/dirtfloor0* nature/*dirt* *dirt*
			dirt_path_side = nature/dirtfloor0* nature/*dirt* *dirt*
			podzol_side = nature/dirtfloor0* nature/*dirt*
			mycelium_side = nature/dirtfloor0* nature/*dirt*
			smooth_sandstone = stone/*sand* *sandstone* concrete/concretefloor0*
			cut_sandstone = stone/*sand* *sandstone* concrete/concretewall0*
			chiseled_sandstone = stone/*sand* *sandstone* concrete/concretewall0*
			red_sandstone = stone/*sand* *sandstone* brick/*
			polished_tuff = concrete/concretefloor0* stone/*
			tuff_bricks = stone/stonewall0* brick/*
			deepslate_bricks = stone/stonewall0* brick/*
			deepslate_tiles = tile/tilefloor0* stone/*
			polished_deepslate = concrete/concretefloor0* stone/*
			mud_bricks = brick/brickwall0* brick/*
			hay_block_side = *hay* *straw* *thatch* nature/*dry*grass* nature/*grass*dry* nature/*grass*
			hay_block_top = *hay* *straw* *thatch* nature/*dry*grass* nature/*grass*dry* nature/*grass*
			white_terracotta = plaster/plasterwall0* plaster/*
			white_wool = *fabric* *cloth* plaster/*
			stone_bricks_wall = stone/stonewall0*
			calcite = stone/*white* plaster/* concrete/*
			dripstone_block = nature/rock* stone/*
			moss_block = nature/*moss* *moss* nature/*grass*
			rooted_dirt = nature/dirtfloor006a nature/*dirt* *dirt*
			iron_bars = metal/*grate* metal/*fence* *grate*
			iron_door_top = metal/metaldoor0* metal/*door* metal/*
			iron_door_bottom = metal/metaldoor0* metal/*door* metal/*
			oak_door_top = wood/*door* wood/woodwall0*
			oak_door_bottom = wood/*door* wood/woodwall0*
			spruce_door_top = wood/*door* wood/woodwall0*
			spruce_door_bottom = wood/*door* wood/woodwall0*
			anvil = metal/*
			coal_block = nature/*coal* stone/*dark* metal/*dark*
			gold_block = metal/*gold* *brass* metal/*
			copper_block = metal/*copper* *copper* metal/*rust*
			oxidized_copper = metal/*green* metal/*copper* *copper*
			netherrack = nature/*lava* nature/rockfloor0* *rock*
			blackstone = nature/rockwall_cave* stone/*dark* *rock*
			basalt_side = nature/rockwall0* *rock*
			basalt_top = nature/rockfloor0* *rock*
			end_stone = nature/sandfloor0* nature/rock*
			obsidian = nature/*obsidian* stone/*black* nature/rockwall_cave*
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

	/**
	 * Every leaves block drawn as a clump of crossed foliage cards reaching a
	 * little past the block, the way Source trees are built from leaf cards,
	 * instead of a cube. Replaces the model all leaves share.
	 */
	private static final String LEAVES_MODEL = """
			{
				"parent": "minecraft:block/block",
				"ambientocclusion": false,
				"textures": {"particle": "#all"},
				"elements": [
					%s,
					%s,
					%s,
					%s,
					%s
				]
			}
			""".formatted(
			card(-4, -2, 8, 20, 18, 8, "y", 45),
			card(-4, -2, 8, 20, 18, 8, "y", -45),
			card(8, -3, -4, 8, 19, 20, "y", 0),
			card(-4, -3, 8, 20, 19, 8, "y", 0),
			"{\"from\": [-4, 9, -4], \"to\": [20, 9, 20], \"shade_direction_override\": \"up\", \"faces\": {"
					+ "\"up\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#all\", \"tintindex\": 0}, "
					+ "\"down\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#all\", \"tintindex\": 0}}}");

	/** One flat, two-sided foliage card. */
	private static String card(int x1, int y1, int z1, int x2, int y2, int z2, String axis, int angle) {
		String a = x1 == x2 ? "east" : "north";
		String b = x1 == x2 ? "west" : "south";
		String face = "{\"uv\": [0, 0, 16, 16], \"texture\": \"#all\", \"tintindex\": 0}";
		return "{\"from\": [" + x1 + ", " + y1 + ", " + z1 + "], \"to\": [" + x2 + ", " + y2 + ", " + z2 + "], \"shade_direction_override\": \"up\", "
				+ "\"rotation\": {\"origin\": [8, 8, 8], \"axis\": \"" + axis + "\", \"angle\": " + angle + ", \"rescale\": true}, "
				+ "\"faces\": {\"" + a + "\": " + face + ", \"" + b + "\": " + face + "}}";
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
		Map<String, String> errors = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> entry : mapping.entrySet()) {
			String name = entry.getKey();
			try {
				if (name.equals("plants")) {
					// Done last, so the grass can take the colour of the ground.
					continue;
				} else if (name.equals("water")) {
					water(files, entry.getValue(), materials, folder, used);
				} else {
					one(files, name, entry.getValue(), materials, folder, used, made);
				}
			} catch (IOException | RuntimeException e) {
				// One texture failing leaves the rest.
				used.put(name, null);
				errors.put(name, e.toString());
			}
		}
		sandColour = made.containsKey("sand") ? average(made.get("sand")) : 0;
		if (mapping.containsKey("plants")) {
			try {
				plants(files, mapping.get("plants"), materials, folder, used, made.get("grass_block_top"), packRoot);
			} catch (IOException | RuntimeException e) {
				used.put("plants", null);
				errors.put("plants", e.toString());
			}
		}
		for (String[] ore : ORES) {
			ore(made, ore[0], ore[1], Integer.parseInt(ore[2], 16), folder, used);
		}
		mossy(made, "cobblestone", "mossy_cobblestone", folder, used);
		mossy(made, "stone_bricks", "mossy_stone_bricks", folder, used);
		try {
			madeTextures(files, materials, made, folder, packRoot, used);
		} catch (IOException | RuntimeException e) {
			errors.put("made", e.toString());
		}
		if (made.keySet().stream().anyMatch(name -> name.endsWith("_leaves"))) {
			Path models = packRoot.resolve("assets/minecraft/models/block");
			Files.createDirectories(models);
			Files.writeString(models.resolve("leaves.json"), LEAVES_MODEL);
		}
		if (!errors.isEmpty()) {
			used.put("errors", errors.toString());
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

	/** One ordinary block texture: the first of its materials found. */
	private static void one(GameFiles files, String name, String[] patterns, List<String> materials, Path folder, Map<String, String> used,
			Map<String, int[]> made) throws IOException {
		String chosen = null;
		int[] image = null;
		for (String pattern : patterns) {
			for (String material : candidates(pattern, materials)) {
				// Leaves keep the cut-out of Half-Life 2's foliage cards.
				RiggedModel.Texture texture = tryLoad(files, material, SIZE * 2, name.endsWith("_leaves"));
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
			return;
		}
		int tint = tint(name);
		if (tint != 0) {
			untint(image, tint);
		}
		if (name.endsWith("_leaves")) {
			fillGaps(image);
		}
		made.put(name, image);
		writePng(folder.resolve(name + ".png"), image, SIZE);
	}

	/** A material, or null if it is missing or can't be read (a broken one is passed over). */
	private static RiggedModel.Texture tryLoad(GameFiles files, String material, int maxSize, boolean keepAlpha) {
		try {
			return Materials.load(files, material, maxSize, keepAlpha);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ------------------------------------------------------------ water

	private static final Pattern NORMAL_MAP = Pattern.compile("\"?\\$(normalmap|bumpmap)\"?\\s+\"?([^\"\\s}]+)", Pattern.CASE_INSENSITIVE);
	private static final int WATER_SIZE = 64;
	private static final int WATER_FRAMES = 32;
	/** Minecraft's default water colour, divided back out. */
	private static final int WATER_TINT = 0x3F76E4;
	/** The murky green-brown of Half-Life 2's canal water. */
	private static final int WATER_COLOUR = 0x3A4A3A;

	/**
	 * Water from a Half-Life 2 water material's ripple normal map: two layers
	 * of ripples drifting past each other, lit from above, in the murky colour
	 * of Half-Life 2's canals. Written as animation strips for still and
	 * flowing water.
	 */
	private static void water(GameFiles files, String[] patterns, List<String> materials, Path folder, Map<String, String> used)
			throws IOException {
		Vtf normals = null;
		String chosen = null;
		for (String pattern : patterns) {
			for (String material : candidates(pattern, materials, false)) {
				byte[] vmt = files.read("materials/" + material + ".vmt");
				if (vmt == null) {
					continue;
				}
				java.util.regex.Matcher m = NORMAL_MAP.matcher(new String(vmt, StandardCharsets.ISO_8859_1));
				if (!m.find()) {
					continue;
				}
				String path = m.group(2).replace('\\', '/').toLowerCase(Locale.ROOT);
				if (path.startsWith("materials/")) {
					path = path.substring("materials/".length());
				}
				if (path.endsWith(".vtf")) {
					path = path.substring(0, path.length() - 4);
				}
				byte[] vtf = files.read("materials/" + path + ".vtf");
				if (vtf == null) {
					continue;
				}
				try {
					normals = Vtf.decode(vtf, 256);
					chosen = material + " (ripples " + path + ")";
					break;
				} catch (IOException | RuntimeException e) {
					// Try the next one.
				}
			}
			if (normals != null) {
				break;
			}
		}
		used.put("water", chosen);
		if (normals == null) {
			return;
		}
		int s = WATER_SIZE;
		int[] strip = new int[s * s * WATER_FRAMES];
		float lx = 0.35F;
		float ly = 0.45F;
		float lz = 0.82F;
		float[] base = new float[3];
		for (int c = 0; c < 3; c++) {
			int shift = 16 - c * 8;
			base[c] = Math.min(1.0F, (WATER_COLOUR >> shift & 255) / (float) (WATER_TINT >> shift & 255));
		}
		for (int f = 0; f < WATER_FRAMES; f++) {
			float t = (float) f / WATER_FRAMES;
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) {
					float u = (float) x / s;
					float v = (float) y / s;
					float[] a = normal(normals, u + t, v + t * 0.5F);
					float[] b = normal(normals, u * 0.7F - t, v * 0.7F + t);
					float nx = a[0] + b[0];
					float ny = a[1] + b[1];
					float nz = a[2] + b[2];
					float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					float light = Math.max(0.0F, (nx * lx + ny * ly + nz * lz) / Math.max(length, 1.0E-4F));
					float shade = 0.55F + 0.6F * light * light;
					int r = Math.min(255, Math.round(base[0] * shade * 255));
					int g = Math.min(255, Math.round(base[1] * shade * 255));
					int bl = Math.min(255, Math.round(base[2] * shade * 255));
					strip[(f * s + y) * s + x] = 0xD0000000 | r << 16 | g << 8 | bl;
				}
			}
		}
		String meta = "{\"animation\": {\"frametime\": 3, \"interpolate\": true}}\n";
		for (String name : new String[] {"water_still", "water_flow"}) {
			writePng(folder.resolve(name + ".png"), strip, s, s * WATER_FRAMES);
			Files.writeString(folder.resolve(name + ".png.mcmeta"), meta);
		}
	}

	/** The normal at a wrapped position on a normal map, each part from -1 to 1. */
	private static float[] normal(Vtf map, float u, float v) {
		int x = Math.floorMod((int) Math.floor(u * map.width), map.width);
		int y = Math.floorMod((int) Math.floor(v * map.height), map.height);
		int p = map.pixels[y * map.width + x];
		return new float[] {(p >> 16 & 255) / 127.5F - 1.0F, (p >> 8 & 255) / 127.5F - 1.0F, (p & 255) / 127.5F - 1.0F};
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
		if (name.endsWith("_leaves") && count > texture.pixels().length * 97 / 100) {
			// A leaf texture with no gaps is a solid wall, not a foliage card.
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
			// Model textures are cut-up atlases for one prop (hay bales, crates), which
			// look wrong tiled over a block; only lists that ask for them get them.
			if (material.startsWith("models/") && !lower.startsWith("models/")) {
				continue;
			}
			if (regex.matcher(material).matches() && !(skipSpecial && SKIP.matcher(material).matches())) {
				out.add(material);
				if (out.size() >= 8) {
					break;
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------ made textures

	private static final int ANIMATION_SIZE = 64;
	private static final int ANIMATION_FRAMES = 32;

	/**
	 * Textures Half-Life 2 has nothing ready for, made from ones it does:
	 * clay, lava, fire and cactus.
	 */
	private static void madeTextures(GameFiles files, List<String> materials, Map<String, int[]> made, Path folder, Path packRoot,
			Map<String, String> used) throws IOException {
		// Clay: the mud floor, grey-blue as river clay.
		int[] mud = made.getOrDefault("mud", made.get("dirt"));
		if (mud != null) {
			writePng(folder.resolve("clay.png"), recolourTo(mud, 0x9298A2), SIZE);
			used.put("clay", (made.containsKey("mud") ? used.get("mud") : used.get("dirt")) + " (as clay)");
		}
		// Lava: the rock, molten, with dark crust drifting over it.
		int[] rock = made.getOrDefault("stone", made.get("cobblestone"));
		if (rock != null) {
			lava(rock, folder);
			used.put("lava", used.getOrDefault("stone", used.get("cobblestone")) + " (molten)");
		}
		used.put("fire", fire(files, materials, folder));
		// Cactus: bark, green, on a ridged column.
		int[] bark = made.getOrDefault("oak_log", made.get("spruce_log"));
		if (bark != null) {
			writePng(folder.resolve("cactus_side.png"), recolourTo(bark, 0x56743A), SIZE);
			writePng(folder.resolve("cactus_top.png"), recolourTo(bark, 0x6A8848), SIZE);
			writePng(folder.resolve("cactus_bottom.png"), recolourTo(bark, 0x4A6230), SIZE);
			Path models = packRoot.resolve("assets/minecraft/models/block");
			Files.createDirectories(models);
			Files.writeString(models.resolve("cactus.json"), CACTUS_MODEL);
			used.put("cactus", used.getOrDefault("oak_log", used.get("spruce_log")) + " (green)");
		}
	}

	/**
	 * A cactus as a ridged column instead of a box: a square column with a
	 * second one turned a quarter about it, so it has eight ribs.
	 */
	private static final String CACTUS_MODEL = """
			{
				"parent": "minecraft:block/block",
				"textures": {
					"particle": "minecraft:block/cactus_side",
					"side": "minecraft:block/cactus_side",
					"top": "minecraft:block/cactus_top",
					"bottom": "minecraft:block/cactus_bottom"
				},
				"elements": [
					{"from": [3, 0, 3], "to": [13, 16, 13], "faces": {
						"down": {"uv": [3, 3, 13, 13], "texture": "#bottom", "cullface": "down"},
						"up": {"uv": [3, 3, 13, 13], "texture": "#top", "cullface": "up"},
						"north": {"uv": [0, 0, 16, 16], "texture": "#side"},
						"south": {"uv": [0, 0, 16, 16], "texture": "#side"},
						"west": {"uv": [0, 0, 16, 16], "texture": "#side"},
						"east": {"uv": [0, 0, 16, 16], "texture": "#side"}}},
					{"from": [3.6, 0, 3.6], "to": [12.4, 15.98, 12.4],
						"rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": false}, "faces": {
						"up": {"uv": [4, 4, 12, 12], "texture": "#top"},
						"north": {"uv": [2, 0, 14, 16], "texture": "#side"},
						"south": {"uv": [2, 0, 14, 16], "texture": "#side"},
						"west": {"uv": [2, 0, 14, 16], "texture": "#side"},
						"east": {"uv": [2, 0, 14, 16], "texture": "#side"}}}
				]
			}
			""";

	/**
	 * Animated lava from a rock texture: its dark cracks and hollows glow
	 * yellow-white, the rest is orange melt under a dark crust that slowly
	 * drifts and breaks up.
	 */
	private static void lava(int[] rock, Path folder) throws IOException {
		int s = ANIMATION_SIZE;
		int[] small = resample(rock, SIZE, s);
		float mean = brightness(average(small));
		int[] strip = new int[s * s * ANIMATION_FRAMES];
		for (int f = 0; f < ANIMATION_FRAMES; f++) {
			double t = (double) f / ANIMATION_FRAMES;
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) {
					// The crust drifts one way, the glow beneath another; both wrap.
					int cx = Math.floorMod(x + Math.round((float) (t * s)), s);
					int cy = Math.floorMod(y + Math.round((float) (Math.sin(t * 2 * Math.PI) * 2)), s);
					float light = brightness(small[cy * s + cx]);
					double u = x * 2 * Math.PI / s;
					double v = y * 2 * Math.PI / s;
					double a = t * 2 * Math.PI;
					float wave = (float) (0.5 + 0.25 * Math.sin(2 * u + v + a) + 0.15 * Math.sin(3 * v - u - 2 * a)
							+ 0.1 * Math.sin(5 * u + 4 * v + a));
					float crack = mean <= 0.0F ? 0.5F : (mean - light) / mean;
					float heat = Math.max(0.0F, Math.min(1.0F, 0.45F + crack * 1.8F + (wave - 0.5F) * 0.9F));
					int r;
					int g;
					int b;
					if (heat < 0.35F) {
						// Crust: dark red-brown, a little of the rock's grain.
						float k = heat / 0.35F;
						r = Math.round(60 + 120 * k + 40 * light);
						g = Math.round(14 + 30 * k + 10 * light);
						b = Math.round(6 + 4 * k);
					} else {
						float k = (heat - 0.35F) / 0.65F;
						r = 255;
						g = Math.round(90 + 150 * k * k);
						b = Math.round(10 + 110 * k * k * k);
					}
					strip[(f * s + y) * s + x] = 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
				}
			}
		}
		String meta = "{\"animation\": {\"frametime\": 4, \"interpolate\": true}}\n";
		for (String name : new String[] {"lava_still", "lava_flow"}) {
			writePng(folder.resolve(name + ".png"), strip, s, s * ANIMATION_FRAMES);
			Files.writeString(folder.resolve(name + ".png.mcmeta"), meta);
		}
	}

	/** Half-Life 2's flame sprites, tried in order. */
	private static final String[] FIRE_PATTERNS = {"sprites/flamelet*", "sprites/fire*", "sprites/flame*", "effects/fire*"};

	/**
	 * Fire from Half-Life 2's flame sprites: each frame is one of them
	 * standing in the block, stretched and swayed a little as it burns.
	 * Returns the sprites used, or null when there are none (Minecraft's
	 * fire is kept).
	 */
	private static String fire(GameFiles files, List<String> materials, Path folder) throws IOException {
		List<RiggedModel.Texture> flames = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (String pattern : FIRE_PATTERNS) {
			for (String material : candidates(pattern, materials, false)) {
				RiggedModel.Texture texture = tryLoad(files, material, 256, true);
				if (texture != null && texture.width() >= 8 && texture.height() >= 8) {
					flames.add(texture);
					names.add(material);
				}
			}
			if (flames.size() >= 3) {
				break;
			}
		}
		if (flames.isEmpty()) {
			return null;
		}
		int s = ANIMATION_SIZE;
		int frames = ANIMATION_FRAMES;
		for (int which = 0; which < 3; which++) {
			int[] strip = new int[s * s * frames];
			for (int f = 0; f < frames; f++) {
				RiggedModel.Texture flame = flames.get((f / 4 + which) % flames.size());
				double t = (double) f / frames;
				float stretch = (float) (1.0 + 0.12 * Math.sin(t * 2 * Math.PI * 3 + which));
				float sway = (float) (0.06 * Math.sin(t * 2 * Math.PI * 2 + which * 2));
				for (int y = 0; y < s; y++) {
					float fy = (float) y / s;
					// Bottom of the sprite on the bottom of the block; the top stretches.
					float sv = 1.0F - (1.0F - fy) / stretch;
					if (sv < 0.0F) {
						continue;
					}
					for (int x = 0; x < s; x++) {
						float su = (float) x / s + sway * (1.0F - fy);
						if (su < 0.0F || su >= 1.0F) {
							continue;
						}
						int p = flame.pixels()[Math.min(flame.height() - 1, (int) (sv * flame.height())) * flame.width()
								+ Math.min(flame.width() - 1, (int) (su * flame.width()))];
						strip[(f * s + y) * s + x] = flamePixel(p);
					}
				}
			}
			String meta = "{\"animation\": {\"frametime\": 2, \"interpolate\": true}}\n";
			String name = which == 2 ? "campfire_fire" : "fire_" + which;
			writePng(folder.resolve(name + ".png"), strip, s, s * frames);
			Files.writeString(folder.resolve(name + ".png.mcmeta"), meta);
		}
		return String.join(" ", names);
	}

	/**
	 * A pixel of an additive flame sprite as a cut-out one: how bright it is
	 * decides whether it shows, and bright parts are lifted to flame colours.
	 */
	private static int flamePixel(int p) {
		int r = p >> 16 & 255;
		int g = p >> 8 & 255;
		int b = p & 255;
		int a = p >>> 24;
		float light = Math.max(r, Math.max(g, b)) / 255.0F * (a / 255.0F);
		if (light < 0.18F) {
			return 0;
		}
		float k = Math.min(1.0F, (light - 0.18F) / 0.6F);
		int or = 255;
		int og = Math.round(110 + 130 * k);
		int ob = Math.round(20 + 120 * k * k);
		// Keep a little of the sprite's own colour.
		or = (or * 3 + r) / 4;
		og = (og * 3 + g) / 4;
		ob = (ob * 3 + b) / 4;
		return 0xFF000000 | Math.min(255, or) << 16 | Math.min(255, og) << 8 | Math.min(255, ob);
	}

	/** A square image resampled to another size by averaging or repeating pixels. */
	private static int[] resample(int[] image, int from, int to) {
		int[] out = new int[to * to];
		int step = Math.max(1, from / to);
		for (int y = 0; y < to; y++) {
			for (int x = 0; x < to; x++) {
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int dy = 0; dy < step; dy++) {
					for (int dx = 0; dx < step; dx++) {
						int p = image[Math.min(from - 1, y * from / to + dy) * from + Math.min(from - 1, x * from / to + dx)];
						r += p >> 16 & 255;
						g += p >> 8 & 255;
						b += p & 255;
						n++;
					}
				}
				out[y * to + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}

	/**
	 * An image recoloured to {@code rgb}, keeping its light and shade: its
	 * average brightness becomes that colour. See-through pixels stay so.
	 */
	static int[] recolourTo(int[] image, int rgb) {
		double sum = 0;
		int count = 0;
		for (int p : image) {
			if ((p >>> 24) >= 128) {
				sum += (p >> 16 & 255) + (p >> 8 & 255) + (p & 255);
				count++;
			}
		}
		int[] out = image.clone();
		if (count == 0 || sum <= 0) {
			return out;
		}
		double mean = sum / count;
		for (int i = 0; i < out.length; i++) {
			int p = out[i];
			double light = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / mean;
			int r = (int) Math.min(255, Math.round((rgb >> 16 & 255) * light));
			int g = (int) Math.min(255, Math.round((rgb >> 8 & 255) * light));
			int b = (int) Math.min(255, Math.round((rgb & 255) * light));
			out[i] = (p & 0xFF000000) | r << 16 | g << 8 | b;
		}
		return out;
	}

	/** Colour of the sand, or 0 when unknown; set before the plants are made. */
	private static int sandColour;

	/**
	 * Desert plants, crops and water plants from the grass sprites,
	 * recoloured: dry grass and dead bushes the pale straw of the sand
	 * around them, ripe wheat gold, seagrass and kelp a murky green.
	 */
	private static void moreSprites(RiggedModel.Texture sheet, List<Sprite> green, Path folder, Map<String, String> used, String chosen)
			throws IOException {
		int sand = sandColour != 0 ? sandColour : 0xA89A80;
		int straw = mix(sand, 0xC2A872, 0.6F);
		int dead = mix(sand, 0x6E5A40, 0.65F);
		writePng(folder.resolve("short_dry_grass.png"), recolourTo(spriteImage(sheet, pickShape(green, "wide", 2), SIZE, SIZE), straw),
				SIZE);
		writePng(folder.resolve("tall_dry_grass.png"), recolourTo(spriteImage(sheet, pickShape(green, "tall", 1), SIZE, SIZE), straw),
				SIZE);
		writePng(folder.resolve("dead_bush.png"), recolourTo(spriteImage(sheet, pickShape(green, "any", 3), SIZE, SIZE), dead), SIZE);
		used.put("dead_bush", chosen + " (grass, dry)");
		used.put("short_dry_grass", chosen + " (grass, dry)");

		// Crops grow from a short tuft to a full one.
		int[] wheat = spriteImage(sheet, pickShape(green, "tall", 0), SIZE, SIZE);
		for (int stage = 0; stage < 8; stage++) {
			int[] image = shorter(wheat, 0.25F + 0.75F * stage / 7.0F);
			if (stage >= 5) {
				image = recolourTo(image, mix(0x7A8A40, 0xD8B860, (stage - 4) / 3.0F));
			}
			writePng(folder.resolve("wheat_stage" + stage + ".png"), image, SIZE);
		}
		String[][] roots = {{"carrots", "4"}, {"potatoes", "5"}, {"beetroots", "6"}};
		for (String[] crop : roots) {
			int[] tuft = spriteImage(sheet, pickShape(green, "wide", Integer.parseInt(crop[1])), SIZE, SIZE);
			for (int stage = 0; stage < 4; stage++) {
				writePng(folder.resolve(crop[0] + "_stage" + stage + ".png"), shorter(tuft, 0.3F + 0.7F * stage / 3.0F), SIZE);
			}
		}
		used.put("crops", chosen + " (grass tufts)");

		int weed = 0x4E7A52;
		writePng(folder.resolve("seagrass.png"), recolourTo(spriteImage(sheet, pickShape(green, "wide", 7), SIZE, SIZE), weed), SIZE);
		int[] tall = recolourTo(spriteImage(sheet, pickShape(green, "tall", 2), SIZE, SIZE * 2), weed);
		writePng(folder.resolve("tall_seagrass_top.png"), java.util.Arrays.copyOfRange(tall, 0, SIZE * SIZE), SIZE);
		writePng(folder.resolve("tall_seagrass_bottom.png"), java.util.Arrays.copyOfRange(tall, SIZE * SIZE, SIZE * SIZE * 2), SIZE);
		int kelp = 0x5A6E3A;
		writePng(folder.resolve("kelp.png"), recolourTo(spriteImage(sheet, pickShape(green, "tall", 3), SIZE, SIZE), kelp), SIZE);
		writePng(folder.resolve("kelp_plant.png"), recolourTo(spriteImage(sheet, pickShape(green, "tall", 4), SIZE, SIZE), kelp), SIZE);
		used.put("seagrass", chosen + " (grass, green-brown)");
	}

	/** A plant sprite squashed down to {@code fraction} of its height, still standing on the bottom edge. */
	private static int[] shorter(int[] image, float fraction) {
		int[] out = new int[SIZE * SIZE];
		int h = Math.max(1, Math.round(SIZE * fraction));
		for (int y = SIZE - h; y < SIZE; y++) {
			int from = Math.min(SIZE - 1, (y - (SIZE - h)) * SIZE / h);
			System.arraycopy(image, from * SIZE, out, y * SIZE, SIZE);
		}
		return out;
	}

	/** {@code a} and {@code b} mixed, {@code k} of the way to {@code b}. */
	private static int mix(int a, int b, float k) {
		int out = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			float c = (a >> shift & 255) * (1.0F - k) + (b >> shift & 255) * k;
			out |= Math.max(0, Math.min(255, Math.round(c))) << shift;
		}
		return out;
	}

	// ------------------------------------------------------------ plants

	/** Plant textures and the kind of sprite each takes. */
	private static final String[][] GREEN_PLANTS = {
		{"short_grass", "wide"}, {"fern", "any"}, {"tall_grass", "tall"}, {"large_fern", "any"},
		{"bush", "wide"}, {"sugar_cane", "tall"},
	};
	/**
	 * Flowers. Half-Life 2 has no flowers, so they become its weeds and grass
	 * tufts, untinted, so meadows look like its overgrown fields.
	 */
	private static final String[] FLOWERS = {"poppy", "red_tulip", "dandelion", "cornflower", "blue_orchid", "oxeye_daisy",
		"azure_bluet", "lily_of_the_valley", "white_tulip", "allium", "pink_tulip", "orange_tulip", "torchflower",
		"closed_eyeblossom", "open_eyeblossom"};
	/** Two-block flowers, drawn as one tall sprite split in two. */
	private static final String[] TALL_FLOWERS = {"rose_bush", "lilac", "peony", "sunflower"};
	/** Plant textures Minecraft tints with the grass colour. */
	private static final java.util.Set<String> GRASS_TINTED = java.util.Set.of("short_grass", "fern", "tall_grass_top",
		"tall_grass_bottom", "large_fern_top", "large_fern_bottom", "sugar_cane", "bush");

	/** One sprite on a sheet: its bounds and average colour. */
	private record Sprite(int x, int y, int w, int h, int pixels, float r, float g, float b) {
		/**
		 * What the sprite looks like: green (grass and leaves, including the
		 * dry yellow-green grass of Half-Life 2's coast), a flower colour, or
		 * other (stones, dirt, bare twigs).
		 */
		String colour() {
			float max = Math.max(r, Math.max(g, b));
			float min = Math.min(r, Math.min(g, b));
			float saturation = max <= 0.0F ? 0.0F : (max - min) / max;
			float hue;
			if (max - min < 1.0E-4F) {
				hue = 0.0F;
			} else if (max == r) {
				hue = 60.0F * (((g - b) / (max - min) + 6.0F) % 6.0F);
			} else if (max == g) {
				hue = 60.0F * ((b - r) / (max - min) + 2.0F);
			} else {
				hue = 60.0F * ((r - g) / (max - min) + 4.0F);
			}
			if (saturation < 0.2F) {
				return max > 0.7F ? "white" : "other";
			}
			if (hue >= 45.0F && hue < 170.0F) {
				// Bright, strongly yellow sprites are flowers; duller ones grass.
				return hue < 62.0F && saturation > 0.6F && max > 0.7F ? "yellow" : "green";
			}
			if (saturation < 0.35F) {
				return hue < 45.0F ? "brown" : "other";
			}
			if (hue < 18.0F || hue >= 330.0F) {
				return "red";
			}
			if (hue < 45.0F) {
				return max > 0.6F ? "yellow" : "brown";
			}
			if (hue >= 185.0F && hue < 260.0F) {
				return "blue";
			}
			return hue >= 260.0F ? "purple" : "other";
		}
	}

	/**
	 * Cuts grass, ferns and flowers out of a sprite sheet: every separate
	 * patch of opaque pixels is a sprite, and sprites are matched to plants
	 * by colour and shape.
	 */
	private static void plants(GameFiles files, String[] patterns, List<String> materials, Path folder, Map<String, String> used,
			int[] ground, Path packRoot) throws IOException {
		groundColour = ground == null ? 0 : average(ground);
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
		used.put("plants", chosen + " (" + sprites.size() + " sprites: " + green.size() + " green, others " + summary(coloured) + ")");
		if (green.isEmpty()) {
			// No green sprites: use the biggest of any kind for grass.
			green.addAll(sprites);
		}
		if (green.isEmpty()) {
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
		// More tufts for short grass and ferns, so a meadow is not one sprite
		// over and over. They are picked at random by grass_tufts below.
		List<Sprite> wide = new ArrayList<>();
		for (Sprite sprite : green) {
			if (sprite.w >= sprite.h * 0.8F) {
				wide.add(sprite);
			}
		}
		if (wide.isEmpty()) {
			wide = green;
		}
		for (int v = 1; v < TUFTS; v++) {
			Sprite pick = wide.get((v * 3) % wide.size());
			writePlant(folder, "short_grass_" + v, spriteImage(sheet, pick, SIZE, SIZE));
			Sprite fern = green.get((v * 5 + 1) % green.size());
			writePlant(folder, "fern_" + v, spriteImage(sheet, fern, SIZE, SIZE));
		}
		grassTufts(packRoot);
		moreSprites(sheet, green, folder, used, chosen);
		// Flowers: the sprites after the ones grass took, so they differ from it.
		int flowerIndex = GREEN_PLANTS.length;
		for (String flower : FLOWERS) {
			Sprite pick = pickShape(green, "any", flowerIndex++);
			writePlant(folder, flower, spriteImage(sheet, pick, SIZE, SIZE));
			used.put(flower, chosen + " (sprite at " + pick.x + "," + pick.y + ")");
		}
		for (String flower : TALL_FLOWERS) {
			Sprite pick = pickShape(green, "tall", flowerIndex++);
			int[] tall = spriteImage(sheet, pick, SIZE, SIZE * 2);
			writePlant(folder, flower + "_top", java.util.Arrays.copyOfRange(tall, 0, SIZE * SIZE));
			writePlant(folder, flower + "_bottom", java.util.Arrays.copyOfRange(tall, SIZE * SIZE, SIZE * SIZE * 2));
			used.put(flower, chosen + " (sprite at " + pick.x + "," + pick.y + ")");
		}
		// The sunflower's head is drawn separately; leave it out.
		writePng(folder.resolve("sunflower_front.png"), new int[SIZE * SIZE], SIZE);
		writePng(folder.resolve("sunflower_back.png"), new int[SIZE * SIZE], SIZE);
	}

	private static String summary(Map<String, List<Sprite>> coloured) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, List<Sprite>> entry : coloured.entrySet()) {
			out.append(out.isEmpty() ? "" : ", ").append(entry.getValue().size()).append(' ').append(entry.getKey());
		}
		return out.isEmpty() ? "none" : out.toString();
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
		String base = name.replaceAll("_\\d+$", "");
		if (GRASS_TINTED.contains(base)) {
			blendWithGround(image);
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

	/** How many textures short grass and ferns each get. */
	private static final int TUFTS = 4;
	/** Average colour of the grass block top, untinted, or 0 when unknown; set while plants are made. */
	private static int groundColour;

	/** Average colour of the opaque pixels of an image. */
	static int average(int[] image) {
		long r = 0;
		long g = 0;
		long b = 0;
		int n = 0;
		for (int p : image) {
			if ((p >>> 24) >= 128) {
				r += p >> 16 & 255;
				g += p >> 8 & 255;
				b += p & 255;
				n++;
			}
		}
		return n == 0 ? 0 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	/**
	 * Recolours a grass sprite with the colour of the ground it grows from,
	 * keeping only the sprite's own light and shade, and darker towards the
	 * root, so a tuft rises out of the grass floor in the same green instead
	 * of standing out from it.
	 * <p>
	 * The ground texture is kept with Minecraft's grass colour taken out (so
	 * the biome colour can go back in), and the sprite has it taken out after
	 * this; so the colour put in here is the ground's with the grass colour
	 * put back, or the colour would come out of it twice and the grass pale.
	 */
	static void blendWithGround(int[] image) {
		if (groundColour == 0) {
			return;
		}
		int[] ground = {groundColour};
		retint(ground, GRASS_TINT);
		int colour = ground[0];
		int top = SIZE;
		int bottom = -1;
		float total = 0.0F;
		int count = 0;
		for (int i = 0; i < image.length; i++) {
			if ((image[i] >>> 24) >= 128) {
				top = Math.min(top, i / SIZE);
				bottom = Math.max(bottom, i / SIZE);
				total += brightness(image[i]);
				count++;
			}
		}
		if (bottom < top || count == 0) {
			return;
		}
		float mean = Math.max(0.02F, total / count);
		for (int i = 0; i < image.length; i++) {
			int p = image[i];
			if ((p >>> 24) < 128) {
				continue;
			}
			// 0 at the tips, 1 at the root.
			float down = bottom == top ? 1.0F : (i / SIZE - top) / (float) (bottom - top);
			float light = Math.max(0.6F, Math.min(1.3F, brightness(p) / mean));
			float dark = 0.95F - 0.3F * down * down;
			int out = p & 0xFF000000;
			for (int shift = 0; shift <= 16; shift += 8) {
				float v = (colour >> shift & 255) * light * dark;
				out |= Math.max(0, Math.min(255, Math.round(v))) << shift;
			}
			image[i] = out;
		}
	}

	/**
	 * Short grass and ferns as Half-Life 2 style tufts: three cards in a star
	 * rather than Minecraft's crossed pair, each block picking one of several
	 * sprites, sizes and turnings at random, and most blocks left bare so the grass
	 * grows in scattered clumps.
	 */
	private static void grassTufts(Path packRoot) throws IOException {
		Path models = packRoot.resolve("assets/mcsource/models/block");
		Path states = packRoot.resolve("assets/minecraft/blockstates");
		Files.createDirectories(models);
		Files.createDirectories(states);
		Files.writeString(models.resolve("no_plant.json"), "{\"textures\": {\"particle\": \"minecraft:block/short_grass\"}, \"elements\": []}");
		for (String plant : new String[] {"short_grass", "fern"}) {
			StringBuilder variants = new StringBuilder();
			for (int v = 0; v < TUFTS; v++) {
				String texture = "minecraft:block/" + plant + (v == 0 ? "" : "_" + v);
				for (int size = 0; size < 2; size++) {
					String name = plant + "_tuft_" + v + "_" + size;
					Files.writeString(models.resolve(name + ".json"), tuftModel(texture, size == 0 ? 0 : 3));
					for (int turn = 0; turn < 360; turn += 90) {
						variants.append(variants.isEmpty() ? "" : ",\n\t\t").append("{\"model\": \"mcsource:block/").append(name)
								.append("\", \"y\": ").append(turn).append("}");
					}
				}
			}
			// Five blocks in six are left bare, so grass grows in scattered clumps.
			variants.append(",\n\t\t{\"model\": \"mcsource:block/no_plant\", \"weight\": ").append(TUFTS * 2 * 4 * 5).append("}");
			Files.writeString(states.resolve(plant + ".json"), "{\"variants\": {\"\": [\n\t\t" + variants + "\n\t]}}");
		}
		// Flat litter lying on the ground (fallen leaves, petals, wildflowers)
		// has nothing like it in Half-Life 2 and shows up as bright squares on
		// the smoothed ground, so it is not drawn.
		for (String litter : GROUND_LITTER) {
			Files.writeString(states.resolve(litter + ".json"), "{\"variants\": {\"\": {\"model\": \"mcsource:block/no_plant\"}}}");
		}
	}

	private static final String[] GROUND_LITTER = {"leaf_litter", "pink_petals", "wildflowers"};

	/** Three two-sided cards in a star, {@code grow} sixteenths bigger than the smallest tuft. */
	private static String tuftModel(String texture, int grow) {
		// Low tufts, as wide as they are tall so the sprite keeps its shape:
		// Half-Life 2's grass sprites are knee high at most.
		int top = 10 + grow;
		int low = 8 - top / 2;
		int high = low + top;
		return "{\"ambientocclusion\": false, \"textures\": {\"particle\": \"" + texture + "\", \"all\": \"" + texture + "\"},\n"
				+ "\"elements\": [\n" + plantCard(low, 0, 8, high, top, 8, -22.5F) + ",\n" + plantCard(low, 0, 8, high, top, 8, 45.0F) + ",\n"
				+ plantCard(8, 0, low, 8, top, high, 22.5F) + "\n]}";
	}

	/** One two-sided, grass-tinted card turned about the block's middle. */
	private static String plantCard(int x1, int y1, int z1, int x2, int y2, int z2, float angle) {
		String a = x1 == x2 ? "east" : "north";
		String b = x1 == x2 ? "west" : "south";
		String face = "{\"uv\": [0, 0, 16, 16], \"texture\": \"#all\", \"tintindex\": 0}";
		return "{\"from\": [" + x1 + ", " + y1 + ", " + z1 + "], \"to\": [" + x2 + ", " + y2 + ", " + z2 + "], \"shade\": false, "
				+ "\"rotation\": {\"origin\": [8, 8, 8], \"axis\": \"y\", \"angle\": " + angle + ", \"rescale\": false}, "
				+ "\"faces\": {\"" + a + "\": " + face + ", \"" + b + "\": " + face + "}}";
	}

	/** Ore textures to make: name, the rock it sits in, and the colour of its mineral. */
	private static final String[][] ORES = {
		{"coal_ore", "stone", "262422"}, {"deepslate_coal_ore", "deepslate", "1c1a19"},
		{"iron_ore", "stone", "b08a6e"}, {"deepslate_iron_ore", "deepslate", "a07c62"},
		{"copper_ore", "stone", "9c6040"}, {"deepslate_copper_ore", "deepslate", "8c5638"},
		{"gold_ore", "stone", "c8a540"}, {"deepslate_gold_ore", "deepslate", "b89438"},
		{"redstone_ore", "stone", "8a2a20"}, {"deepslate_redstone_ore", "deepslate", "7a241c"},
		{"lapis_ore", "stone", "34508a"}, {"deepslate_lapis_ore", "deepslate", "2e467a"},
		{"diamond_ore", "stone", "6ab8b0"}, {"deepslate_diamond_ore", "deepslate", "5aa49c"},
		{"emerald_ore", "stone", "3c8a50"}, {"deepslate_emerald_ore", "deepslate", "347a46"},
	};

	/**
	 * Makes an ore texture from the rock it sits in: a few soft veins of the
	 * mineral's colour worked into the rock's own light and shade, instead of
	 * Minecraft's bright pixel speckles, so ore reads as part of a rock face.
	 */
	private static void ore(Map<String, int[]> made, String name, String host, int colour, Path folder, Map<String, String> used)
			throws IOException {
		int[] rock = made.get(host);
		if (rock == null) {
			return;
		}
		float mean = Math.max(0.05F, brightness(average(rock)));
		java.util.Random random = new java.util.Random(name.hashCode());
		int veins = 5;
		float[] cx = new float[veins];
		float[] cy = new float[veins];
		float[] rx = new float[veins];
		float[] ry = new float[veins];
		float[] turn = new float[veins];
		for (int k = 0; k < veins; k++) {
			cx[k] = random.nextFloat() * SIZE;
			cy[k] = random.nextFloat() * SIZE;
			rx[k] = SIZE * (0.05F + 0.05F * random.nextFloat());
			ry[k] = rx[k] * (0.3F + 0.3F * random.nextFloat());
			turn[k] = random.nextFloat() * (float) Math.PI;
		}
		int[] out = new int[rock.length];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int p = rock[y * SIZE + x];
				float w = 0.0F;
				for (int k = 0; k < veins; k++) {
					// Wrapped, so the texture still tiles.
					float dx = x - cx[k];
					float dy = y - cy[k];
					dx -= SIZE * Math.round(dx / SIZE);
					dy -= SIZE * Math.round(dy / SIZE);
					float a = (float) (dx * Math.cos(turn[k]) + dy * Math.sin(turn[k])) / rx[k];
					float b = (float) (-dx * Math.sin(turn[k]) + dy * Math.cos(turn[k])) / ry[k];
					float d = a * a + b * b;
					w = Math.max(w, Math.max(0.0F, 1.0F - d));
				}
				float light = brightness(p) / mean;
				// Broken up by the rock's own grain.
				w = Math.min(0.8F, w * 1.6F * Math.min(1.0F, light));
				int o = 0xFF000000;
				for (int shift = 0; shift <= 16; shift += 8) {
					float c = (p >> shift & 255) * (1.0F - w) + (colour >> shift & 255) * Math.min(1.4F, light) * w;
					o |= Math.max(0, Math.min(255, Math.round(c))) << shift;
				}
				out[y * SIZE + x] = o;
			}
		}
		writePng(folder.resolve(name + ".png"), out, SIZE);
		used.put(name, used.get(host) + " (with veins)");
	}

	/**
	 * Makes a mossy texture from a plain one: the grass floor grows into its
	 * cracks and in soft patches, keeping the stone's own light and shade.
	 */
	private static void mossy(Map<String, int[]> made, String from, String to, Path folder, Map<String, String> used) throws IOException {
		int[] stone = made.get(from);
		int[] grass = made.get("grass_block_top");
		if (stone == null || grass == null) {
			return;
		}
		int[] moss = grass.clone();
		retint(moss, GRASS_TINT);
		float mean = brightness(average(stone));
		int[] out = new int[stone.length];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int p = stone[y * SIZE + x];
				float light = brightness(p);
				// Patches that repeat with the texture, so blocks still join up.
				double u = x * 2 * Math.PI / SIZE;
				double v = y * 2 * Math.PI / SIZE;
				float patch = (float) (0.5 + 0.22 * Math.sin(3 * u + v + 0.7) + 0.18 * Math.sin(2 * u - 4 * v + 2.1)
						+ 0.1 * Math.sin(7 * u + 5 * v + 4.0));
				float crack = mean <= 0.0F ? 0.0F : (mean - light) / mean;
				float w = Math.max(0.0F, Math.min(0.85F, (patch - 0.55F) * 3.0F + crack * 1.6F));
				int m = moss[y * SIZE + x];
				float shade = mean <= 0.0F ? 1.0F : Math.min(1.3F, light / mean * 0.8F + 0.2F);
				int o = 0xFF000000;
				for (int shift = 0; shift <= 16; shift += 8) {
					float c = (p >> shift & 255) * (1.0F - w) + (m >> shift & 255) * shade * w;
					o |= Math.max(0, Math.min(255, Math.round(c))) << shift;
				}
				out[y * SIZE + x] = o;
			}
		}
		writePng(folder.resolve(to + ".png"), out, SIZE);
		used.put(to, used.get(from) + " (with moss from " + used.get("grass_block_top") + ")");
	}

	private static float brightness(int p) {
		return ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 765.0F;
	}

	/**
	 * Gives the see-through pixels of a foliage card a darker leaf colour.
	 * With Minecraft's leaves drawn solid (fast graphics), they show as dense
	 * shaded leaves instead of black.
	 */
	private static void fillGaps(int[] image) {
		long r = 0;
		long g = 0;
		long b = 0;
		int count = 0;
		for (int p : image) {
			if ((p >>> 24) >= 128) {
				r += p >> 16 & 255;
				g += p >> 8 & 255;
				b += p & 255;
				count++;
			}
		}
		if (count == 0) {
			return;
		}
		int fill = (int) (r / count * 6 / 10) << 16 | (int) (g / count * 6 / 10) << 8 | (int) (b / count * 6 / 10);
		for (int i = 0; i < image.length; i++) {
			if ((image[i] >>> 24) < 128) {
				image[i] = fill;
			}
		}
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
		writePng(file, argb, size, size);
	}

	/** Writes ARGB pixels as an 8-bit RGBA PNG of any size. */
	public static void writePng(Path file, int[] argb, int width, int height) throws IOException {
		byte[] raw = new byte[height * (width * 4 + 1)];
		int at = 0;
		for (int y = 0; y < height; y++) {
			raw[at++] = 0; // no filter
			for (int x = 0; x < width; x++) {
				int p = argb[y * width + x];
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
		header.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
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
