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
 * Menu and inventory textures in Half-Life 2's dark grey scheme: panels and
 * slots flat and dark, buttons and text boxes as flat boxes with thin
 * borders, other greys made darker, while coloured icons stay as they are. The vanilla
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
						int[] pixels = flatWidget(path, w, h);
						// Inventories and their slots lose their bevels.
						boolean flat = path.contains("/container/") || path.endsWith("recipe_book.png");
						if (pixels == null) {
							pixels = new int[w * h];
							for (int y = 0; y < h; y++) {
								for (int x = 0; x < w; x++) {
									pixels[y * w + x] = recolor(image.getPixel(x, y), lift, flat);
								}
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

	/** Half-Life 2 skies tried for the title screen, best first. */
	private static final String[] SKIES = {"skybox/sky_day01_01", "skybox/sky_day01_05", "skybox/sky_day02_02", "skybox/sky_day01_04",
		"skybox/sky_day02_01", "skybox/sky_day01_06"};

	/**
	 * The title screen's turning background made from a Half-Life 2 sky. One
	 * side of the sky is used all the way round, mirrored on every other side
	 * so the edges always meet; its top fades into a plain sky colour for the
	 * face above, and below the horizon it fades into dark haze, so no seams
	 * or stretched streaks show as it turns. Returns the sky used, or a note
	 * that none was found.
	 */
	static String panorama(com.mcsource.model.source.GameFiles files, Path packRoot) throws IOException {
		for (String sky : SKIES) {
			com.mcsource.model.RiggedModel.Texture face;
			try {
				face = com.mcsource.model.source.Materials.load(files, sky + "ft", 1024);
			} catch (IOException | RuntimeException e) {
				face = null;
			}
			if (face == null || face.width() < 8 || face.height() < 8) {
				continue;
			}
			int size = PANORAMA_SIZE;
			// The sky picture fills the top of each side down to the horizon at its middle.
			int horizon = size / 2;
			int topColour = rowAverage(face, 0);
			int bottomColour = rowAverage(face, face.height() - 1);
			int haze = darken(bottomColour, 0.45F);
			int[] side = new int[size * size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int colour;
					if (y < horizon) {
						int tx = Math.min(face.width() - 1, x * face.width() / size);
						// Half-height skies hold only the sky; full-height ones have the horizon at their middle.
						int skyRows = face.height() * 2 <= face.width() + 2 ? face.height() : face.height() / 2;
						int ty = Math.min(skyRows - 1, y * skyRows / horizon);
						colour = face.pixels()[ty * face.width() + tx];
						// Fade the top edge into the plain colour of the face above.
						float t = 1.0F - y / (size * 0.12F);
						if (t > 0.0F) {
							colour = mix(colour, topColour, Math.min(1.0F, t));
						}
						// And the bottom edge into the horizon colour, the same all round.
						float b = (y - horizon * 0.9F) / (horizon * 0.1F);
						if (b > 0.0F) {
							colour = mix(colour, bottomColour, Math.min(1.0F, b));
						}
					} else {
						colour = mix(bottomColour, haze, Math.min(1.0F, (y - horizon) / (size * 0.15F)));
					}
					side[y * size + x] = colour | 0xFF000000;
				}
			}
			int[] mirrored = new int[size * size];
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					mirrored[y * size + x] = side[y * size + size - 1 - x];
				}
			}
			int[] up = new int[size * size];
			java.util.Arrays.fill(up, topColour | 0xFF000000);
			int[] down = new int[size * size];
			java.util.Arrays.fill(down, haze | 0xFF000000);
			Path folder = packRoot.resolve("assets/minecraft/textures/gui/title/background");
			Files.createDirectories(folder);
			BlockTextures.writePng(folder.resolve("panorama_0.png"), side, size);
			BlockTextures.writePng(folder.resolve("panorama_1.png"), mirrored, size);
			BlockTextures.writePng(folder.resolve("panorama_2.png"), side, size);
			BlockTextures.writePng(folder.resolve("panorama_3.png"), mirrored, size);
			BlockTextures.writePng(folder.resolve("panorama_4.png"), up, size);
			BlockTextures.writePng(folder.resolve("panorama_5.png"), down, size);
			return sky + "ft";
		}
		return "(no Half-Life 2 skybox found)";
	}

	private static int rowAverage(com.mcsource.model.RiggedModel.Texture face, int row) {
		long r = 0;
		long g = 0;
		long b = 0;
		for (int x = 0; x < face.width(); x++) {
			int p = face.pixels()[row * face.width() + x];
			r += p >> 16 & 255;
			g += p >> 8 & 255;
			b += p & 255;
		}
		int n = face.width();
		return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	private static final int PANORAMA_SIZE = 512;

	private static int mix(int a, int b, float t) {
		int out = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			float c = (a >> shift & 255) * (1.0F - t) + (b >> shift & 255) * t;
			out |= Math.round(c) << shift;
		}
		return out;
	}

	private static int darken(int colour, float factor) {
		return mix(0, colour, factor);
	}

	// Half-Life 2's VGUI scheme: flat dark panels with thin lighter borders.
	private static final int PANEL = 0xF0383634;
	private static final int SLOT = 0xFF1E1D1C;
	private static final int SLOT_EDGE = 0xFF4A4846;

	/**
	 * Buttons, sliders and text boxes drawn flat in Half-Life 2's style, the
	 * same size as Minecraft's so its nine-slice settings still fit: a dark
	 * translucent fill and a one-pixel border that brightens on hover. Null
	 * for other textures.
	 */
	static int[] flatWidget(String path, int w, int h) {
		String name = path.substring(path.lastIndexOf('/') + 1).replace(".png", "");
		if (name.equals("menu_background") || name.equals("inworld_menu_background")) {
			// The dark veil over the world behind menus, thicker so the menus read as panels.
			return box(w, h, 0x9A141312, 0x9A141312);
		}
		if (!path.contains("/widget/")) {
			return null;
		}
		boolean hover = name.contains("highlighted");
		return switch (name) {
			case "button", "button_highlighted", "realms_button", "realms_button_highlighted" ->
				box(w, h, hover ? 0xE6585654 : 0xD2403E3C, hover ? 0xFFC8C4BE : 0xFF6E6A66);
			case "button_disabled", "realms_button_disabled" -> box(w, h, 0xB42A2928, 0xFF403E3C);
			case "slider", "slider_highlighted" -> box(w, h, 0xD21A1918, hover ? 0xFFC8C4BE : 0xFF5A5652);
			case "slider_handle", "slider_handle_highlighted" -> box(w, h, hover ? 0xFFA09C96 : 0xFF7C7874, hover ? 0xFFE6E2DC : 0xFFB4B0AA);
			case "text_field", "text_field_highlighted" -> box(w, h, 0xE6141312, hover ? 0xFFDCD8D2 : 0xFF6E6A66);
			case "scroller" -> box(w, h, 0xFF6E6A66, 0xFF8C8884);
			case "scroller_background" -> box(w, h, 0xB4141312, 0xFF3C3A38);
			default -> null;
		};
	}

	private static int[] box(int w, int h, int fill, int border) {
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
				out[y * w + x] = edge ? border : fill;
			}
		}
		return out;
	}

	/**
	 * Darkens a grey pixel (ARGB); coloured ones are kept. With {@code flat},
	 * Minecraft's exact panel greys are flattened first: the white and dark grey bevels round
	 * panels and slots become plain panel colour and a thin slot border, so
	 * inventories look flat like Half-Life 2's dialogs instead of embossed.
	 */
	static int recolor(int argb, int lift, boolean flat) {
		int a = argb >>> 24;
		int r = argb >> 16 & 0xFF;
		int g = argb >> 8 & 0xFF;
		int b = argb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		if (a == 0 || max - min > 14) {
			return argb;
		}
		if (flat && a == 255 && max == min) {
			switch (max) {
				case 198, 255, 85 -> {
					return PANEL;
				}
				case 139 -> {
					return SLOT;
				}
				case 55 -> {
					return SLOT_EDGE;
				}
				default -> {
				}
			}
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
