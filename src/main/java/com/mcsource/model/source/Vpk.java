package com.mcsource.model.source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Valve pack file set: a {@code *_dir.vpk} directory listing files stored
 * in it and in numbered archives beside it ({@code *_000.vpk} and so on).
 * Written for this mod from public descriptions of the format.
 */
public final class Vpk implements GameFiles {
	private static final int SIGNATURE = 0x55AA1234;
	private static final int IN_DIRECTORY = 0x7FFF;

	private record Entry(byte[] preload, int archive, long offset, int length) {
	}

	private final Path directory;
	private final String baseName;
	private final long dataStart;
	private final Map<String, Entry> entries = new HashMap<>();

	public Vpk(Path dirFile) throws IOException {
		directory = dirFile;
		String fileName = dirFile.getFileName().toString();
		baseName = fileName.substring(0, fileName.length() - "_dir.vpk".length());
		ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(dirFile)).order(ByteOrder.LITTLE_ENDIAN);
		if (data.getInt(0) != SIGNATURE) {
			throw new IOException(dirFile + " is not a VPK");
		}
		int version = data.getInt(4);
		int treeSize = data.getInt(8);
		int headerSize = version == 1 ? 12 : 28;
		dataStart = headerSize + (long) treeSize;
		data.position(headerSize);
		while (true) {
			String extension = cString(data);
			if (extension.isEmpty()) {
				break;
			}
			while (true) {
				String folder = cString(data);
				if (folder.isEmpty()) {
					break;
				}
				while (true) {
					String file = cString(data);
					if (file.isEmpty()) {
						break;
					}
					data.getInt(); // CRC
					int preloadBytes = Short.toUnsignedInt(data.getShort());
					int archive = Short.toUnsignedInt(data.getShort());
					long offset = Integer.toUnsignedLong(data.getInt());
					int length = data.getInt();
					data.getShort(); // terminator
					byte[] preload = new byte[preloadBytes];
					data.get(preload);
					String path = (folder.equals(" ") ? "" : folder + "/") + file + (extension.equals(" ") ? "" : "." + extension);
					entries.put(path.toLowerCase(Locale.ROOT), new Entry(preload, archive, offset, length));
				}
			}
		}
	}

	private static String cString(ByteBuffer data) {
		int start = data.position();
		while (data.get() != 0) {
			// Read up to the terminating zero.
		}
		byte[] bytes = new byte[data.position() - start - 1];
		data.get(start, bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	public int size() {
		return entries.size();
	}

	@Override
	public List<String> list(String folder) {
		String prefix = folder.toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (String path : entries.keySet()) {
			if (path.startsWith(prefix)) {
				out.add(path);
			}
		}
		return out;
	}

	@Override
	public byte[] read(String path) throws IOException {
		Entry entry = entries.get(path.replace('\\', '/').toLowerCase(Locale.ROOT));
		if (entry == null) {
			return null;
		}
		byte[] out = new byte[entry.preload.length + entry.length];
		System.arraycopy(entry.preload, 0, out, 0, entry.preload.length);
		if (entry.length > 0) {
			Path archive;
			long offset = entry.offset;
			if (entry.archive == IN_DIRECTORY) {
				archive = directory;
				offset += dataStart;
			} else {
				archive = directory.resolveSibling(String.format(Locale.ROOT, "%s_%03d.vpk", baseName, entry.archive));
			}
			try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
				file.seek(offset);
				file.readFully(out, entry.preload.length, entry.length);
			}
		}
		return out;
	}
}
