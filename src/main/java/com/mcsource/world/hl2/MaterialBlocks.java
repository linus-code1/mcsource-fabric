package com.mcsource.world.hl2;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.BlockTextures;
import com.mcsource.model.source.GameFiles;
import com.mcsource.model.source.Materials;

/**
 * Picks the Minecraft block for a Source material. The mod's own list of
 * which Half-Life 2 material each block texture is made from is read
 * backwards, so a wall of {@code brick/brickwall031d} becomes bricks, which
 * the mod draws with a Half-Life 2 brick texture again; materials it doesn't
 * name are matched by words in their names.
 */
final class MaterialBlocks {
	private static final Map<String, String[]> MAPPING = BlockTextures.parse(BlockTextures.DEFAULTS);
	private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();
	/** Materials baked for one map's cubemaps: maps/MAPNAME/material_X_Y_Z. */
	private static final Pattern MAP_PREFIX = Pattern.compile("^maps/[^/]+/");
	private static final Pattern CUBEMAP_SUFFIX = Pattern.compile("(_-?\\d+){3}$");
	private static final String[] TEXTURE_SUFFIXES = {"_top", "_side", "_bottom", "_front", "_end", "_still"};

	private static final String[][] WORDS = {
			{"glass", "glass"}, {"window", "glass"},
			{"grass", "grass_block"}, {"mud", "mud"}, {"dirt", "dirt"}, {"sand", "sand"},
			{"gravel", "gravel"}, {"snow", "snow_block"}, {"ice", "packed_ice"},
			{"rock", "stone"}, {"cliff", "stone"}, {"cave", "stone"},
			{"brick", "bricks"}, {"cobble", "cobblestone"}, {"stone", "stone_bricks"},
			{"wood", "oak_planks"}, {"plank", "oak_planks"}, {"crate", "oak_planks"},
			{"grate", "iron_bars"}, {"fence", "iron_bars"},
			{"metal", "iron_block"}, {"steel", "iron_block"}, {"pipe", "iron_block"}, {"vent", "iron_block"},
			{"tile", "deepslate_tiles"}, {"carpet", "gray_wool"}, {"fabric", "white_wool"},
			{"plaster", "calcite"}, {"black", "black_concrete"}, {"white", "white_concrete"},
			{"concrete", "smooth_stone"}, {"cement", "smooth_stone"}, {"floor", "smooth_stone"},
			{"wall", "smooth_stone"}, {"ceiling", "smooth_stone"}};

	private MaterialBlocks() {
	}

	/** Strips the per-map cubemap copy back to the material it was made from. */
	static String plain(String material) {
		String m = material.toLowerCase(Locale.ROOT);
		if (MAP_PREFIX.matcher(m).find()) {
			m = MAP_PREFIX.matcher(m).replaceFirst("");
			m = CUBEMAP_SUFFIX.matcher(m).replaceFirst("");
		}
		return m;
	}

	static boolean tool(String material) {
		return material == null || material.startsWith("tools/");
	}

	/** Plain blocks to match a material's colour against, when its name says nothing. */
	private static final String[] BY_COLOUR = {"smooth_stone", "stone", "andesite", "tuff", "deepslate", "cobblestone", "stone_bricks",
			"bricks", "sandstone", "terracotta", "packed_mud",
			"mud_bricks", "calcite", "white_concrete", "light_gray_concrete", "gray_concrete", "black_concrete",
			"brown_concrete", "red_concrete", "orange_concrete", "yellow_concrete", "lime_concrete", "green_concrete", "cyan_concrete",
			"light_blue_concrete", "blue_concrete", "purple_concrete", "white_terracotta", "light_gray_terracotta", "gray_terracotta",
			"brown_terracotta", "green_terracotta", "cyan_terracotta"};

	static BlockState of(String material, GameFiles files) {
		if (material == null) {
			return Blocks.SMOOTH_STONE.defaultBlockState();
		}
		return CACHE.computeIfAbsent(plain(material), name -> find(name, files));
	}

	private static BlockState find(String material, GameFiles files) {
		if (material.equals("tools/toolsblack")) {
			return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("black_concrete")).defaultBlockState();
		}
		// The most specific entry of the mod's list naming this material.
		String bestTexture = null;
		int bestScore = -1;
		for (Map.Entry<String, String[]> entry : MAPPING.entrySet()) {
			for (String pattern : entry.getValue()) {
				int score = match(pattern.toLowerCase(Locale.ROOT), material);
				if (score > bestScore) {
					Block block = blockFor(entry.getKey());
					if (block != null) {
						bestScore = score;
						bestTexture = entry.getKey();
					}
				}
			}
		}
		// A bare "*word*" says little; a near-exact name is trusted.
		if (bestTexture != null && bestScore >= 6) {
			return blockFor(bestTexture).defaultBlockState();
		}
		for (String[] word : WORDS) {
			if (material.contains(word[0])) {
				return block(word[1]);
			}
		}
		int colour = averageColour(material, files);
		if (colour == -1) {
			return Blocks.SMOOTH_STONE.defaultBlockState();
		}
		BlockState best = Blocks.SMOOTH_STONE.defaultBlockState();
		double bestDistance = Double.MAX_VALUE;
		for (String name : BY_COLOUR) {
			BlockState state = block(name);
			int other = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
			double distance = distance(colour, other);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = state;
			}
		}
		return best;
	}

	private static BlockState block(String name) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name)).defaultBlockState();
	}

	/** The average colour of a material's texture, read from the game, or -1. */
	private static int averageColour(String material, GameFiles files) {
		if (files == null) {
			return -1;
		}
		try {
			RiggedModel.Texture texture = Materials.load(files, material, 32);
			if (texture == null || texture.pixels().length == 0) {
				return -1;
			}
			long r = 0;
			long g = 0;
			long b = 0;
			for (int pixel : texture.pixels()) {
				r += pixel >> 16 & 0xFF;
				g += pixel >> 8 & 0xFF;
				b += pixel & 0xFF;
			}
			int n = texture.pixels().length;
			return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		} catch (java.io.IOException | RuntimeException e) {
			return -1;
		}
	}

	/** Colour difference weighted for the eye. */
	private static double distance(int a, int b) {
		int dr = (a >> 16 & 0xFF) - (b >> 16 & 0xFF);
		int dg = (a >> 8 & 0xFF) - (b >> 8 & 0xFF);
		int db = (a & 0xFF) - (b & 0xFF);
		return 2 * dr * dr + 4 * dg * dg + 3 * db * db;
	}

	/** How many literal characters of a {@code *} pattern match the name, or -1 if it doesn't match. */
	private static int match(String pattern, String name) {
		if (!pattern.contains("*")) {
			return pattern.equals(name) ? 1000 : -1;
		}
		String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
		if (!name.matches(regex)) {
			return -1;
		}
		return pattern.replace("*", "").length();
	}

	/** The full, solid block a texture belongs to, if any (stone_bricks, grass_block_top and so on). */
	private static Block blockFor(String texture) {
		String name = texture;
		for (int pass = 0; pass < 2; pass++) {
			var block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name));
			if (block.isPresent() && block.get() != Blocks.AIR) {
				BlockState state = block.get().defaultBlockState();
				if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && state.getFluidState().isEmpty()) {
					return block.get();
				}
				return null;
			}
			for (String suffix : TEXTURE_SUFFIXES) {
				if (name.endsWith(suffix)) {
					name = name.substring(0, name.length() - suffix.length());
					break;
				}
			}
		}
		return null;
	}
}
