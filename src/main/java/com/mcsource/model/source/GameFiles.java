package com.mcsource.model.source;

import java.io.IOException;

/** Files of an installed game, by their path inside the game (such as {@code models/zombie/classic.mdl}). */
public interface GameFiles {
	/** The file's bytes, or null if no such file. Paths use forward slashes and any case. */
	byte[] read(String path) throws IOException;
}
