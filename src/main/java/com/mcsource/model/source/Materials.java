package com.mcsource.model.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mcsource.model.RiggedModel;

/**
 * Reads a Source material ({@code .vmt}) and its base texture ({@code .vtf})
 * as ARGB pixels. Patch materials are followed to the material they include.
 */
public final class Materials {
	private static final Pattern BASE_TEXTURE = Pattern.compile("\"?\\$basetexture\"?\\s+\"?([^\"\\s}]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern INCLUDE = Pattern.compile("\"?include\"?\\s+\"?([^\"\\s}]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CUTOUT = Pattern.compile("\"?\\$(alphatest|translucent)\"?\\s+\"?1", Pattern.CASE_INSENSITIVE);

	private Materials() {
	}

	/**
	 * The material at {@code path} (such as {@code nature/dirtfloor003a},
	 * relative to {@code materials/}), or null if it or its texture is
	 * missing. Opaque materials get full alpha, since Source keeps other data
	 * (such as shininess) there.
	 */
	public static RiggedModel.Texture load(GameFiles files, String path, int maxSize) throws IOException {
		String name = clean(path, ".vmt");
		String text = null;
		for (int depth = 0; depth < 4; depth++) {
			byte[] vmt = files.read("materials/" + name + ".vmt");
			if (vmt == null) {
				return null;
			}
			text = new String(vmt, StandardCharsets.ISO_8859_1);
			if (BASE_TEXTURE.matcher(text).find()) {
				break;
			}
			Matcher include = INCLUDE.matcher(text);
			if (!include.find()) {
				return null;
			}
			name = clean(include.group(1), ".vmt");
		}
		Matcher base = BASE_TEXTURE.matcher(text);
		if (!base.find()) {
			return null;
		}
		boolean cutout = CUTOUT.matcher(text).find();
		byte[] vtf = files.read("materials/" + clean(base.group(1), ".vtf") + ".vtf");
		if (vtf == null) {
			return null;
		}
		Vtf image = Vtf.decode(vtf, maxSize);
		int[] pixels = image.pixels;
		if (!cutout) {
			for (int i = 0; i < pixels.length; i++) {
				pixels[i] |= 0xFF000000;
			}
		}
		return new RiggedModel.Texture(name, image.width, image.height, pixels, cutout);
	}

	/** A path relative to {@code materials/} without its extension, forward slashes, lower case. */
	private static String clean(String path, String extension) {
		String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		if (p.startsWith("materials/")) {
			p = p.substring("materials/".length());
		}
		if (p.endsWith(extension)) {
			p = p.substring(0, p.length() - extension.length());
		}
		return p;
	}
}
