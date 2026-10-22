package com.mcsource.world.hl2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.mcsource.model.source.GameFiles;

/**
 * The game's files as one map sees them: the files packed into the map
 * itself first (its own textures, models and the per-map copies of
 * materials), then the game's.
 */
final class BspFiles implements GameFiles {
	private static final int LUMP_PAKFILE = 40;

	private final Map<String, byte[]> packed = new HashMap<>();
	private final GameFiles game;

	BspFiles(Bsp map, GameFiles game) {
		this.game = game;
		ByteBuffer lump = map.lump(LUMP_PAKFILE);
		if (lump.limit() == 0) {
			return;
		}
		byte[] bytes = new byte[lump.limit()];
		lump.get(0, bytes);
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					packed.put(clean(entry.getName()), zip.readAllBytes());
				}
			}
		} catch (IOException | RuntimeException e) {
			// A damaged pack only loses the map's own files.
		}
	}

	private static String clean(String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	@Override
	public byte[] read(String path) throws IOException {
		byte[] own = packed.get(clean(path));
		if (own != null) {
			return own;
		}
		return game == null ? null : game.read(path);
	}

	@Override
	public List<String> list(String folder) throws IOException {
		List<String> out = new ArrayList<>(game == null ? List.of() : game.list(folder));
		String prefix = clean(folder);
		for (String name : packed.keySet()) {
			if (name.startsWith(prefix)) {
				out.add(name);
			}
		}
		return out;
	}
}
