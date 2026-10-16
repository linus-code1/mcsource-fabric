package com.mcsource.model.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mcsource.model.RiggedModel;

/**
 * The sun and moon in the style of Half-Life 2's env_sun: a small white-hot
 * disc in a wide soft glow, the glow taken from Half-Life 2's own glow sprite
 * when one is found. Half-Life 2 has no moon, so the moon is the same glow,
 * cooler and dimmer, with Minecraft's phases cut out of its disc. Optionally
 * the sun gets a scary face.
 */
public final class SkyTextures {
	/** Glow sprites tried in order, as in env_sun's overlay material. */
	private static final String[] GLOWS = {"sprites/light_glow02_add_noz", "sprites/light_glow02_add", "sprites/light_glow02",
		"sprites/light_glow03", "sprites/glow01"};
	private static final int SIZE = 128;
	/** Radius of the bright disc, as a share of half the image. */
	private static final float DISC = 0.2F;
	/** A scary sun is drawn bigger so its face can be seen. */
	private static final float SCARY_DISC = 0.45F;
	/** Minecraft's moon phases, in the order of its {@code MoonPhase} names. */
	private static final String[] PHASES = {"full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon",
		"waxing_crescent", "first_quarter", "waxing_gibbous"};

	private SkyTextures() {
	}

	/**
	 * Writes the sun and moon textures into the pack; returns the glow sprite
	 * used, or null if the glow was drawn without one.
	 */
	public static String generate(GameFiles files, Path packRoot, boolean scary) throws IOException {
		float[] glow = null;
		String chosen = null;
		for (String material : GLOWS) {
			try {
				RiggedModel.Texture texture = Materials.load(files, material, 256, true);
				if (texture != null) {
					glow = brightness(texture);
					chosen = material;
					break;
				}
			} catch (IOException | RuntimeException e) {
				// Try the next one.
			}
		}
		if (glow == null) {
			glow = drawnGlow();
		}
		Path folder = packRoot.resolve("assets/minecraft/textures/environment/celestial");
		Files.createDirectories(folder.resolve("moon"));

		int[] sun = new int[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float r = radius(x, y);
				float disc = disc(r, scary ? SCARY_DISC : DISC);
				float halo = glow[y * SIZE + x];
				float face = scary ? face(x, y) : 1.0F;
				// White-hot middle fading out through a warm glow.
				float red = Math.min(1.0F, halo * 1.0F + disc) * face;
				float green = Math.min(1.0F, halo * 0.92F + disc) * face;
				float blue = Math.min(1.0F, halo * 0.75F + disc * 0.95F) * face;
				sun[y * SIZE + x] = pixel(red, green, blue);
			}
		}
		BlockTextures.writePng(folder.resolve("sun.png"), sun, SIZE);

		for (int phase = 0; phase < PHASES.length; phase++) {
			int[] moon = new int[SIZE * SIZE];
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					float lit = lit(x, y, phase);
					// Only the lit part of the disc shines, and the glow fades towards new moon.
					float fullness = 1.0F - Math.min(phase, PHASES.length - phase) / 4.0F;
					float disc = disc(radius(x, y), DISC) * lit * 0.85F;
					float halo = glow[y * SIZE + x] * 0.35F * fullness;
					moon[y * SIZE + x] = pixel(Math.min(1.0F, halo * 0.7F + disc * 0.9F), Math.min(1.0F, halo * 0.8F + disc * 0.95F),
							Math.min(1.0F, halo + disc));
				}
			}
			BlockTextures.writePng(folder.resolve("moon/" + PHASES[phase] + ".png"), moon, SIZE);
		}
		return chosen;
	}

	private static float radius(int x, int y) {
		float dx = (x + 0.5F) / SIZE * 2.0F - 1.0F;
		float dy = (y + 0.5F) / SIZE * 2.0F - 1.0F;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	/** The bright disc, with an edge a couple of pixels soft. */
	private static float disc(float r, float size) {
		return clamp((size - r) / 0.03F + 0.5F);
	}

	/** Brightness of the glow sprite, resampled to {@link #SIZE} and dimmed towards the edge. */
	private static float[] brightness(RiggedModel.Texture texture) {
		float[] out = new float[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int sx = Math.min(texture.width() - 1, x * texture.width() / SIZE);
				int sy = Math.min(texture.height() - 1, y * texture.height() / SIZE);
				int p = texture.pixels()[sy * texture.width() + sx];
				float a = (p >>> 24) / 255.0F;
				float v = Math.max(p >> 16 & 0xFF, Math.max(p >> 8 & 0xFF, p & 0xFF)) / 255.0F;
				// Additive sprites keep their shape in colour; blended ones in alpha.
				float value = texture.masked() && a < 0.99F ? Math.min(v, a) : v;
				out[y * SIZE + x] = value * clamp((1.0F - radius(x, y)) * 4.0F);
			}
		}
		return out;
	}

	/** A soft round glow, used when no glow sprite is found. */
	private static float[] drawnGlow() {
		float[] out = new float[SIZE * SIZE];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float r = radius(x, y);
				out[y * SIZE + x] = clamp(1.0F - r) * clamp(1.0F - r) * 0.9F;
			}
		}
		return out;
	}

	/** Share of the moon's disc lit at this pixel for a phase (0 full, 4 new). */
	private static float lit(int x, int y, int phase) {
		float dx = ((x + 0.5F) / SIZE * 2.0F - 1.0F) / DISC;
		float dy = ((y + 0.5F) / SIZE * 2.0F - 1.0F) / DISC;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length > 0.999F) {
			// Past the edge: as lit as the edge itself, so the disc's soft rim matches.
			dx = dx / length * 0.999F;
			dy = dy / length * 0.999F;
		}
		// The terminator is an ellipse across the disc; waning phases are lit on the left.
		float half = (float) Math.sqrt(Math.max(0.0F, 1.0F - dy * dy));
		float terminator = (float) Math.cos(phase / 8.0 * 2.0 * Math.PI) * half;
		boolean waning = phase < 4;
		float side = waning ? -dx : dx;
		return clamp((side + terminator) * SIZE * DISC / 4.0F + 0.5F);
	}

	/**
	 * Darkens the sun where a face is drawn on it: two slanted eyes and a wide
	 * jagged grin. Returns the brightness kept, 1 where there is no face.
	 */
	private static float face(int x, int y) {
		float u = ((x + 0.5F) / SIZE * 2.0F - 1.0F) / SCARY_DISC;
		float v = ((y + 0.5F) / SIZE * 2.0F - 1.0F) / SCARY_DISC;
		float dark = 0.0F;
		for (int side = -1; side <= 1; side += 2) {
			// Angry eyes: ellipses cut off by a brow slanting down to the middle.
			float ex = (u - side * 0.36F) / 0.2F;
			float ey = (v + 0.25F) / 0.14F;
			float brow = -0.4F + 0.35F * (0.56F - Math.abs(u));
			if (ex * ex + ey * ey < 1.0F && v > brow) {
				dark = 1.0F;
			}
		}
		// Grin: a wide smile, thickest in the middle, with pointed teeth top and bottom.
		float width = 0.7F;
		if (Math.abs(u) < width) {
			float curve = 0.4F - 0.3F * u * u;
			float thickness = 0.24F * (1.0F - (u / width) * (u / width));
			float teeth = Math.abs((float) Math.sin(u * 13.0F)) * 0.07F;
			float top = curve - 0.04F + teeth * 0.8F;
			float bottom = curve + thickness - teeth * 0.6F;
			if (v > top && v < bottom) {
				dark = 1.0F;
			}
		}
		return 1.0F - dark * 0.95F;
	}

	private static int pixel(float r, float g, float b) {
		int ri = Math.round(clamp(r) * 255.0F);
		int gi = Math.round(clamp(g) * 255.0F);
		int bi = Math.round(clamp(b) * 255.0F);
		int a = Math.max(ri, Math.max(gi, bi));
		return a << 24 | ri << 16 | gi << 8 | bi;
	}

	private static float clamp(float value) {
		return value < 0.0F ? 0.0F : value > 1.0F ? 1.0F : value;
	}
}
