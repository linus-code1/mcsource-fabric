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
			sandstone = nature/*sandstone* *sandstone* nature/sandfloor0* nature/*sand* nature/rockwall00*
			sandstone_top = nature/*sandstone* *sandstone* nature/sandfloor0* nature/*sand* nature/rockfloor00*
			sandstone_bottom = nature/*sandstone* *sandstone* nature/sandfloor0* nature/*sand* nature/rockfloor00*
			snow = nature/snowfloor00* nature/*snow* *snow*
			snow_block = nature/snowfloor00* nature/*snow* *snow*
			powder_snow = nature/snowfloor00* nature/*snow* *snow*
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
			white_wool = *fabric* *cloth* plaster/*
			stone_bricks_wall = stone/stonewall0*
			calcite = stone/*white* plaster/* concrete/*
			dripstone_block = nature/rock* stone/*
			moss_block = nature/*moss* *moss* nature/*grass*
			moss_carpet = nature/*moss* *moss* nature/*grass*
			# Vines are Half-Life 2's ivy where it has some, else a foliage card.
			vine = models/props_foliage/*ivy* nature/*ivy* *ivy* *vine* models/props_foliage/*leaves* models/props_foliage/*leaf*
			rooted_dirt = nature/dirtfloor006a nature/*dirt* *dirt*
			iron_bars = metal/*grate* metal/*fence* *grate*
			# A door is one material over both of its blocks: its top half on the upper
			# block and its bottom half on the lower, so it isn't repeated on each.
			iron_door = metal/metaldoor0* metal/*door* metal/*
			oak_door = wood/*door* wood/woodwall0*
			spruce_door = wood/*door* wood/woodwall0*
			birch_door = wood/*door* wood/woodwall0*
			jungle_door = wood/*door* wood/woodwall0*
			acacia_door = wood/*door* wood/woodwall0*
			dark_oak_door = wood/*door* wood/woodwall0*
			mangrove_door = wood/*door* wood/woodwall0*
			cherry_door = wood/*door* wood/woodwall0*
			bamboo_door = wood/*door* wood/woodwall0*
			crimson_door = wood/*door* wood/woodwall0*
			warped_door = wood/*door* wood/woodwall0*
			pale_oak_door = wood/*door* wood/woodwall0*
			poplar_door = wood/*door* wood/woodwall0*
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
			# Badlands are cliffs of rock: terracotta is Half-Life 2's rock, and each
			# coloured terracotta is that rock tinted to the block's own colour.
			terracotta = nature/rockwall004a nature/rockwall00* nature/*cliff* nature/*rock* *rock*
			clay = nature/mudfloor* nature/*mud* nature/dirtfloor003a nature/*dirt* *mud*
			packed_mud = nature/mudfloor* nature/dirtfloor0* nature/*dirt*
			magma = nature/*lava* nature/rockfloor0* *rock*
			glass = glass/glasswindow0* glass/*window* glass/* *glass*
			glass_pane_top = glass/glasswindow0* glass/*window* glass/* *glass*
			tinted_glass = glass/*dirty* glass/*window* glass/*
			ladder = metal/*ladder* *ladder* wood/*ladder*
			iron_trapdoor = metal/*grate* metal/metalfloor0* metal/*
			oak_trapdoor = wood/*door* wood/woodwall0* wood/*
			spruce_trapdoor = wood/*door* wood/woodwall0* wood/*
			birch_trapdoor = wood/*door* wood/woodwall0* wood/*
			jungle_trapdoor = wood/*door* wood/woodwall0* wood/*
			acacia_trapdoor = wood/*door* wood/woodwall0* wood/*
			dark_oak_trapdoor = wood/*door* wood/woodwall0* wood/*
			mangrove_trapdoor = wood/*door* wood/woodwall0* wood/*
			blue_ice = nature/ice* */ice0* nature/*frozen*
			frosted_ice_0 = nature/ice* */ice0* nature/*frozen*
			frosted_ice_1 = nature/ice* */ice0* nature/*frozen*
			frosted_ice_2 = nature/ice* */ice0* nature/*frozen*
			frosted_ice_3 = nature/ice* */ice0* nature/*frozen*
			blackstone_top = nature/rockwall_cave* stone/*dark* *rock*
			polished_blackstone = concrete/concretefloor0* stone/*dark* stone/*
			polished_blackstone_bricks = stone/stonewall0* brick/*
			cracked_polished_blackstone_bricks = stone/*crack* stone/stonewall0*
			gilded_blackstone = nature/rockwall_cave* stone/*dark* *rock*
			polished_basalt_side = nature/rockwall0* *rock*
			polished_basalt_top = nature/rockfloor0* *rock*
			smooth_basalt = nature/rockfloor0* *rock*
			quartz_block_bottom = tile/tilefloor0* plaster/plasterwall0* tile/*
			quartz_bricks = tile/tilewall0* tile/* plaster/*
			quartz_pillar_side = plaster/plasterwall0* tile/* plaster/*
			quartz_pillar_top = tile/tilefloor0* plaster/plasterwall0* tile/*
			red_sandstone_top = stone/*sand* *sandstone* nature/sandfloor0*
			red_sandstone_bottom = stone/*sand* *sandstone* nature/sandfloor0*
			cut_red_sandstone = stone/*sand* *sandstone* concrete/concretewall0*
			cracked_deepslate_bricks = stone/*crack* stone/stonewall0*
			cracked_deepslate_tiles = tile/*crack* stone/*crack* tile/tilefloor0*
			cracked_nether_bricks = brick/*crack* brick/brickwall0*
			crying_obsidian = nature/*obsidian* stone/*black* nature/rockwall_cave*
			smooth_stone_slab_side = concrete/concretefloor001a concrete/concretefloor0* concrete/*
			melon_side = models/props_junk/watermelon* *melon*
			melon_top = models/props_junk/watermelon* *melon*
			bone_block_side = plaster/plasterwall0* stone/*white*
			bone_block_top = plaster/plasterwall0* stone/*white*
			suspicious_sand_0 = nature/sandfloor009a nature/sandfloor0* nature/*sand*
			suspicious_gravel_0 = nature/gravelfloor002a nature/gravelfloor00* nature/*gravel*
			flower_pot = models/props_junk/terracotta* *pot* plaster/*
			lantern = metal/*lamp* metal/*
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

	/**
	 * The built-in list for Half-Life: Source, whose textures are the first
	 * game's, kept under {@code halflife/}. Every block has a material; only
	 * the plants keep Minecraft's, as the game has no grass or flower sprites.
	 */
	public static final String HALF_LIFE_SOURCE = """
			# The mod's list of Half-Life: Source materials for Minecraft block textures.
			# Half-Life's textures all sit in halflife/. It has no grass or flower sprites,
			# so plants keep Minecraft's, recoloured to match the ground.
			water = halflife/water4b halflife/scrollwater1 liquids/*
			grass_block_top = halflife/crossfire_outgrnd halflife/out_grnd1 halflife/out_grnd2
			dirt = halflife/out_dirt1 halflife/out_grnd2
			coarse_dirt = halflife/out_grnd2 halflife/out_dirt1
			rooted_dirt = halflife/out_dirt1
			podzol_top = halflife/out_grnd1 halflife/out_dirt1
			mud = halflife/out_mud1 halflife/c2a4_mud
			farmland = halflife/out_dirt1
			farmland_moist = halflife/out_mud1 halflife/out_dirt1
			stone = halflife/out_rk3 halflife/cretecrumble halflife/tnnl_rck1
			andesite = halflife/c2a4_brd1 halflife/out_rk3
			diorite = halflife/cretecrumble2 halflife/fifties_ceil01
			granite = halflife/c2a4_brd1r halflife/c2a4_mud
			tuff = halflife/cretecrumble halflife/out_rk3
			deepslate = halflife/tnnl_rck1 halflife/out_rk3
			deepslate_top = halflife/tnnl_rck1 halflife/out_rk3
			cobbled_deepslate = halflife/tnnl_swr1 halflife/tnnl_rck1
			bedrock = halflife/tnnl_rck1
			cobblestone = halflife/out_pav3 halflife/out_pave1 halflife/out_rk3
			smooth_stone = halflife/crete2_flr01 halflife/crete2_flr02
			stone_bricks = halflife/c1a3wall01 halflife/crete3_wall01
			cracked_stone_bricks = halflife/crete4_wall01 halflife/c1a3wall01
			chiseled_stone_bricks = halflife/crete2_wall01 halflife/c1a3wall01
			stone_bricks_wall = halflife/c1a3wall01
			bricks = halflife/fifties_w12 halflife/fifties_w13 halflife/out_wall1a
			gravel = halflife/out_gravel halflife/out_grvl2 halflife/c3a1a_gravel
			suspicious_gravel_0 = halflife/out_gravel halflife/out_grvl2
			sand = halflife/out_snd1 halflife/c2a5_sand
			suspicious_sand_0 = halflife/out_snd1 halflife/c2a5_sand
			red_sand = halflife/c2a5b_sand halflife/out_snd2
			sandstone = halflife/c2a5w_sand halflife/out_snd2
			sandstone_top = halflife/out_snd2 halflife/c2a5w_sand
			sandstone_bottom = halflife/out_snd2 halflife/c2a5w_sand
			smooth_sandstone = halflife/out_snd2 halflife/c2a5w_sand
			cut_sandstone = halflife/out_con halflife/c2a5w_sand
			chiseled_sandstone = halflife/out_con halflife/c2a5w_sand
			red_sandstone = halflife/c2a4_mud2 halflife/c2a5b_sand
			red_sandstone_top = halflife/c2a5b_sand
			red_sandstone_bottom = halflife/c2a5b_sand
			cut_red_sandstone = halflife/c2a4_mud2 halflife/c2a5b_sand
			snow = halflife/fifties_ceil01 halflife/frost
			snow_block = halflife/fifties_ceil01 halflife/frost
			powder_snow = halflife/fifties_ceil01 halflife/frost
			ice = halflife/frost halflife/frostslick
			packed_ice = halflife/frostslick halflife/frost
			blue_ice = halflife/frostslick2 halflife/frostslick
			frosted_ice_0 = halflife/frost
			frosted_ice_1 = halflife/frost
			frosted_ice_2 = halflife/frost
			frosted_ice_3 = halflife/frost
			oak_planks = halflife/out_wd halflife/trrm_wood
			spruce_planks = halflife/trrm_wood2 halflife/trrm_wood
			birch_planks = halflife/c1a1c_w1 halflife/out_wd
			jungle_planks = halflife/trrm_pan1 halflife/out_wd
			acacia_planks = halflife/crate01 halflife/out_wd
			dark_oak_planks = halflife/trrm_wood2
			mangrove_planks = halflife/trrm_wood
			cherry_planks = halflife/trrm_wood
			pale_oak_planks = halflife/c1a1c_w1 halflife/out_wd
			bamboo_planks = halflife/out_slat01 halflife/out_wd
			oak_log = halflife/trrm_wood2 halflife/out_wd
			spruce_log = halflife/trrm_wood2 halflife/out_wd
			birch_log = halflife/trrm_wood2 halflife/out_wd
			jungle_log = halflife/trrm_wood2 halflife/out_wd
			acacia_log = halflife/trrm_wood2 halflife/out_wd
			dark_oak_log = halflife/trrm_wood2 halflife/out_wd
			mangrove_log = halflife/trrm_wood2 halflife/out_wd
			cherry_log = halflife/trrm_wood2 halflife/out_wd
			pale_oak_log = halflife/trrm_wood2 halflife/out_wd
			oak_log_top = halflife/out_wd
			spruce_log_top = halflife/out_wd
			birch_log_top = halflife/out_wd
			jungle_log_top = halflife/out_wd
			acacia_log_top = halflife/out_wd
			dark_oak_log_top = halflife/out_wd
			mangrove_log_top = halflife/out_wd
			cherry_log_top = halflife/out_wd
			pale_oak_log_top = halflife/out_wd
			stripped_oak_log = halflife/out_wd
			stripped_oak_log_top = halflife/out_wd
			stripped_spruce_log = halflife/trrm_wood2
			stripped_spruce_log_top = halflife/out_wd
			stripped_birch_log = halflife/c1a1c_w1
			stripped_birch_log_top = halflife/out_wd
			stripped_jungle_log = halflife/out_wd
			stripped_jungle_log_top = halflife/out_wd
			stripped_acacia_log = halflife/out_wd
			stripped_acacia_log_top = halflife/out_wd
			stripped_dark_oak_log = halflife/trrm_wood2
			stripped_dark_oak_log_top = halflife/out_wd
			stripped_mangrove_log = halflife/trrm_wood
			stripped_mangrove_log_top = halflife/out_wd
			stripped_cherry_log = halflife/trrm_wood
			stripped_cherry_log_top = halflife/out_wd
			stripped_pale_oak_log = halflife/c1a1c_w1
			stripped_pale_oak_log_top = halflife/out_wd
			oak_leaves = halflife/out_shrb1 halflife/out_cac1
			birch_leaves = halflife/out_shrb1 halflife/out_cac1
			spruce_leaves = halflife/out_cac2 halflife/out_shrb1
			jungle_leaves = halflife/out_cac1 halflife/out_shrb1
			acacia_leaves = halflife/out_shrb1
			dark_oak_leaves = halflife/out_cac2 halflife/out_shrb1
			mangrove_leaves = halflife/out_cac1 halflife/out_shrb1
			cherry_leaves = halflife/out_shrb1
			pale_oak_leaves = halflife/out_shrb1
			vine = halflife/out_shrb1
			moss_block = halflife/crossfire_outgrnd halflife/out_grnd1
			moss_carpet = halflife/crossfire_outgrnd halflife/out_grnd1
			dirt_path_top = halflife/out_grnd2 halflife/out_dirt1
			dirt_path_side = halflife/out_dirt1
			podzol_side = halflife/out_dirt1
			mycelium_side = halflife/out_dirt1
			hay_block_side = halflife/out_snbag halflife/out_sndbag4
			hay_block_top = halflife/out_sndbag4 halflife/out_snbag
			white_wool = halflife/c2a4_shield halflife/crete3_wall01b
			polished_tuff = halflife/crete3_flr04 halflife/crete2_flr01
			tuff_bricks = halflife/c1a3wall01
			deepslate_bricks = halflife/tnnl_w6_2 halflife/c1a3wall01
			cracked_deepslate_bricks = halflife/tnnl_w6_2
			deepslate_tiles = halflife/tnnl_flr1 halflife/crete4_flr01
			cracked_deepslate_tiles = halflife/tnnl_flr1
			polished_deepslate = halflife/tnnl_c1 halflife/crete4_flr02
			mud_bricks = halflife/fifties_w13 halflife/c2a4_mud
			calcite = halflife/crete3_wall01b halflife/fifties_ceil01
			dripstone_block = halflife/c2a4_mud2 halflife/out_rk3
			iron_bars = halflife/frostgrate halflife/grate_rust halflife/out_net1
			iron_door = halflife/c1a1_locker halflife/genlocker halflife/elev1
			oak_door = halflife/c1a1c_w1 halflife/trrm_pan1
			spruce_door = halflife/trrm_pan2 halflife/trrm_wood2
			birch_door = halflife/c1a1c_w1
			jungle_door = halflife/trrm_pan1
			acacia_door = halflife/trrm_pan2
			dark_oak_door = halflife/trrm_wood2
			mangrove_door = halflife/trrm_pan1
			cherry_door = halflife/trrm_pan1
			bamboo_door = halflife/out_slat01
			crimson_door = halflife/trrm_pan2
			warped_door = halflife/trrm_pan2
			pale_oak_door = halflife/c1a1c_w1
			poplar_door = halflife/c1a1c_w1
			anvil = halflife/steel halflife/duct_flr01
			coal_block = halflife/out_gravel halflife/black
			gold_block = halflife/c3a2b_reac halflife/yellow
			copper_block = halflife/c2a3turbine1 halflife/c3a2a_pipe1
			oxidized_copper = halflife/lab1_grn1 halflife/c2a3turbine2
			netherrack = halflife/c2a4_mud halflife/c2a4_brd1r
			blackstone = halflife/tnnl_rck1
			blackstone_top = halflife/tnnl_rck1
			polished_blackstone = halflife/tnnl_c1
			polished_blackstone_bricks = halflife/tnnl_w6_2
			cracked_polished_blackstone_bricks = halflife/tnnl_w6_2
			gilded_blackstone = halflife/tnnl_rck1
			basalt_side = halflife/tnnl_swr2 halflife/tnnl_rck1
			basalt_top = halflife/tnnl_rck1
			polished_basalt_side = halflife/tnnl_swr2
			polished_basalt_top = halflife/tnnl_c1
			smooth_basalt = halflife/tnnl_c1
			end_stone = halflife/out_snd2 halflife/c2a5w_sand
			obsidian = halflife/out_pav3 halflife/black
			crying_obsidian = halflife/out_pav3 halflife/black
			terracotta = halflife/c2a4_mud halflife/c3a1a_gravel
			clay = halflife/out_mud1 halflife/c2a4_mud2
			packed_mud = halflife/out_mud1
			magma = halflife/c2a4_mud halflife/c3a2a_pipe1
			glass = halflife/c1a1_window halflife/lab1_glss1 halflife/translucent
			glass_pane_top = halflife/c1a1_window halflife/lab1_glss1
			tinted_glass = halflife/lab1_glss1 halflife/c1a1_window
			ladder = halflife/rope halflife/out_slat01
			iron_trapdoor = halflife/duct_vnt halflife/frostgrate
			oak_trapdoor = halflife/crate01
			spruce_trapdoor = halflife/crate02
			birch_trapdoor = halflife/crate01
			jungle_trapdoor = halflife/crate01
			acacia_trapdoor = halflife/crate01
			dark_oak_trapdoor = halflife/crate02
			mangrove_trapdoor = halflife/crate02
			quartz_block_bottom = halflife/crete3_wall01b halflife/fifties_ceil01
			quartz_block_side = halflife/crete3_wall01b halflife/fifties_ceil01
			quartz_block_top = halflife/fifties_ceil01 halflife/crete3_wall01b
			quartz_bricks = halflife/c1a3wall02
			quartz_pillar_side = halflife/crete3_wall01b
			quartz_pillar_top = halflife/fifties_ceil01
			cracked_nether_bricks = halflife/fifties_w12
			smooth_stone_slab_side = halflife/crete2_flr01
			melon_side = halflife/lab1_grn1
			melon_top = halflife/lab1_grn1
			bone_block_side = halflife/crete3_wall01b
			bone_block_top = halflife/fifties_ceil01
			flower_pot = halflife/c2a4_mud
			lantern = halflife/steel
			white_concrete = halflife/crete3_wall01b halflife/white
			light_gray_concrete = halflife/crete3_wall01 halflife/crete1_wall01
			gray_concrete = halflife/crete2_flr01 halflife/crete2_wall01
			polished_andesite = halflife/crete3_flr04 halflife/crete2_flr01
			polished_diorite = halflife/fifties_ceil01
			polished_granite = halflife/c2a4_brd1r
			iron_block = halflife/metal_wall01b halflife/duct_wall01 halflife/steel
			""";

	/**
	 * The built-in list for a game, by its content folder (such as hl2 or hl1).
	 * Any other game, or all of them together, uses the Half-Life 2 list with
	 * the Half-Life: Source materials after each line, so a block whose
	 * Half-Life 2 material isn't there still gets one from the first game.
	 */
	public static String defaultsFor(String game) {
		if (game != null && game.startsWith("hl1")) {
			return HALF_LIFE_SOURCE;
		}
		Map<String, String[]> first = parse(HALF_LIFE_SOURCE);
		StringBuilder out = new StringBuilder();
		for (String line : DEFAULTS.split("\n")) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			String[] more = equals < 0 || trimmed.startsWith("#") ? null : first.get(trimmed.substring(0, equals).trim());
			out.append(trimmed);
			if (more != null) {
				out.append(' ').append(String.join(" ", more));
			}
			out.append('\n');
		}
		return out.toString();
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
				} else if (name.endsWith("_door")) {
					door(files, name, entry.getValue(), materials, folder, used);
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
		for (String[] one : RECOLOURED) {
			recoloured(made, one[0], one[1], Integer.parseInt(one[2], 16), folder, used);
		}
		cobweb(folder, used);
		nylium(made, "crimson_nylium", folder, used);
		nylium(made, "warped_nylium", folder, used);
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
				RiggedModel.Texture texture = tryLoad(files, material, SIZE * 2, name.endsWith("_leaves") || name.equals("vine")
						|| name.contains("glass") || name.equals("ladder") || name.endsWith("_trapdoor"));
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
		boolean grey = false;
		if (image == null && tint(name) != 0) {
			// Nothing green (Half-Life: Source's ground and shrubs are brown and
			// olive): take the first one anyway, turned grey so Minecraft's
			// biome colour makes it green.
			outer:
			for (String pattern : patterns) {
				for (String material : candidates(pattern, materials)) {
					RiggedModel.Texture texture = tryLoad(files, material, SIZE * 2, false);
					if (texture != null) {
						chosen = material + " (greyed for the biome colour)";
						image = square(texture);
						greyForTint(image, name.endsWith("_leaves") || name.equals("vine"));
						grey = true;
						break outer;
					}
				}
			}
		}
		used.put(name, chosen);
		if (image == null) {
			return;
		}
		int tint = tint(name);
		if (tint != 0 && !grey) {
			untint(image, tint);
		}
		if (name.endsWith("_leaves")) {
			fillGaps(image);
		}
		made.put(name, image);
		writePng(folder.resolve(name + ".png"), image, SIZE);
	}

	/**
	 * A door's two textures from one material: the material is stretched over
	 * the door's full height, two blocks, and cut in two, so the upper block
	 * shows its top half and the lower block its bottom half instead of the
	 * whole material repeated on each.
	 */
	private static void door(GameFiles files, String name, String[] patterns, List<String> materials, Path folder, Map<String, String> used)
			throws IOException {
		String chosen = null;
		int[] image = null;
		for (String pattern : patterns) {
			for (String material : candidates(pattern, materials)) {
				RiggedModel.Texture texture = tryLoad(files, material, SIZE * 2, false);
				if (texture != null) {
					chosen = material;
					image = stretched(texture, SIZE, SIZE * 2);
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
		writePng(folder.resolve(name + "_top.png"), java.util.Arrays.copyOfRange(image, 0, SIZE * SIZE), SIZE);
		writePng(folder.resolve(name + "_bottom.png"), java.util.Arrays.copyOfRange(image, SIZE * SIZE, SIZE * SIZE * 2), SIZE);
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
		if (normals == null) {
			// Older games' water (Half-Life: Source's) is a plain picture with no
			// ripple map: make one from how light and dark the picture is.
			outer:
			for (String pattern : patterns) {
				for (String material : candidates(pattern, materials, false)) {
					RiggedModel.Texture texture = tryLoad(files, material, 256, false);
					if (texture != null) {
						normals = bumpsOf(texture);
						chosen = material + " (ripples from its shading)";
						break outer;
					}
				}
			}
		}
		used.put("water", chosen);
		if (normals == null) {
			return;
		}
		float[] base = new float[3];
		for (int c = 0; c < 3; c++) {
			int shift = 16 - c * 8;
			base[c] = Math.min(1.0F, (WATER_COLOUR >> shift & 255) / (float) (WATER_TINT >> shift & 255));
		}
		String meta = "{\"animation\": {\"frametime\": 3, \"interpolate\": true}}\n";
		// Still water ripples drift gently; flowing water's ripples run along
		// the flow. Both are twice the size: the still texture is laid over two blocks
		// (see FluidUvMixin), and Minecraft draws half the flow texture on a face.
		int size = WATER_SIZE * 2;
		writePng(folder.resolve("water_still.png"), waterStrip(normals, base, size, false), size, size * WATER_FRAMES);
		Files.writeString(folder.resolve("water_still.png.mcmeta"), meta);
		writePng(folder.resolve("water_flow.png"), waterStrip(normals, base, size, true), size, size * WATER_FRAMES);
		Files.writeString(folder.resolve("water_flow.png.mcmeta"), meta);
	}

	private static int[] waterStrip(Vtf normals, float[] base, int s, boolean flowing) {
		int[] strip = new int[s * s * WATER_FRAMES];
		float lx = 0.35F;
		float ly = 0.45F;
		float lz = 0.82F;
		for (int f = 0; f < WATER_FRAMES; f++) {
			float t = (float) f / WATER_FRAMES;
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) {
					float u = 2.0F * x / s;
					float v = 2.0F * y / s;
					float[] a;
					float[] b;
					if (flowing) {
						// Both layers run down the texture, the way the water flows,
						// one twice as fast; each moves a whole tile a loop.
						a = normal(normals, u, v - t * 2);
						b = normal(normals, u * 0.5F + 0.3F, v * 0.5F - t);
					} else {
						a = normal(normals, u + t, v + t * 0.5F);
						b = normal(normals, v + 0.37F, -u - t);
					}
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
		return strip;
	}

	/** The normal at a wrapped position on a normal map, each part from -1 to 1. */
	/** A normal map from a picture's light and dark, as if it were a height map. */
	private static Vtf bumpsOf(RiggedModel.Texture texture) {
		int w = texture.width();
		int h = texture.height();
		int[] in = texture.pixels();
		float[] height = new float[w * h];
		for (int i = 0; i < in.length; i++) {
			int p = in[i];
			height[i] = ((p >> 16 & 255) * 0.3F + (p >> 8 & 255) * 0.59F + (p & 255) * 0.11F) / 255.0F;
		}
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float dx = height[y * w + Math.floorMod(x + 1, w)] - height[y * w + Math.floorMod(x - 1, w)];
				float dy = height[Math.floorMod(y + 1, h) * w + x] - height[Math.floorMod(y - 1, h) * w + x];
				float nx = -dx * 3.0F;
				float ny = -dy * 3.0F;
				float length = (float) Math.sqrt(nx * nx + ny * ny + 1.0F);
				int r = Math.round((nx / length + 1.0F) * 127.5F);
				int g = Math.round((ny / length + 1.0F) * 127.5F);
				int b = Math.round((1.0F / length + 1.0F) * 127.5F);
				out[y * w + x] = 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
			}
		}
		return new Vtf(w, h, out);
	}

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
		if ((name.endsWith("_leaves") || name.equals("vine")) && count > texture.pixels().length * 97 / 100) {
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
		// Molten rock in soft cells of crust over a glow, from noise that wraps
		// at the texture's edges in several sizes, so it has no grid of repeats
		// and no blocky patches. Still lava churns slowly; flowing lava's crust
		// runs along the flow.
		String meta = "{\"animation\": {\"frametime\": 4, \"interpolate\": true}}\n";
		// Twice the size: the still texture is laid over two blocks (see
		// FluidUvMixin), and Minecraft draws half the flow texture on a face.
		int s = ANIMATION_SIZE * 2;
		writePng(folder.resolve("lava_still.png"), lavaStrip(s, false), s, s * ANIMATION_FRAMES);
		Files.writeString(folder.resolve("lava_still.png.mcmeta"), meta);
		writePng(folder.resolve("lava_flow.png"), lavaStrip(s, true), s, s * ANIMATION_FRAMES);
		Files.writeString(folder.resolve("lava_flow.png.mcmeta"), meta);
	}

	private static int[] lavaStrip(int s, boolean flowing) {
		int[] strip = new int[s * s * ANIMATION_FRAMES];
		for (int f = 0; f < ANIMATION_FRAMES; f++) {
			float t = (float) f / ANIMATION_FRAMES;
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) {
					float u = (float) x / s;
					float v = (float) y / s;
					// Plates of dark crust with glowing cracks between them, the
					// plates drifting in small circles (still) or along the flow, so
					// the loop is seamless; a soft glow varies across the plates.
					float shift = flowing ? -t : 0.0F;
					float[] cells = worley(u * 4, (v + shift) * 4, 4, flowing ? 0.0F : t, 5);
					float crack = 1.0F - smooth01((cells[1] - cells[0]) / 0.09F);
					float halo = 1.0F - smooth01((cells[1] - cells[0]) / 0.4F);
					float glow = wrapNoise(u * 12, (v + shift) * 12, 12, 2) * 0.6F + wrapNoise(u * 24, (v + 2 * shift) * 24, 24, 3) * 0.4F;
					float heat = Math.max(0.0F, Math.min(1.0F, 0.8F * crack + 0.3F * halo + 0.5F * glow - 0.08F));
					int r;
					int g;
					int b;
					if (heat < 0.4F) {
						// Crust: dark red-brown.
						float k = heat / 0.4F;
						r = Math.round(55 + 135 * k);
						g = Math.round(12 + 34 * k);
						b = Math.round(6 + 6 * k);
					} else {
						float k = (heat - 0.4F) / 0.6F;
						r = 255;
						g = Math.round(80 + 160 * k * k);
						b = Math.round(8 + 100 * k * k * k);
					}
					strip[(f * s + y) * s + x] = 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
				}
			}
		}
		return strip;
	}

	private static float smooth01(float t) {
		t = Math.max(0.0F, Math.min(1.0F, t));
		return t * t * (3.0F - 2.0F * t);
	}

	/**
	 * Distances to the nearest and second nearest of a set of points, one in
	 * each cell, repeating every {@code period} cells; each point circles a
	 * little as {@code t} goes from 0 to 1 and is back where it started at 1.
	 */
	private static float[] worley(float x, float y, int period, float t, int seed) {
		int cx = (int) Math.floor(x);
		int cy = (int) Math.floor(y);
		float f1 = Float.MAX_VALUE;
		float f2 = Float.MAX_VALUE;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				int i = cx + dx;
				int j = cy + dy;
				float px = 0.15F + 0.7F * cellHash(i, j, period, seed);
				float py = 0.15F + 0.7F * cellHash(i, j, period, seed + 17);
				double phase = 2 * Math.PI * (t + cellHash(i, j, period, seed + 31));
				px += 0.06F * (float) Math.cos(phase);
				py += 0.06F * (float) Math.sin(phase);
				float ddx = i + px - x;
				float ddy = j + py - y;
				float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
				if (d < f1) {
					f2 = f1;
					f1 = d;
				} else if (d < f2) {
					f2 = d;
				}
			}
		}
		return new float[] {f1, f2};
	}

	/** Smooth value noise from 0 to 1 that repeats every {@code period} cells each way. */
	private static float wrapNoise(float x, float y, int period, int seed) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		float fx = x - x0;
		float fy = y - y0;
		fx = fx * fx * (3.0F - 2.0F * fx);
		fy = fy * fy * (3.0F - 2.0F * fy);
		float a = lerp(cellHash(x0, y0, period, seed), cellHash(x0 + 1, y0, period, seed), fx);
		float b = lerp(cellHash(x0, y0 + 1, period, seed), cellHash(x0 + 1, y0 + 1, period, seed), fx);
		return lerp(a, b, fy);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float cellHash(int x, int y, int period, int seed) {
		int h = Math.floorMod(x, period) * 374761393 + Math.floorMod(y, period) * 668265263 + seed * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (float) 0xFFFFFF;
	}

	/** Brightness of a small wrapping image at a point, blended between its pixels. */
	private static float smoothBrightness(int[] image, int size, float x, float y) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		float ax = x - x0;
		float ay = y - y0;
		float out = 0.0F;
		for (int j = 0; j < 2; j++) {
			for (int i = 0; i < 2; i++) {
				float w = (i == 0 ? 1.0F - ax : ax) * (j == 0 ? 1.0F - ay : ay);
				out += w * brightness(image[Math.floorMod(y0 + j, size) * size + Math.floorMod(x0 + i, size)]);
			}
		}
		return out;
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
				int[] box = flameBounds(flame);
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
						// Within the part of the sprite that shows, so the flame fills the block.
						int sx = box[0] + Math.min(box[2] - 1, (int) (su * box[2]));
						int sy = box[1] + Math.min(box[3] - 1, (int) (sv * box[3]));
						int p = flame.pixels()[sy * flame.width() + sx];
						strip[(f * s + y) * s + x] = flamePixel(p);
					}
				}
			}
			String meta = "{\"animation\": {\"frametime\": 2, \"interpolate\": true}}\n";
			String name = which == 2 ? "campfire_fire" : "fire_" + which;
			writePng(folder.resolve(name + ".png"), strip, s, s * frames);
			Files.writeString(folder.resolve(name + ".png.mcmeta"), meta);
		}
		// The little flames over torches, candles and furnaces.
		Path particles = folder.getParent().resolve("particle");
		Files.createDirectories(particles);
		writePng(particles.resolve("flame.png"), flameSprite(flames.get(0), 32), 32);
		writePng(particles.resolve("small_flame.png"), flameSprite(flames.get(Math.min(1, flames.size() - 1)), 16), 16);
		writePng(folder.resolve("torch.png"), flare(flames.get(0)), 64);
		return String.join(" ", names);
	}

	/** A flame sprite filling a square image, its bottom on the bottom edge. */
	private static int[] flameSprite(RiggedModel.Texture flame, int size) {
		int[] box = flameBounds(flame);
		int[] out = new int[size * size];
		// Square, so it is not squashed: as wide as it is tall, centred.
		int side = Math.max(box[2], box[3]);
		int left = box[0] + box[2] / 2 - side / 2;
		int top = box[1] + box[3] - side;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int sx = left + x * side / size;
				int sy = top + y * side / size;
				if (sx < 0 || sy < 0 || sx >= flame.width() || sy >= flame.height()) {
					continue;
				}
				out[y * size + x] = flamePixel(flame.pixels()[sy * flame.width() + sx]);
			}
		}
		return out;
	}

	/**
	 * A torch as a Half-Life 2 road flare: a red stick with a grey cap and a
	 * burning tip, laid out where Minecraft's torch model takes its texture
	 * (a stick two sixteenths wide, from six sixteenths down to the bottom).
	 */
	private static int[] flare(RiggedModel.Texture flame) {
		int size = 64;
		int scale = size / 16;
		int[] out = new int[size * size];
		int[] box = flameBounds(flame);
		java.util.Random random = new java.util.Random(11);
		for (int y = 6 * scale; y < size; y++) {
			for (int x = 7 * scale; x < 9 * scale; x++) {
				// Rounded: lit in the middle of the stick, darker at its edges.
				float across = (x - 7 * scale + 0.5F) / (2 * scale);
				float round = 0.7F + 0.3F * (float) Math.sin(Math.PI * across);
				int colour;
				if (y < 8 * scale) {
					// The burning tip, from the middle of the flame sprite.
					int sx = box[0] + box[2] / 2 + (int) ((across - 0.5F) * box[2] * 0.3F);
					int sy = box[1] + box[3] * 3 / 4 + (y - 6 * scale) * box[3] / (8 * scale);
					int p = flamePixel(flame.pixels()[Math.min(flame.height() - 1, sy) * flame.width() + Math.min(flame.width() - 1, sx)]);
					colour = p != 0 ? p : 0xFFFFD27A;
					round = 1.0F;
				} else if (y < 9 * scale) {
					colour = 0xFF5A5A5A;
				} else {
					colour = y >= 13 * scale && y < 14 * scale ? 0xFFD8D0B8 : 0xFFB3201C;
				}
				float grain = 0.92F + 0.08F * random.nextFloat();
				out[y * size + x] = 0xFF000000 | mix(colour, 0, 1.0F - round * grain);
			}
		}
		return out;
	}

	/** The bounds of the part of a flame sprite that shows: x, y, width, height. */
	private static int[] flameBounds(RiggedModel.Texture flame) {
		int minX = flame.width();
		int minY = flame.height();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < flame.height(); y++) {
			for (int x = 0; x < flame.width(); x++) {
				if (flamePixel(flame.pixels()[y * flame.width() + x]) != 0) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < 0) {
			return new int[] {0, 0, flame.width(), flame.height()};
		}
		return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
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
		// Sugar cane stacks block on block, so a whole tuft (root clump and
		// tips) in each looked like a pile of grass tufts. It takes the middle
		// of a tall tuft instead, where the blades run straight up, stretched to
		// the full block, so a stack reads as one tall clump of reeds.
		Sprite reeds = pickShape(green, "tall", 1);
		writePlant(folder, "sugar_cane", reedImage(sheet, reeds));
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

	/**
	 * Reeds in the colours of a tall grass sprite: a few thin stalks running
	 * straight from the bottom edge to the top, so blocks of cane stacked on
	 * each other join into tall reeds. Grass blades fan out from their root,
	 * so a slice of a tuft was narrow at the bottom and wide at the top, and a
	 * stack showed a row of fans.
	 */
	private static int[] reedImage(RiggedModel.Texture sheet, Sprite sprite) {
		int[] out = new int[SIZE * SIZE];
		int sheetW = sheet.width();
		int[] opaque = new int[sprite.w * sprite.h];
		int count = 0;
		for (int sy = 0; sy < sprite.h; sy++) {
			for (int sx = 0; sx < sprite.w; sx++) {
				int p = sheet.pixels()[(sprite.y + sy) * sheetW + sprite.x + sx];
				if ((p >>> 24) >= 96) {
					opaque[count++] = p | 0xFF000000;
				}
			}
		}
		if (count == 0) {
			return out;
		}
		int mean = 0xFF000000 | average(java.util.Arrays.copyOf(opaque, count));
		int width = Math.max(1, SIZE / 16);
		float[] stalks = {0.1F, 0.27F, 0.46F, 0.63F, 0.84F};
		java.util.Random random = new java.util.Random(7);
		for (int k = 0; k < stalks.length; k++) {
			int left = Math.round(stalks[k] * SIZE);
			float shade = 0.8F + 0.2F * (k % 3) / 2.0F;
			for (int y = 0; y < SIZE; y++) {
				// Grain from the sprite's own pixels, half mixed into its mean
				// colour so the stalk stays one even colour from a distance.
				int grain = opaque[random.nextInt(count)];
				int c = mix(grain, mean, 0.6F);
				for (int w = 0; w < width && left + w < SIZE; w++) {
					// The right edge of a stalk is darker, giving it some roundness.
					float edge = w == width - 1 && width > 1 ? 0.75F : 1.0F;
					out[y * SIZE + left + w] = 0xFF000000 | mix(c, 0, 1.0F - shade * edge);
				}
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
		{"nether_gold_ore", "netherrack", "d8a838"}, {"nether_quartz_ore", "netherrack", "e8dcd0"},
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
	/**
	 * Blocks made from another block's Half-Life 2 texture, recoloured to the
	 * Minecraft block's own colour: name, made from, colour.
	 */
	private static final String[][] RECOLOURED = {
			{"white_terracotta", "terracotta", "C9A693"}, {"orange_terracotta", "terracotta", "A15325"},
			{"magenta_terracotta", "terracotta", "95576C"}, {"light_blue_terracotta", "terracotta", "706C8A"},
			{"yellow_terracotta", "terracotta", "BA8523"}, {"lime_terracotta", "terracotta", "677534"},
			{"pink_terracotta", "terracotta", "A14E4E"}, {"gray_terracotta", "terracotta", "4A3A33"},
			{"light_gray_terracotta", "terracotta", "876A61"}, {"cyan_terracotta", "terracotta", "575B5B"},
			{"purple_terracotta", "terracotta", "764656"}, {"blue_terracotta", "terracotta", "4A3B5B"},
			{"brown_terracotta", "terracotta", "5A3C29"}, {"green_terracotta", "terracotta", "4C532A"},
			{"red_terracotta", "terracotta", "8F3D2E"}, {"black_terracotta", "terracotta", "33231C"},
			// The plain block last, tinted too: Half-Life 2's grey rock read as stone, not clay.
			{"terracotta", "terracotta", "985E43"},
			{"soul_sand", "sand", "5A463A"}, {"soul_soil", "dirt", "4E3C30"},
			{"nether_bricks", "bricks", "3A1C20"}, {"red_nether_bricks", "bricks", "52090B"},
			{"crimson_nylium", "grass_block_top", "852427"}, {"warped_nylium", "grass_block_top", "2B7265"},
			{"nether_wart_block", "grass_block_top", "742020"}, {"warped_wart_block", "grass_block_top", "167E86"},
			{"orange_wool", "white_wool", "F07613"}, {"magenta_wool", "white_wool", "BD44B3"},
			{"light_blue_wool", "white_wool", "3AAFD9"}, {"yellow_wool", "white_wool", "F8C627"},
			{"lime_wool", "white_wool", "70B919"}, {"pink_wool", "white_wool", "ED8DAC"},
			{"gray_wool", "white_wool", "3E4447"}, {"light_gray_wool", "white_wool", "8E8E86"},
			{"cyan_wool", "white_wool", "158991"}, {"purple_wool", "white_wool", "792AAC"},
			{"blue_wool", "white_wool", "35399D"}, {"brown_wool", "white_wool", "724728"},
			{"green_wool", "white_wool", "546D1B"}, {"red_wool", "white_wool", "A12722"},
			{"black_wool", "white_wool", "141519"},
			{"orange_concrete", "white_concrete", "E06100"}, {"magenta_concrete", "white_concrete", "A9309F"},
			{"light_blue_concrete", "white_concrete", "2389C6"}, {"yellow_concrete", "white_concrete", "F0AF15"},
			{"lime_concrete", "white_concrete", "5EA818"}, {"pink_concrete", "white_concrete", "D5658E"},
			{"cyan_concrete", "white_concrete", "157788"}, {"purple_concrete", "white_concrete", "64209C"},
			{"blue_concrete", "white_concrete", "2C2E8F"}, {"brown_concrete", "white_concrete", "603B1F"},
			{"green_concrete", "white_concrete", "495B24"}, {"red_concrete", "white_concrete", "8E2020"},
			{"black_concrete", "white_concrete", "1A1C21"},
			{"diamond_block", "iron_block", "62DBD5"}, {"emerald_block", "iron_block", "2ACB57"},
			{"lapis_block", "iron_block", "1F43A0"}, {"netherite_block", "iron_block", "433F40"},
			{"raw_iron_block", "iron_block", "A6876B"}, {"raw_gold_block", "gold_block", "D79A2B"},
			{"suspicious_sand_1", "sand", "D9CB99"}, {"suspicious_sand_2", "sand", "D9CB99"}, {"suspicious_sand_3", "sand", "D9CB99"},
	};

	/**
	 * A cobweb drawn as fine pale threads: strands from the corners and sides
	 * to an off-centre hub, with a spiral of thread round it, instead of
	 * Minecraft's pixel-art web. Made, not taken from Half-Life 2, which has
	 * no web texture.
	 */
	private static void cobweb(Path folder, Map<String, String> used) throws IOException {
		int s = SIZE;
		float[] alpha = new float[s * s];
		float hx = s * 0.47F;
		float hy = s * 0.53F;
		java.util.Random random = new java.util.Random(7);
		int strands = 11;
		float[] angles = new float[strands];
		for (int i = 0; i < strands; i++) {
			angles[i] = (float) (i * 2 * Math.PI / strands + (random.nextFloat() - 0.5F) * 0.35F);
			float ex = hx + (float) Math.cos(angles[i]) * s;
			float ey = hy + (float) Math.sin(angles[i]) * s;
			thread(alpha, s, hx, hy, ex, ey, 1.1F, 0.85F);
		}
		// The spiral, from strand to strand, sagging a little between them.
		float radius = s * 0.05F;
		for (int turn = 0; radius < s * 0.62F; turn++) {
			for (int i = 0; i < strands; i++) {
				float a0 = angles[i];
				float a1 = angles[(i + 1) % strands] + (i + 1 == strands ? (float) (2 * Math.PI) : 0.0F);
				float r0 = radius;
				float r1 = radius + s * 0.045F / strands;
				float x0 = hx + (float) Math.cos(a0) * r0;
				float y0 = hy + (float) Math.sin(a0) * r0;
				float x1 = hx + (float) Math.cos(a1) * r1;
				float y1 = hy + (float) Math.sin(a1) * r1;
				float mx = (x0 + x1) / 2 + (hx - (x0 + x1) / 2) * 0.06F;
				float my = (y0 + y1) / 2 + (hy - (y0 + y1) / 2) * 0.06F;
				float strength = 0.55F + 0.3F * random.nextFloat();
				radius = r1;
				if (random.nextFloat() < 0.08F) {
					continue; // a broken thread here and there
				}
				thread(alpha, s, x0, y0, mx, my, 0.8F, strength);
				thread(alpha, s, mx, my, x1, y1, 0.8F, strength);
			}
		}
		int[] image = new int[s * s];
		for (int i = 0; i < image.length; i++) {
			int a = Math.round(Math.min(1.0F, alpha[i]) * 235.0F);
			image[i] = a << 24 | 0xE4E6E8;
		}
		writePng(folder.resolve("cobweb.png"), image, s);
		used.put("cobweb", "drawn");
	}

	/** One soft-edged thread from (x0, y0) to (x1, y1), clipped to the image. */
	private static void thread(float[] alpha, int s, float x0, float y0, float x1, float y1, float width, float strength) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		int steps = Math.max(1, Math.round(length * 2));
		for (int k = 0; k <= steps; k++) {
			float px = x0 + dx * k / steps;
			float py = y0 + dy * k / steps;
			int cx = Math.round(px);
			int cy = Math.round(py);
			for (int oy = -2; oy <= 2; oy++) {
				for (int ox = -2; ox <= 2; ox++) {
					int x = cx + ox;
					int y = cy + oy;
					if (x < 0 || y < 0 || x >= s || y >= s) {
						continue;
					}
					float d = (float) Math.sqrt((x - px) * (x - px) + (y - py) * (y - py));
					float a = Math.max(0.0F, 1.0F - d / width) * strength;
					alpha[y * s + x] = Math.max(alpha[y * s + x], a);
				}
			}
		}
	}

	/** One block's texture made from another's, recoloured; see {@link #RECOLOURED}. */
	private static void recoloured(Map<String, int[]> made, String name, String from, int colour, Path folder, Map<String, String> used)
			throws IOException {
		int[] source = made.get(from);
		if (source == null) {
			return;
		}
		if (from.equals("grass_block_top")) {
			source = source.clone();
			retint(source, GRASS_TINT);
		}
		int[] out = recolourTo(source, colour);
		writePng(folder.resolve(name + ".png"), out, SIZE);
		used.put(name, "recoloured " + from);
	}

	/** A nylium side: the netherrack with a band of the nylium's top along its upper edge. */
	private static void nylium(Map<String, int[]> made, String name, Path folder, Map<String, String> used) throws IOException {
		int[] rock = made.get("netherrack");
		int[] top = made.get("grass_block_top");
		if (rock == null || top == null) {
			return;
		}
		String colour = null;
		for (String[] one : RECOLOURED) {
			if (one[0].equals(name)) {
				colour = one[2];
			}
		}
		int[] tinted = top.clone();
		retint(tinted, GRASS_TINT);
		writePng(folder.resolve(name + "_side.png"), band(rock, recolourTo(tinted, Integer.parseInt(colour, 16))), SIZE);
		used.put(name + "_side", "netherrack with " + name);
	}

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
		if (name.equals("vine")) {
			return FOLIAGE_TINT;
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

	/**
	 * Turns a texture grey, about as bright as Minecraft's own grass and
	 * leaves, keeping its light and dark. Leaves also get gaps where the
	 * picture is darkest, so the foliage cards show through.
	 */
	private static void greyForTint(int[] image, boolean gaps) {
		float[] light = new float[image.length];
		double sum = 0;
		for (int i = 0; i < image.length; i++) {
			int p = image[i];
			light[i] = (p >> 16 & 255) * 0.3F + (p >> 8 & 255) * 0.59F + (p & 255) * 0.11F;
			sum += light[i];
		}
		float mean = (float) Math.max(1.0, sum / image.length);
		float cut = -1;
		if (gaps) {
			float[] sorted = light.clone();
			java.util.Arrays.sort(sorted);
			cut = sorted[sorted.length * 3 / 10];
		}
		for (int i = 0; i < image.length; i++) {
			if (light[i] <= cut) {
				image[i] = 0;
				continue;
			}
			// Contrast kept but squeezed a little around Minecraft's grey.
			int v = Math.max(0, Math.min(255, Math.round(150.0F + (light[i] / mean - 1.0F) * 110.0F)));
			image[i] = 0xFF000000 | v << 16 | v << 8 | v;
		}
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
		return stretched(texture, SIZE, SIZE);
	}

	/** Resamples a texture to {@code width} by {@code height}, wrapping at the edges so it still tiles. */
	private static int[] stretched(RiggedModel.Texture texture, int width, int height) {
		int w = texture.width();
		int h = texture.height();
		int[] in = texture.pixels();
		int[] out = new int[width * height];
		// Average the source texels each output texel covers (box filter), or
		// interpolate when the source is smaller.
		float sx = (float) w / width;
		float sy = (float) h / height;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
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
				out[y * width + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
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
