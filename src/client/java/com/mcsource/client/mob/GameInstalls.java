package com.mcsource.client.mob;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import com.mcsource.model.source.GameFiles;
import com.mcsource.model.source.Pak;
import com.mcsource.model.source.Vpk;

/**
 * The player's own installs of Half-Life and Source games, opened as file
 * systems so models can be read from them. Nothing from these games is
 * shipped with the mod; everything is read from the player's copies.
 *
 * <p>Looked for, in order: {@code config/mcsource} (loose files laid out as
 * in the game, such as {@code models/zombie/classic.mdl}), the folders set
 * in {@code config/mcsource.properties}, and Steam's usual library folder.
 */
public final class GameInstalls {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static GameFiles halfLife;
	private static GameFiles source;

	private GameInstalls() {
	}

	/** Half-Life 1 files: loose files and {@code pak0.pak} in each {@code valve} folder. */
	public static synchronized GameFiles halfLife() {
		if (halfLife == null) {
			List<GameFiles> mounts = new ArrayList<>();
			List<String> names = new ArrayList<>();
			Path own = ownFolder();
			mounts.add(loose(own));
			names.add(own.toString());
			for (Path root : roots("halflife_path", "Half-Life")) {
				for (String game : new String[] {"valve_hd", "valve"}) {
					Path folder = root.resolve(game);
					if (Files.isDirectory(folder)) {
						mounts.add(loose(folder));
						names.add(folder.toString());
						for (String pak : new String[] {"pak0.pak", "pak1.pak"}) {
							Path file = folder.resolve(pak);
							if (Files.isRegularFile(file)) {
								try {
									mounts.add(new Pak(file));
									names.add(file.toString());
								} catch (IOException e) {
									LOGGER.warn("Could not open {}", file, e);
								}
							}
						}
					}
				}
			}
			LOGGER.info("Half-Life files searched: {}", names);
			halfLife = all(mounts);
		}
		return halfLife;
	}

	/** Source game files: VPK archives and loose files in each game folder (hl2, episodic, ep2 and so on). */
	public static synchronized GameFiles source() {
		if (source == null) {
			List<GameFiles> mounts = new ArrayList<>();
			List<String> names = new ArrayList<>();
			Path own = ownFolder();
			mounts.add(loose(own));
			names.add(own.toString());
			Set<Path> roots = new LinkedHashSet<>(roots("halflife2_path", "Half-Life 2"));
			roots.addAll(roots("source_paths", null));
			// Any other Source game in the Steam library.
			for (Path library : steamLibraries()) {
				try (DirectoryStream<Path> games = Files.newDirectoryStream(library)) {
					for (Path game : games) {
						roots.add(game);
					}
				} catch (IOException e) {
					// No such library.
				}
			}
			for (Path root : roots) {
				for (Path folder : gameFolders(root)) {
					mounts.add(loose(folder));
					try (DirectoryStream<Path> vpks = Files.newDirectoryStream(folder, "*_dir.vpk")) {
						for (Path vpk : vpks) {
							try {
								Vpk archive = new Vpk(vpk);
								mounts.add(archive);
								names.add(vpk + " (" + archive.size() + " files)");
							} catch (IOException | RuntimeException e) {
								LOGGER.warn("Could not open {}", vpk, e);
							}
						}
					} catch (IOException e) {
						// Not a folder.
					}
				}
			}
			LOGGER.info("Source game files searched: {}", names);
			source = all(mounts);
		}
		return source;
	}

	private static Path ownFolder() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcsource");
	}

	/** Folders inside a game install that hold Source game content: hl2 first, then the rest. */
	private static List<Path> gameFolders(Path root) {
		List<Path> folders = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return folders;
		}
		for (String preferred : new String[] {"hl2", "episodic", "ep2", "hl2_complete", "lostcoast"}) {
			if (Files.isDirectory(root.resolve(preferred))) {
				folders.add(root.resolve(preferred));
			}
		}
		try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
			for (Path child : children) {
				if (folders.contains(child) || !Files.isDirectory(child)) {
					continue;
				}
				// Source games describe each content folder with a gameinfo.txt (Source 2 games use other formats).
				if (Files.isRegularFile(child.resolve("gameinfo.txt"))) {
					folders.add(child);
				}
			}
		} catch (IOException e) {
			// Unreadable folder.
		}
		return folders;
	}

	private static List<Path> steamLibraries() {
		Path home = Path.of(System.getProperty("user.home"));
		return List.of(home.resolve("Library/Application Support/Steam/steamapps/common"), home.resolve(".steam/steam/steamapps/common"),
				home.resolve(".local/share/Steam/steamapps/common"), Path.of("C:/Program Files (x86)/Steam/steamapps/common"),
				Path.of("C:/Program Files/Steam/steamapps/common"));
	}

	/** Folders named in the settings file under {@code key}, then the game's usual Steam folder. */
	private static List<Path> roots(String key, String steamFolder) {
		List<Path> roots = new ArrayList<>();
		String configured = settings().getProperty(key, "").trim();
		for (String part : configured.split(",")) {
			String path = part.trim();
			if (path.isEmpty()) {
				continue;
			}
			if (path.startsWith("~")) {
				path = System.getProperty("user.home") + path.substring(1);
			}
			roots.add(Path.of(path));
		}
		if (steamFolder != null) {
			for (Path library : steamLibraries()) {
				roots.add(library.resolve(steamFolder));
			}
		}
		return roots;
	}

	private static Properties settings() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mcsource.properties");
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			} catch (IOException e) {
				LOGGER.warn("Could not read {}", file, e);
			}
		}
		boolean changed = false;
		for (String[] entry : new String[][] {
				{"halflife_path", "Folder of your Half-Life install (the one holding the 'valve' folder)."},
				{"halflife2_path", "Folder of your Half-Life 2 install (the one holding the 'hl2' folder)."},
				{"source_paths", "Other Source game folders, separated by commas."}}) {
			if (!properties.containsKey(entry[0])) {
				properties.setProperty(entry[0], "");
				changed = true;
			}
		}
		if (changed) {
			try (Writer writer = Files.newBufferedWriter(file)) {
				properties.store(writer, "MC:Source settings. Leave paths empty to look in Steam's usual folder. Models are read from"
						+ " these games, or from loose files in config/mcsource laid out as in the game.");
			} catch (IOException e) {
				LOGGER.warn("Could not write {}", file, e);
			}
		}
		return properties;
	}

	private static GameFiles loose(Path folder) {
		return path -> {
			Path file = folder.resolve(path.replace('\\', '/'));
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		};
	}

	private static GameFiles all(List<GameFiles> mounts) {
		return path -> {
			for (GameFiles mount : mounts) {
				byte[] bytes = mount.read(path);
				if (bytes != null) {
					return bytes;
				}
			}
			return null;
		};
	}
}
