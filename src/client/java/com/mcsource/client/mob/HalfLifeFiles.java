package com.mcsource.client.mob;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Finds model files in the player's own Half-Life install. Models are looked
 * for in {@code config/mcsource/models}, then in the folder set as
 * {@code halflife_path} in {@code config/mcsource.properties}, then in
 * Steam's usual install folders on macOS, Windows and Linux.
 */
public final class HalfLifeFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final String KEY = "halflife_path";
	private static List<Path> roots;

	private HalfLifeFiles() {
	}

	/** The model file with this name (such as {@code zombie.mdl}), or null if none is found. */
	public static Path model(String fileName) {
		for (Path root : roots()) {
			for (Path candidate : new Path[] {root.resolve(fileName), root.resolve("valve").resolve("models").resolve(fileName)}) {
				if (Files.isRegularFile(candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	private static synchronized List<Path> roots() {
		if (roots != null) {
			return roots;
		}
		List<Path> found = new ArrayList<>();
		Path config = FabricLoader.getInstance().getConfigDir();
		found.add(config.resolve("mcsource").resolve("models"));

		Path file = config.resolve("mcsource.properties");
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("Could not read {}", file, e);
			}
		} else {
			try (Writer writer = Files.newBufferedWriter(file)) {
				writer.write("# MC:Source settings\n");
				writer.write("# Folder of your Half-Life install (the one holding the 'valve' folder).\n");
				writer.write("# Leave empty to look in Steam's usual folders. Mob models are read from\n");
				writer.write("# valve/models there, or from config/mcsource/models.\n");
				writer.write(KEY + "=\n");
			} catch (IOException e) {
				LOGGER.warn("Could not write {}", file, e);
			}
		}
		String configured = properties.getProperty(KEY, "").trim();
		if (!configured.isEmpty()) {
			if (configured.startsWith("~")) {
				configured = System.getProperty("user.home") + configured.substring(1);
			}
			found.add(Path.of(configured));
		}

		Path home = Path.of(System.getProperty("user.home"));
		found.add(home.resolve("Library/Application Support/Steam/steamapps/common/Half-Life"));
		found.add(home.resolve(".steam/steam/steamapps/common/Half-Life"));
		found.add(home.resolve(".local/share/Steam/steamapps/common/Half-Life"));
		found.add(Path.of("C:/Program Files (x86)/Steam/steamapps/common/Half-Life"));
		found.add(Path.of("C:/Program Files/Steam/steamapps/common/Half-Life"));
		roots = List.copyOf(found);
		return roots;
	}
}
