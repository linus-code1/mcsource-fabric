package com.mcsource.model.source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A Quake and Half-Life 1 {@code .pak} archive. */
public final class Pak implements GameFiles {
	private static final int SIGNATURE = 0x4B434150; // "PACK"

	private final Path file;
	private final Map<String, long[]> entries = new HashMap<>();

	public Pak(Path file) throws IOException {
		this.file = file;
		try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
			byte[] header = new byte[12];
			in.readFully(header);
			ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			if (h.getInt(0) != SIGNATURE) {
				throw new IOException(file + " is not a PAK");
			}
			int offset = h.getInt(4);
			int length = h.getInt(8);
			byte[] directory = new byte[length];
			in.seek(offset);
			in.readFully(directory);
			ByteBuffer d = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i + 64 <= length; i += 64) {
				int end = 0;
				while (end < 56 && directory[i + end] != 0) {
					end++;
				}
				String name = new String(directory, i, end, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
				entries.put(name, new long[] {Integer.toUnsignedLong(d.getInt(i + 56)), d.getInt(i + 60)});
			}
		}
	}

	@Override
	public byte[] read(String path) throws IOException {
		long[] entry = entries.get(path.replace('\\', '/').toLowerCase(Locale.ROOT));
		if (entry == null) {
			return null;
		}
		byte[] out = new byte[(int) entry[1]];
		try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
			in.seek(entry[0]);
			in.readFully(out);
		}
		return out;
	}
}
