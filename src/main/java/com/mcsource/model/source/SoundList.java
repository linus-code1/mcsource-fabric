package com.mcsource.model.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Puts Half-Life 2's sounds, read from the player's own game files, in place
 * of Minecraft's: for each Minecraft sound event, the first pattern in its
 * list that matches any readable sound file is used, with every file it
 * matches (up to {@link #MAX_VARIANTS}) as the event's random variants. The
 * files are written into a resource pack folder with a {@code sounds.json}
 * that replaces those events; events with nothing found keep Minecraft's
 * sounds. Nothing from Valve ships with the mod.
 *
 * <p>Minecraft only plays Ogg Vorbis, so the WAVE files are written under the
 * {@code .ogg} names it asks for, in the {@code mcsource} namespace under
 * {@code sounds/hl2/}; the client decodes those itself (see {@link Wav}).
 */
public final class SoundList {
	/** Most variants given to one event. */
	public static final int MAX_VARIANTS = 8;
	/** Where the sounds go inside the pack's {@code mcsource} namespace. */
	public static final String FOLDER = "hl2/";

	private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9/._-]+");

	/**
	 * The built-in list. Each line: one or more Minecraft sound events, then
	 * {@code =}, then sound paths under {@code sound/} in the game (without
	 * {@code .wav}), tried in order. {@code #} matches a number and {@code *}
	 * anything. Options: {@code volume:0.5}, {@code pitch:1.2},
	 * {@code subtitle:key}.
	 */
	public static final String DEFAULTS = """
			# The mod's list of Half-Life 2 sounds for Minecraft sound events.
			# Each line: minecraft.event other.event ... = sound sound ... [volume:N] [pitch:N] [subtitle:key]
			# Sounds are paths under sound/ in the game, without .wav; # matches a number, * matches anything.
			# The first sound (or pattern) found is used; a pattern gives every file it matches as variants.

			# ---- Footsteps (Minecraft also plays these while mining a block and when landing on it)
			block.stone.step block.stone.hit block.stone.fall block.deepslate.step block.deepslate.hit block.deepslate.fall block.deepslate_bricks.step block.deepslate_bricks.hit block.deepslate_bricks.fall block.deepslate_tiles.step block.deepslate_tiles.hit block.deepslate_tiles.fall block.polished_deepslate.step block.polished_deepslate.hit block.polished_deepslate.fall block.tuff.step block.tuff.hit block.tuff.fall block.tuff_bricks.step block.tuff_bricks.hit block.tuff_bricks.fall block.polished_tuff.step block.polished_tuff.hit block.polished_tuff.fall block.basalt.step block.basalt.hit block.basalt.fall block.calcite.step block.calcite.hit block.calcite.fall block.dripstone_block.step block.dripstone_block.hit block.dripstone_block.fall block.nether_bricks.step block.nether_bricks.hit block.nether_bricks.fall block.netherrack.step block.netherrack.hit block.netherrack.fall block.nether_ore.step block.nether_ore.hit block.nether_ore.fall block.lodestone.step block.lodestone.hit block.lodestone.fall block.bone_block.step block.bone_block.hit block.bone_block.fall block.mud_bricks.step block.mud_bricks.hit block.mud_bricks.fall block.resin_bricks.step block.resin_bricks.hit block.resin_bricks.fall = player/footsteps/concrete# player/footsteps/tile#
			block.grass.step block.grass.hit block.grass.fall block.wet_grass.step block.wet_grass.hit block.wet_grass.fall block.moss.step block.moss.hit block.moss.fall block.moss_carpet.step block.moss_carpet.hit block.moss_carpet.fall block.azalea.step block.azalea.hit block.azalea.fall block.flowering_azalea.step block.flowering_azalea.hit block.flowering_azalea.fall block.azalea_leaves.step block.azalea_leaves.hit block.azalea_leaves.fall block.cherry_leaves.step block.cherry_leaves.hit block.cherry_leaves.fall block.leaf_litter.step block.leaf_litter.hit block.leaf_litter.fall block.pink_petals.step block.pink_petals.hit block.pink_petals.fall block.roots.step block.roots.hit block.roots.fall block.vine.step block.vine.hit block.vine.fall = player/footsteps/grass# player/footsteps/dirt#
			block.gravel.step block.gravel.hit block.gravel.fall block.suspicious_gravel.step block.suspicious_gravel.hit block.suspicious_gravel.fall = player/footsteps/gravel# player/footsteps/dirt#
			block.rooted_dirt.step block.rooted_dirt.hit block.rooted_dirt.fall block.packed_mud.step block.packed_mud.hit block.packed_mud.fall block.soul_soil.step block.soul_soil.hit block.soul_soil.fall = player/footsteps/dirt# player/footsteps/gravel#
			block.mud.step block.mud.hit block.mud.fall block.muddy_mangrove_roots.step block.muddy_mangrove_roots.hit block.muddy_mangrove_roots.fall = player/footsteps/mud# player/footsteps/dirt#
			block.sand.step block.sand.hit block.sand.fall block.suspicious_sand.step block.suspicious_sand.hit block.suspicious_sand.fall block.soul_sand.step block.soul_sand.hit block.soul_sand.fall = player/footsteps/sand# player/footsteps/dirt#
			block.snow.step block.snow.hit block.snow.fall block.powder_snow.step block.powder_snow.hit block.powder_snow.fall = player/footsteps/snow# player/footsteps/sand#
			block.wood.step block.wood.hit block.wood.fall block.cherry_wood.step block.cherry_wood.hit block.cherry_wood.fall block.bamboo_wood.step block.bamboo_wood.hit block.bamboo_wood.fall block.nether_wood.step block.nether_wood.hit block.nether_wood.fall block.stem.step block.stem.hit block.stem.fall block.chiseled_bookshelf.step block.chiseled_bookshelf.hit block.chiseled_bookshelf.fall block.mangrove_roots.step block.mangrove_roots.hit block.mangrove_roots.fall = player/footsteps/wood# player/footsteps/woodpanel#
			block.metal.step block.metal.hit block.metal.fall block.iron.step block.iron.hit block.iron.fall block.copper.step block.copper.hit block.copper.fall block.copper_bulb.step block.copper_bulb.hit block.copper_bulb.fall block.netherite_block.step block.netherite_block.hit block.netherite_block.fall block.anvil.step block.anvil.hit block.anvil.fall block.heavy_core.step block.heavy_core.hit block.heavy_core.fall block.vault.step block.vault.hit block.vault.fall block.trial_spawner.step block.trial_spawner.hit block.trial_spawner.fall block.spawner.step block.spawner.hit block.spawner.fall block.lantern.step block.lantern.hit block.lantern.fall = player/footsteps/metal#
			block.copper_grate.step block.copper_grate.hit block.copper_grate.fall = player/footsteps/metalgrate# player/footsteps/metal#
			block.chain.step block.chain.hit block.chain.fall = player/footsteps/chainlink# player/footsteps/metal#
			block.glass.step block.glass.fall = player/footsteps/tile# player/footsteps/concrete#
			block.glass.hit = physics/glass/glass_sheet_impact_soft# physics/glass/*impact_soft* player/footsteps/tile#
			block.ladder.step block.ladder.hit block.ladder.fall = player/footsteps/ladder# player/footsteps/wood#
			block.slime_block.step block.slime_block.hit block.slime_block.fall block.honey_block.step block.honey_block.hit block.honey_block.fall block.slime_block.break block.slime_block.place block.honey_block.break block.honey_block.place = physics/flesh/flesh_squishy_impact_hard# physics/flesh/flesh_impact_hard#

			# ---- Breaking and placing blocks
			block.stone.break block.deepslate.break block.deepslate_bricks.break block.deepslate_tiles.break block.polished_deepslate.break block.tuff.break block.tuff_bricks.break block.polished_tuff.break block.basalt.break block.calcite.break block.dripstone_block.break block.nether_bricks.break block.netherrack.break block.nether_ore.break block.lodestone.break block.bone_block.break block.mud_bricks.break block.resin_bricks.break = physics/concrete/concrete_break# physics/concrete/rock_impact_hard# physics/concrete/concrete_impact_hard#
			block.stone.place block.deepslate.place block.deepslate_bricks.place block.deepslate_tiles.place block.polished_deepslate.place block.tuff.place block.tuff_bricks.place block.polished_tuff.place block.basalt.place block.calcite.place block.dripstone_block.place block.nether_bricks.place block.netherrack.place block.nether_ore.place block.lodestone.place block.bone_block.place block.mud_bricks.place block.resin_bricks.place = physics/concrete/concrete_impact_hard# physics/concrete/rock_impact_hard# physics/concrete/concrete_block_impact_hard#
			block.grass.break block.grass.place block.wet_grass.break block.wet_grass.place block.moss.break block.moss.place block.moss_carpet.break block.moss_carpet.place block.azalea.break block.azalea.place block.azalea_leaves.break block.azalea_leaves.place block.cherry_leaves.break block.cherry_leaves.place block.roots.break block.roots.place block.vine.break block.vine.place = player/footsteps/grass# player/footsteps/dirt#
			block.gravel.break block.gravel.place block.suspicious_gravel.break block.suspicious_gravel.place = player/footsteps/gravel# player/footsteps/dirt#
			block.rooted_dirt.break block.rooted_dirt.place block.packed_mud.break block.packed_mud.place block.soul_soil.break block.soul_soil.place = player/footsteps/dirt# player/footsteps/gravel#
			block.mud.break block.mud.place = player/footsteps/mud# player/footsteps/dirt#
			block.sand.break block.sand.place block.suspicious_sand.break block.suspicious_sand.place block.soul_sand.break block.soul_sand.place = player/footsteps/sand# physics/surfaces/sand_impact_bullet#
			block.snow.break block.snow.place block.powder_snow.break block.powder_snow.place = player/footsteps/snow# player/footsteps/sand#
			block.wood.break block.cherry_wood.break block.bamboo_wood.break block.nether_wood.break block.stem.break block.chiseled_bookshelf.break block.ladder.break = physics/wood/wood_plank_break# physics/wood/wood_box_break# physics/wood/wood_crate_break# physics/wood/*break*
			block.wood.place block.cherry_wood.place block.bamboo_wood.place block.nether_wood.place block.stem.place block.chiseled_bookshelf.place block.ladder.place = physics/wood/wood_plank_impact_hard# physics/wood/wood_box_impact_hard# physics/wood/*impact_hard*
			block.metal.break block.iron.break block.copper.break block.copper_bulb.break block.copper_grate.break block.netherite_block.break block.anvil.break block.heavy_core.break block.vault.break block.trial_spawner.break block.spawner.break block.lantern.break block.chain.break = physics/metal/metal_box_break# physics/metal/metal_solid_impact_hard# physics/metal/*break*
			block.metal.place block.iron.place block.copper.place block.copper_bulb.place block.copper_grate.place block.netherite_block.place block.heavy_core.place block.vault.place block.trial_spawner.place block.spawner.place block.lantern.place block.chain.place = physics/metal/metal_solid_impact_hard# physics/metal/metal_box_impact_hard# physics/metal/*impact_hard*
			block.glass.break = physics/glass/glass_sheet_break# physics/glass/glass_largesheet_break# physics/glass/*break*
			block.glass.place = physics/glass/glass_sheet_impact_hard# physics/glass/glass_impact_hard# physics/glass/*impact_hard*

			# ---- The player
			entity.player.hurt = player/pl_pain# subtitle:subtitles.entity.player.hurt
			entity.player.death = player/pl_pain7 player/pl_pain# subtitle:subtitles.entity.player.death
			entity.player.hurt_drown = player/pl_drown# player/pl_pain# subtitle:subtitles.entity.player.hurt_drown
			entity.player.hurt_on_fire = player/pl_burnpain# player/pl_pain# subtitle:subtitles.entity.player.hurt_on_fire
			entity.player.small_fall = player/pl_fallpain# subtitle:subtitles.entity.generic.small_fall
			entity.player.big_fall = player/pl_fallpain# subtitle:subtitles.entity.generic.big_fall
			entity.player.swim = player/footsteps/wade# player/pl_wade# subtitle:subtitles.entity.generic.swim
			entity.player.splash entity.player.splash.high_speed = ambient/water/water_splash# player/footsteps/slosh# subtitle:subtitles.entity.generic.splash
			entity.player.attack.weak entity.player.attack.nodamage entity.player.attack.sweep = weapons/iceaxe/iceaxe_swing1 volume:0.7
			entity.player.attack.strong entity.player.attack.crit entity.player.attack.knockback = weapons/crowbar/crowbar_impact# physics/flesh/flesh_impact_bullet# volume:0.7
			entity.item.pickup = items/itempickup items/ammo_pickup volume:0.6 subtitle:subtitles.entity.item.pickup

			# ---- Doors, chests and buttons
			block.wooden_door.open block.wooden_trapdoor.open block.fence_gate.open block.cherry_wood_door.open block.cherry_wood_trapdoor.open block.cherry_wood_fence_gate.open block.bamboo_wood_door.open block.bamboo_wood_trapdoor.open block.bamboo_wood_fence_gate.open block.nether_wood_door.open block.nether_wood_trapdoor.open block.nether_wood_fence_gate.open = doors/door1_move doors/wood_move1 doors/default_move volume:0.9
			block.wooden_door.close block.wooden_trapdoor.close block.fence_gate.close block.cherry_wood_door.close block.cherry_wood_trapdoor.close block.cherry_wood_fence_gate.close block.bamboo_wood_door.close block.bamboo_wood_trapdoor.close block.bamboo_wood_fence_gate.close block.nether_wood_door.close block.nether_wood_trapdoor.close block.nether_wood_fence_gate.close = doors/door_wood_close1 doors/wood_stop1 doors/door1_stop volume:0.9
			block.iron_door.open block.iron_trapdoor.open block.copper_door.open block.copper_trapdoor.open = doors/door_metal_thin_open1 doors/door_metal_medium_open1 doors/door_metal_*open* doors/default_move volume:0.9
			block.iron_door.close block.iron_trapdoor.close block.copper_door.close block.copper_trapdoor.close = doors/door_metal_thin_close2 doors/door_metal_medium_close1 doors/door_metal_*close* doors/default_stop volume:0.9
			block.chest.open block.barrel.open = items/ammocrate_open
			block.chest.close block.barrel.close = items/ammocrate_close
			block.stone_button.click_on = buttons/button14 buttons/button3 volume:0.5
			block.stone_button.click_off = buttons/button15 buttons/button9 volume:0.5
			block.wooden_button.click_on block.wooden_button.click_off block.cherry_wood_button.click_on block.cherry_wood_button.click_off block.bamboo_wood_button.click_on block.bamboo_wood_button.click_off block.nether_wood_button.click_on block.nether_wood_button.click_off = buttons/lightswitch2 buttons/button14 volume:0.5
			block.lever.click = buttons/lever1 buttons/lever# volume:0.6
			ui.button.click = ui/buttonclick

			# ---- Weather and explosions
			entity.lightning_bolt.thunder = ambient/weather/thunder# ambient/atmosphere/thunder#
			entity.generic.explode = weapons/explode# ambient/explosions/explode_#
			""";

	/** Subtitles of the events above, which a replaced event would otherwise lose; block sounds follow a pattern. */
	private static final Map<String, String> SUBTITLES = Map.ofEntries(
			Map.entry("block.wooden_door.open", "subtitles.block.door.toggle"),
			Map.entry("block.wooden_door.close", "subtitles.block.door.toggle"),
			Map.entry("block.iron_door.open", "subtitles.block.door.toggle"),
			Map.entry("block.iron_door.close", "subtitles.block.door.toggle"),
			Map.entry("block.chest.open", "subtitles.block.chest.open"),
			Map.entry("block.chest.close", "subtitles.block.chest.close"),
			Map.entry("block.barrel.open", "subtitles.block.barrel.open"),
			Map.entry("block.barrel.close", "subtitles.block.barrel.close"),
			Map.entry("block.lever.click", "subtitles.block.lever.click"),
			Map.entry("entity.player.attack.weak", "subtitles.entity.player.attack.weak"),
			Map.entry("entity.player.attack.nodamage", "subtitles.entity.player.attack.weak"),
			Map.entry("entity.player.attack.sweep", "subtitles.entity.player.attack.sweep"),
			Map.entry("entity.player.attack.strong", "subtitles.entity.player.attack.strong"),
			Map.entry("entity.player.attack.crit", "subtitles.entity.player.attack.crit"),
			Map.entry("entity.player.attack.knockback", "subtitles.entity.player.attack.knockback"),
			Map.entry("entity.lightning_bolt.thunder", "subtitles.entity.lightning_bolt.thunder"),
			Map.entry("entity.generic.explode", "subtitles.entity.generic.explode"));

	/** One line of the list: the patterns to try and how to play what's found. */
	public record Entry(String[] patterns, float volume, float pitch, String subtitle) {
	}

	private SoundList() {
	}

	/** Reads a list in the {@link #DEFAULTS} format, one entry per event. */
	public static Map<String, Entry> parse(String text) {
		Map<String, Entry> out = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
				continue;
			}
			List<String> patterns = new ArrayList<>();
			float volume = 1;
			float pitch = 1;
			String subtitle = null;
			for (String token : trimmed.substring(equals + 1).trim().split("\\s+")) {
				try {
					if (token.startsWith("volume:")) {
						volume = Float.parseFloat(token.substring(7));
					} else if (token.startsWith("pitch:")) {
						pitch = Float.parseFloat(token.substring(6));
					} else if (token.startsWith("subtitle:")) {
						subtitle = token.substring(9);
					} else if (!token.isEmpty()) {
						patterns.add(token.toLowerCase(Locale.ROOT));
					}
				} catch (NumberFormatException e) {
					// A mistyped number keeps the default.
				}
			}
			if (patterns.isEmpty()) {
				continue;
			}
			for (String event : trimmed.substring(0, equals).trim().split("\\s+")) {
				if (!event.isEmpty()) {
					out.put(event.toLowerCase(Locale.ROOT), new Entry(patterns.toArray(String[]::new), volume, pitch, subtitle));
				}
			}
		}
		return out;
	}

	/**
	 * Writes the sounds found for each event under {@code packRoot} and a
	 * {@code sounds.json} replacing those events. Returns, for each event,
	 * the sounds used (or null when none was found and Minecraft's is kept).
	 */
	public static Map<String, List<String>> generate(GameFiles files, Map<String, Entry> mapping, Path packRoot) throws IOException {
		List<String> sounds = new ArrayList<>();
		for (String path : files.list("sound/")) {
			if (path.endsWith(".wav")) {
				sounds.add(path.substring("sound/".length(), path.length() - ".wav".length()));
			}
		}
		java.util.Collections.sort(sounds);
		Map<String, Boolean> readable = new java.util.HashMap<>();
		Map<String, List<String>> used = new LinkedHashMap<>();
		// namespace -> event -> json of the event
		Map<String, Map<String, String>> json = new TreeMap<>();
		Path folder = packRoot.resolve("assets/mcsource/sounds/" + FOLDER);
		for (Map.Entry<String, Entry> line : mapping.entrySet()) {
			String event = line.getKey();
			Entry entry = line.getValue();
			List<String> found = null;
			for (String pattern : entry.patterns()) {
				List<String> matches = new ArrayList<>();
				for (String candidate : candidates(pattern, sounds)) {
					if (readable.computeIfAbsent(candidate, name -> write(files, name, folder))) {
						matches.add(candidate);
						if (matches.size() >= MAX_VARIANTS) {
							break;
						}
					}
				}
				if (!matches.isEmpty()) {
					found = matches;
					break;
				}
			}
			used.put(event, found);
			if (found == null) {
				continue;
			}
			String namespace = "minecraft";
			String name = event;
			int colon = event.indexOf(':');
			if (colon >= 0) {
				namespace = event.substring(0, colon);
				name = event.substring(colon + 1);
			}
			StringBuilder out = new StringBuilder("{\"replace\": true");
			String subtitle = subtitle(name, entry);
			if (subtitle != null) {
				out.append(", \"subtitle\": \"").append(escape(subtitle)).append('"');
			}
			out.append(", \"sounds\": [");
			for (int i = 0; i < found.size(); i++) {
				out.append(i == 0 ? "" : ", ").append("{\"name\": \"mcsource:").append(FOLDER).append(found.get(i)).append('"');
				if (entry.volume() != 1) {
					out.append(", \"volume\": ").append(entry.volume());
				}
				if (entry.pitch() != 1) {
					out.append(", \"pitch\": ").append(entry.pitch());
				}
				out.append('}');
			}
			out.append("]}");
			json.computeIfAbsent(namespace, n -> new LinkedHashMap<>()).put(name, out.toString());
		}
		for (Map.Entry<String, Map<String, String>> namespace : json.entrySet()) {
			StringBuilder out = new StringBuilder("{\n");
			int i = 0;
			for (Map.Entry<String, String> event : namespace.getValue().entrySet()) {
				out.append("  \"").append(escape(event.getKey())).append("\": ").append(event.getValue())
						.append(++i < namespace.getValue().size() ? ",\n" : "\n");
			}
			out.append("}\n");
			Path file = packRoot.resolve("assets/" + namespace.getKey() + "/sounds.json");
			Files.createDirectories(file.getParent());
			Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
		}
		return used;
	}

	private static String subtitle(String event, Entry entry) {
		if (entry.subtitle() != null) {
			return entry.subtitle().equals("none") ? null : entry.subtitle();
		}
		String known = SUBTITLES.get(event);
		if (known != null) {
			return known;
		}
		if (event.endsWith("_door.open") || event.endsWith("_door.close")) {
			return "subtitles.block.door.toggle";
		}
		if (event.endsWith("_trapdoor.open")) {
			return "subtitles.block.trapdoor.open";
		}
		if (event.endsWith("_trapdoor.close")) {
			return "subtitles.block.trapdoor.close";
		}
		if (event.endsWith("fence_gate.open") || event.endsWith("fence_gate.close")) {
			return "subtitles.block.fence_gate.toggle";
		}
		if (event.endsWith("_button.click_on") || event.endsWith("_button.click_off")) {
			return "subtitles.block.button.click";
		}
		if (event.startsWith("block.")) {
			String kind = event.substring(event.lastIndexOf('.') + 1);
			return switch (kind) {
				case "step" -> "subtitles.block.generic.footsteps";
				case "break", "place", "hit", "fall" -> "subtitles.block.generic." + kind;
				default -> null;
			};
		}
		return null;
	}

	/** Sound files matching a pattern, in name order ({@code #} is a number, {@code *} anything). */
	private static List<String> candidates(String pattern, List<String> sounds) {
		if (!pattern.contains("*") && !pattern.contains("#")) {
			int at = java.util.Collections.binarySearch(sounds, pattern);
			return at >= 0 ? List.of(pattern) : List.of();
		}
		int wild = pattern.length();
		for (int i = 0; i < pattern.length(); i++) {
			if (pattern.charAt(i) == '*' || pattern.charAt(i) == '#') {
				wild = i;
				break;
			}
		}
		String prefix = pattern.substring(0, wild);
		StringBuilder regex = new StringBuilder();
		for (char c : pattern.toCharArray()) {
			regex.append(switch (c) {
				case '*' -> ".*";
				case '#' -> "[0-9]+";
				default -> Pattern.quote(String.valueOf(c));
			});
		}
		Pattern compiled = Pattern.compile(regex.toString());
		List<String> out = new ArrayList<>();
		// The list is sorted, so files sharing the literal start of the pattern sit together.
		int start = java.util.Collections.binarySearch(sounds, prefix);
		for (int i = start >= 0 ? start : -start - 1; i < sounds.size() && sounds.get(i).startsWith(prefix); i++) {
			if (compiled.matcher(sounds.get(i)).matches()) {
				out.add(sounds.get(i));
			}
		}
		// Numbered variants in number order (2 before 10).
		out.sort(java.util.Comparator.comparing((String s) -> s.replaceAll("[0-9]+$", "")).thenComparingLong(SoundList::trailingNumber));
		return out;
	}

	private static long trailingNumber(String name) {
		int i = name.length();
		while (i > 0 && Character.isDigit(name.charAt(i - 1))) {
			i--;
		}
		return i == name.length() || name.length() - i > 18 ? -1 : Long.parseLong(name.substring(i));
	}

	/** Copies one sound into the pack if it can be decoded; returns whether it was. */
	private static boolean write(GameFiles files, String name, Path folder) {
		if (!SAFE_NAME.matcher(name).matches()) {
			return false;
		}
		try {
			byte[] data = files.read("sound/" + name + ".wav");
			if (data == null) {
				return false;
			}
			Wav.decode(data);
			Path file = folder.resolve(name + ".ogg");
			Files.createDirectories(file.getParent());
			Files.write(file, data);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
