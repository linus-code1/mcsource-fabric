package com.mcsource.client.texture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.model.source.BlockTextures;
import com.mcsource.model.source.SkyTextures;

/**
 * An always-on resource pack of block textures made from the player's own
 * Half-Life 2 files (see {@link BlockTextures}). The pack is built in
 * {@code config/mcsource/hl2_block_pack} the first time the game starts with
 * Half-Life 2 found, and again whenever the list in
 * {@code config/mcsource/block_textures.txt} (the player's changes to the
 * list) or the mod's own list changes.
 */
public final class HalfLife2BlockPack implements RepositorySource {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	/** Bump when the way textures are built changes, so old packs are rebuilt. */
	private static final String VERSION = "9";
	private static final String USER_LIST = """
			# Your changes to which Half-Life 2 material each Minecraft block texture uses.
			# A line here replaces the mod's own line for that texture, for example:
			#   dirt = nature/dirtfloor005c
			# Materials are paths under materials/ in the game (without .vmt); * matches anything,
			# and the first one found is used. hl2_block_pack/used_materials.txt shows what was picked.
			# To keep Minecraft's texture for a block, list a material that doesn't exist, such as:
			#   stone = none
			""";
	private static boolean built;

	@Override
	public void loadPacks(Consumer<Pack> output) {
		Path root = FabricLoader.getInstance().getConfigDir().resolve("mcsource");
		Path pack = root.resolve("hl2_block_pack");
		if (!built) {
			built = true;
			try {
				build(root, pack);
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not build the Half-Life 2 block textures", e);
			}
		}
		if (!Files.isDirectory(pack.resolve("assets"))) {
			return;
		}
		PackLocationInfo location = new PackLocationInfo("mcsource_hl2_blocks", Component.literal("Half-Life 2 block textures"),
				PackSource.BUILT_IN, Optional.empty());
		Pack.Metadata metadata = new Pack.Metadata(Component.literal("Made from your Half-Life 2 files by MC:Source"),
				PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
		output.accept(new Pack(location, new PathPackResources.PathResourcesSupplier(pack), metadata,
				new PackSelectionConfig(true, Pack.Position.TOP, false)));
	}

	private static void build(Path root, Path pack) throws IOException {
		Files.createDirectories(root);
		// The player's own lines override the built-in list line by line.
		Path listFile = root.resolve("block_textures.txt");
		if (!Files.isRegularFile(listFile)) {
			Files.writeString(listFile, USER_LIST, StandardCharsets.UTF_8);
		}
		String list = Files.readString(listFile, StandardCharsets.UTF_8);
		if (list.startsWith("# Half-Life 2 materials used for Minecraft block textures.")) {
			// The first version saved the whole built-in list here; replace it so
			// later improvements to the list reach the player.
			list = USER_LIST;
			Files.writeString(listFile, list, StandardCharsets.UTF_8);
		}
		Map<String, String[]> mapping = BlockTextures.parse(BlockTextures.DEFAULTS);
		mapping.putAll(BlockTextures.parse(list));
		boolean scary = GameInstalls.flag("scary_sun", false);
		String stamp = VERSION + ":" + BlockTextures.DEFAULTS.hashCode() + ":" + list.hashCode() + ":" + scary;
		Path stampFile = pack.resolve("built_from.txt");
		if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp)) {
			return;
		}
		long start = System.currentTimeMillis();
		Path fresh = root.resolve("hl2_block_pack.new");
		delete(fresh);
		Map<String, String> used = BlockTextures.generate(GameInstalls.source(), mapping, fresh);
		List<String> missing = new ArrayList<>();
		StringBuilder report = new StringBuilder();
		for (Map.Entry<String, String> entry : used.entrySet()) {
			if (entry.getValue() == null) {
				missing.add(entry.getKey());
				report.append(entry.getKey()).append(" = (nothing found, Minecraft's is kept)\n");
			} else {
				report.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
			}
		}
		if (missing.size() == used.size()) {
			// No Half-Life 2 materials found: keep whatever was built before.
			LOGGER.info("No Half-Life 2 materials found for block textures; keeping Minecraft's");
			delete(fresh);
			return;
		}
		try {
			String glow = SkyTextures.generate(GameInstalls.source(), fresh, scary);
			report.append("sun and moon = ").append(glow == null ? "(drawn glow)" : glow).append(scary ? ", scary face" : "").append('\n');
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not make the sun and moon", e);
		}
		try {
			report.append("menus = ").append(HalfLife2Gui.generate(fresh)).append(" textures in Half-Life 2's dark grey\n");
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not make the menu textures", e);
		}
		try {
			report.append("title screen = ").append(HalfLife2Gui.panorama(GameInstalls.source(), fresh)).append('\n');
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not make the title screen background", e);
		}
		Files.writeString(fresh.resolve("used_materials.txt"), report.toString());
		Files.writeString(fresh.resolve("built_from.txt"), stamp);
		delete(pack);
		Files.move(fresh, pack);
		LOGGER.info("Built {} Half-Life 2 block textures in {} ms (none found for {})", used.size() - missing.size(),
				System.currentTimeMillis() - start, missing);
	}

	private static void delete(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		try (var files = Files.walk(path)) {
			for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}
}
