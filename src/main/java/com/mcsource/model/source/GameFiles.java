package com.mcsource.model.source;

import java.io.IOException;
import java.util.List;

/** Files of an installed game, by their path inside the game (such as {@code models/zombie/classic.mdl}). */
public interface GameFiles {
	/** The file's bytes, or null if no such file. Paths use forward slashes and any case. */
	byte[] read(String path) throws IOException;

	/** Every file path (lower case) under a folder such as {@code materials/}, where that can be listed. */
	default List<String> list(String folder) throws IOException {
		return List.of();
	}
}
