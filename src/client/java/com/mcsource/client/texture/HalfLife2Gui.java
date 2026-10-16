package com.mcsource.client.texture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;

import com.mcsource.model.source.BlockTextures;

/**
 * Menu and inventory textures in Half-Life 2's dark grey scheme: every grey
 * of Minecraft's light grey panels, slots, buttons and lists is made darker,
 * keeping its shading, while coloured icons stay as they are. The vanilla
 * textures listed in {@code assets/mcsource/gui/recolor.txt} are read from
 * the game and written, recoloured, into the pack.
 */
final class HalfLife2Gui {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");

	private HalfLife2Gui() {
	}

	/** Writes the recoloured textures under {@code packRoot}; returns how many. */
	static int generate(Path packRoot) throws IOException {
		int count = 0;
		try (InputStream list = HalfLife2Gui.class.getResourceAsStream("/assets/mcsource/gui/recolor.txt")) {
			if (list == null) {
				return 0;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(list, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				String path = line.trim();
				if (path.isEmpty()) {
					continue;
				}
				try (InputStream in = Minecraft.class.getResourceAsStream("/assets/minecraft/" + path)) {
					if (in == null) {
						continue;
					}
					Path out = packRoot.resolve("assets/minecraft/" + path);
					Files.createDirectories(out.getParent());
					if (path.endsWith(".mcmeta")) {
						// A texture's settings must come from the same pack as the texture.
						Files.write(out, in.readAllBytes());
						continue;
					}
					try (NativeImage image = NativeImage.read(in)) {
						// Hovered buttons light up, as Half-Life 2's do.
						int lift = path.contains("highlighted") ? 28 : 0;
						int w = image.getWidth();
						int h = image.getHeight();
						int[] pixels = new int[w * h];
						for (int y = 0; y < h; y++) {
							for (int x = 0; x < w; x++) {
								pixels[y * w + x] = recolor(image.getPixel(x, y), lift);
							}
						}
						BlockTextures.writePng(out, pixels, w, h);
						count++;
					}
				} catch (IOException | RuntimeException e) {
					LOGGER.debug("Could not recolour {}", path, e);
				}
			}
		}
		return count;
	}

	/** Darkens a grey pixel (ARGB); coloured ones are kept. */
	static int recolor(int argb, int lift) {
		int a = argb >>> 24;
		int r = argb >> 16 & 0xFF;
		int g = argb >> 8 & 0xFF;
		int b = argb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		if (a == 0 || max - min > 14) {
			return argb;
		}
		float light = (r + g + b) / 765.0F;
		// Minecraft's panel grey (198) becomes about 69, slots (139) about 42,
		// white edges about 102: Half-Life 2's dark panels with soft bevels.
		int v = Math.round(12.0F + 90.0F * (float) Math.pow(light, 1.8)) + lift;
		int rr = Math.min(255, v + (r - (r + g + b) / 3) / 3);
		int gg = Math.min(255, v + (g - (r + g + b) / 3) / 3);
		int bb = Math.min(255, v + (b - (r + g + b) / 3) / 3);
		return a << 24 | Math.max(0, rr) << 16 | Math.max(0, gg) << 8 | Math.max(0, bb);
	}
}
