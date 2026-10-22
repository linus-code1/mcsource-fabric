package com.mcsource.client.mob;

import java.util.function.Supplier;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A repeating, smoothly filtered texture with mipmaps, as Source draws its
 * model textures. Without them, leaves and other cut-out detail sparkle and
 * flicker from one frame to the next once they are a few pixels across on
 * screen.
 */
public final class MippedTexture extends AbstractTexture {
	public MippedTexture(Supplier<String> label, NativeImage image) {
		int levels = 1;
		while (levels < 6 && image.getWidth() >> levels >= 1 && image.getHeight() >> levels >= 1) {
			levels++;
		}
		NativeImage[] mips = new NativeImage[levels];
		mips[0] = image;
		for (int level = 1; level < levels; level++) {
			mips[level] = half(mips[level - 1]);
		}
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(label, 5, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, levels);
		textureView = device.createTextureView(texture);
		sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
		for (int level = 0; level < levels; level++) {
			device.createCommandEncoder().writeToTexture(texture, mips[level], level, 0, 0, 0);
		}
		for (NativeImage mip : mips) {
			mip.close();
		}
	}

	/**
	 * Half the size: each pixel the average of four, colours weighted by how
	 * opaque they are so see-through pixels don't darken the edges of leaves.
	 */
	private static NativeImage half(NativeImage from) {
		int width = Math.max(1, from.getWidth() / 2);
		int height = Math.max(1, from.getHeight() / 2);
		NativeImage out = new NativeImage(width, height, false);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float a = 0;
				float c0 = 0;
				float c1 = 0;
				float c2 = 0;
				int n = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int px = Math.min(from.getWidth() - 1, x * 2 + dx);
						int py = Math.min(from.getHeight() - 1, y * 2 + dy);
						int p = from.getPixel(px, py);
						float alpha = (p >>> 24) / 255.0F;
						a += alpha;
						c0 += (p >> 16 & 255) * alpha;
						c1 += (p >> 8 & 255) * alpha;
						c2 += (p & 255) * alpha;
						n++;
					}
				}
				int pixel;
				if (a <= 0.0F) {
					pixel = 0;
				} else {
					pixel = Math.round(a / n * 255.0F) << 24 | Math.round(c0 / a) << 16 | Math.round(c1 / a) << 8 | Math.round(c2 / a);
				}
				out.setPixel(x, y, pixel);
			}
		}
		return out;
	}
}
