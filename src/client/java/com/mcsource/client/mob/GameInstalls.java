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
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

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
			List<Game> found = new ArrayList<>();
			Set<Path> seen = new java.util.HashSet<>();
			for (Path root : roots) {
				for (Path folder : gameFolders(root)) {
					if (!seen.add(folder.toAbsolutePath().normalize())) {
						continue;
					}
					List<GameFiles> folderMounts = new ArrayList<>();
					folderMounts.add(loose(folder));
					try (DirectoryStream<Path> vpks = Files.newDirectoryStream(folder, "*_dir.vpk")) {
						for (Path vpk : vpks) {
							try {
								Vpk archive = new Vpk(vpk);
								folderMounts.add(archive);
								names.add(vpk + " (" + archive.size() + " files)");
							} catch (IOException | RuntimeException e) {
								LOGGER.warn("Could not open {}", vpk, e);
							}
						}
					} catch (IOException e) {
						// Not a folder.
					}
					mounts.addAll(folderMounts);
					String id = folder.getFileName().toString().toLowerCase(Locale.ROOT);
					if (folderMounts.size() > 1 && found.stream().noneMatch(game -> game.id().equals(id))) {
						found.add(new Game(id, gameName(folder, id), all(folderMounts)));
					}
				}
			}
			LOGGER.info("Source game files searched: {}", names);
			games = List.copyOf(found);
			source = all(mounts);
		}
		return source;
	}

	/** One installed Source game's own content folder, such as hl2 or hl1. */
	public record Game(String id, String name, GameFiles files) {
	}

	private static List<Game> games = List.of();

	/** The Source games found installed, Half-Life 2 first. */
	public static synchronized List<Game> games() {
		source();
		return games;
	}

	/** The game block textures are made from: its id, or "auto" for all games found (Half-Life 2 first). */
	public static String textureGame() {
		String value = settingText("texture_game").toLowerCase(Locale.ROOT);
		return value.isEmpty() ? "auto" : value;
	}

	/** The files block textures are made from: the chosen game's own, or every game's when none is chosen or it isn't found. */
	public static GameFiles textureFiles() {
		String chosen = textureGame();
		for (Game game : games()) {
			if (game.id().equals(chosen)) {
				// The game's own folder, then the player's loose files.
				return all(List.of(game.files(), loose(ownFolder())));
			}
		}
		return source();
	}

	/** The game's name from its gameinfo.txt, or a name for the folders everyone knows. */
	private static String gameName(Path folder, String id) {
		switch (id) {
			case "hl2":
				return "Half-Life 2";
			case "hl1":
				return "Half-Life: Source";
			case "episodic":
				return "Half-Life 2: Episode One";
			case "ep2":
				return "Half-Life 2: Episode Two";
			case "lostcoast":
				return "Half-Life 2: Lost Coast";
			case "cstrike":
				return "Counter-Strike: Source";
			case "dod":
				return "Day of Defeat: Source";
			case "portal":
				return "Portal";
			default:
				break;
		}
		try {
			String info = Files.readString(folder.resolve("gameinfo.txt"), java.nio.charset.StandardCharsets.ISO_8859_1);
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bgame\\s+\"([^\"]+)\"").matcher(info);
			if (m.find()) {
				return m.group(1);
			}
		} catch (IOException | RuntimeException e) {
			// Use the folder's name.
		}
		return id;
	}

	/** A text setting from {@code config/mcsource.properties}, trimmed; empty if unset. */
	public static String settingText(String key) {
		synchronized (SETTINGS_LOCK) {
			if (cachedSettings == null) {
				cachedSettings = settings();
			}
			return cachedSettings.getProperty(key, "").trim();
		}
	}

	/** Saves a setting in {@code config/mcsource.properties}. */
	public static void saveSetting(String key, String value) {
		synchronized (SETTINGS_LOCK) {
			Properties properties = settings();
			properties.setProperty(key, value);
			Path file = FabricLoader.getInstance().getConfigDir().resolve("mcsource.properties");
			try (Writer writer = Files.newBufferedWriter(file)) {
				properties.store(writer, "MC:Source settings. Leave paths empty to look in Steam's usual folder. Models are read from"
						+ " these games, or from loose files in config/mcsource laid out as in the game.");
			} catch (IOException e) {
				LOGGER.warn("Could not write {}", file, e);
			}
			cachedSettings = properties;
		}
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

	/** A number from {@code config/mcsource.properties}, or {@code fallback} if unset or not a number. */
	public static float setting(String key, float fallback) {
		synchronized (SETTINGS_LOCK) {
			if (cachedSettings == null) {
				cachedSettings = settings();
			}
		}
		try {
			String value = cachedSettings.getProperty(key, "").trim();
			return value.isEmpty() ? fallback : Float.parseFloat(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** A yes/no setting from {@code config/mcsource.properties} (true, yes, on or 1), or {@code fallback} if unset. */
	public static boolean flag(String key, boolean fallback) {
		synchronized (SETTINGS_LOCK) {
			if (cachedSettings == null) {
				cachedSettings = settings();
			}
		}
		String value = cachedSettings.getProperty(key, "").trim().toLowerCase(java.util.Locale.ROOT);
		if (value.isEmpty()) {
			return fallback;
		}
		return value.equals("true") || value.equals("yes") || value.equals("on") || value.equals("1");
	}

	private static final Object SETTINGS_LOCK = new Object();
	private static Properties cachedSettings;

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
				{"source_paths", "Other Source game folders, separated by commas."},
				{"texture_game", "Game block textures are made from: auto, or a game's folder name such as hl2 or hl1 (also in Video Settings)."},
				{"viewmodel_fov", "Field of view of the first-person hands, as in Source (54 in Half-Life 2; higher shows more)."},
				{"viewmodel_offset_x", "Moves the hands right (negative: left), in Source units."},
				{"viewmodel_offset_y", "Moves the hands forward, in Source units."},
				{"viewmodel_offset_z", "Moves the hands up, in Source units."},
				{"scary_sun", "true gives the sun a scary face (restart to apply)."},
				{"hl2_trees", "false keeps Minecraft's block trees in new chunks instead of Half-Life 2 tree models."},
				{"hl2_ambience", "false turns off the Half-Life 2 ambience loops on the menus and in the world."},
				{"minecraft_music", "true keeps Minecraft's music playing alongside the Half-Life 2 ambience."}}) {
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
		return new GameFiles() {
			@Override
			public byte[] read(String path) throws IOException {
				Path file = folder.resolve(path.replace('\\', '/'));
				return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
			}

			@Override
			public List<String> list(String prefix) throws IOException {
				Path start = folder.resolve(prefix);
				if (!Files.isDirectory(start)) {
					return List.of();
				}
				try (Stream<Path> files = Files.walk(start)) {
					return files.filter(Files::isRegularFile)
							.map(file -> folder.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT))
							.toList();
				}
			}
		};
	}

	private static GameFiles all(List<GameFiles> mounts) {
		return new GameFiles() {
			@Override
			public byte[] read(String path) throws IOException {
				for (GameFiles mount : mounts) {
					byte[] bytes = mount.read(path);
					if (bytes != null) {
						return bytes;
					}
				}
				return null;
			}

			@Override
			public List<String> list(String prefix) throws IOException {
				Set<String> paths = new LinkedHashSet<>();
				for (GameFiles mount : mounts) {
					paths.addAll(mount.list(prefix));
				}
				return new ArrayList<>(paths);
			}
		};
	}
}
