package com.mcsource.model.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes a Valve texture ({@code .vtf}) to ARGB pixels: the first frame of
 * the largest mipmap no wider than a limit, in the common uncompressed and
 * DXT formats. Written for this mod from public descriptions of the format.
 */
public final class Vtf {
	private static final int RGBA8888 = 0;
	private static final int ABGR8888 = 1;
	private static final int RGB888 = 2;
	private static final int BGR888 = 3;
	private static final int RGB565 = 4;
	private static final int I8 = 5;
	private static final int IA88 = 6;
	private static final int A8 = 8;
	private static final int RGB888_BLUESCREEN = 9;
	private static final int BGR888_BLUESCREEN = 10;
	private static final int ARGB8888 = 11;
	private static final int BGRA8888 = 12;
	private static final int DXT1 = 13;
	private static final int DXT3 = 14;
	private static final int DXT5 = 15;
	private static final int BGRX8888 = 16;
	private static final int BGR565 = 17;
	private static final int BGRX5551 = 18;
	private static final int BGRA4444 = 19;
	private static final int DXT1_ONEBITALPHA = 20;
	private static final int BGRA5551 = 21;
	private static final int UV88 = 22;
	private static final int UVWQ8888 = 23;

	private static final int FLAG_ENVMAP = 0x4000;

	public final int width;
	public final int height;
	/** ARGB, row by row. */
	public final int[] pixels;

	private Vtf(int width, int height, int[] pixels) {
		this.width = width;
		this.height = height;
		this.pixels = pixels;
	}

	public static Vtf decode(byte[] bytes, int maxSize) throws IOException {
		ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (bytes.length < 64 || data.getInt(0) != 0x00465456) { // "VTF\0"
			throw new IOException("Not a VTF texture");
		}
		int minor = data.getInt(8);
		int headerSize = data.getInt(12);
		int width = Short.toUnsignedInt(data.getShort(16));
		int height = Short.toUnsignedInt(data.getShort(18));
		int flags = data.getInt(20);
		int frames = Math.max(1, Short.toUnsignedInt(data.getShort(24)));
		int format = data.getInt(52);
		int mipCount = Math.max(1, Byte.toUnsignedInt(data.get(56)));
		int lowFormat = data.getInt(57);
		int lowWidth = Byte.toUnsignedInt(data.get(61));
		int lowHeight = Byte.toUnsignedInt(data.get(62));
		int depth = minor >= 2 ? Math.max(1, Short.toUnsignedInt(data.getShort(63))) : 1;
		int faces = (flags & FLAG_ENVMAP) != 0 ? 6 : 1;

		long start;
		if (minor >= 3) {
			int resources = data.getInt(68);
			start = -1;
			for (int i = 0; i < resources; i++) {
				int at = 80 + i * 8;
				if ((data.getInt(at) & 0xFFFFFF) == 0x30) {
					start = Integer.toUnsignedLong(data.getInt(at + 4));
				}
			}
			if (start < 0) {
				throw new IOException("VTF has no image");
			}
		} else {
			start = headerSize + (lowFormat < 0 || lowWidth == 0 ? 0 : size(lowFormat, lowWidth, lowHeight));
		}

		// Mipmaps are stored smallest first; skip down to the one wanted.
		int mip = 0;
		while (mip < mipCount - 1 && Math.max(width >> mip, height >> mip) > maxSize) {
			mip++;
		}
		long offset = start;
		for (int m = mipCount - 1; m > mip; m--) {
			offset += (long) size(format, Math.max(1, width >> m), Math.max(1, height >> m)) * frames * faces * depth;
		}
		int w = Math.max(1, width >> mip);
		int h = Math.max(1, height >> mip);
		if (offset + size(format, w, h) > bytes.length) {
			throw new IOException("VTF image data out of range");
		}
		return new Vtf(w, h, pixels(data, (int) offset, format, w, h));
	}

	private static int bytesPerPixel(int format) throws IOException {
		return switch (format) {
			case RGBA8888, ABGR8888, ARGB8888, BGRA8888, BGRX8888, UVWQ8888 -> 4;
			case RGB888, BGR888, RGB888_BLUESCREEN, BGR888_BLUESCREEN -> 3;
			case RGB565, BGR565, BGRX5551, BGRA4444, BGRA5551, IA88, UV88 -> 2;
			case I8, A8 -> 1;
			default -> throw new IOException("Unsupported VTF format " + format);
		};
	}

	static int size(int format, int w, int h) throws IOException {
		return switch (format) {
			case DXT1, DXT1_ONEBITALPHA -> Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4) * 8;
			case DXT3, DXT5 -> Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4) * 16;
			default -> w * h * bytesPerPixel(format);
		};
	}

	private static int argb(int a, int r, int g, int b) {
		return a << 24 | r << 16 | g << 8 | b;
	}

	private static int[] pixels(ByteBuffer data, int offset, int format, int w, int h) throws IOException {
		int[] out = new int[w * h];
		switch (format) {
			case DXT1, DXT1_ONEBITALPHA, DXT3, DXT5 -> dxt(data, offset, format, w, h, out);
			default -> {
				int bpp = bytesPerPixel(format);
				for (int i = 0; i < out.length; i++) {
					int p = offset + i * bpp;
					int b0 = Byte.toUnsignedInt(data.get(p));
					int b1 = bpp > 1 ? Byte.toUnsignedInt(data.get(p + 1)) : 0;
					int b2 = bpp > 2 ? Byte.toUnsignedInt(data.get(p + 2)) : 0;
					int b3 = bpp > 3 ? Byte.toUnsignedInt(data.get(p + 3)) : 255;
					int v16 = b0 | b1 << 8;
					out[i] = switch (format) {
						case RGBA8888, UVWQ8888 -> argb(b3, b0, b1, b2);
						case ABGR8888 -> argb(b0, b3, b2, b1);
						case ARGB8888 -> argb(b0, b1, b2, b3);
						case BGRA8888 -> argb(b3, b2, b1, b0);
						case BGRX8888 -> argb(255, b2, b1, b0);
						case RGB888, RGB888_BLUESCREEN -> argb(255, b0, b1, b2);
						case BGR888, BGR888_BLUESCREEN -> argb(255, b2, b1, b0);
						case RGB565 -> argb(255, (v16 & 31) * 255 / 31, (v16 >> 5 & 63) * 255 / 63, (v16 >> 11) * 255 / 31);
						case BGR565 -> argb(255, (v16 >> 11) * 255 / 31, (v16 >> 5 & 63) * 255 / 63, (v16 & 31) * 255 / 31);
						case BGRX5551 -> argb(255, (v16 >> 10 & 31) * 255 / 31, (v16 >> 5 & 31) * 255 / 31, (v16 & 31) * 255 / 31);
						case BGRA5551 -> argb((v16 >> 15) * 255, (v16 >> 10 & 31) * 255 / 31, (v16 >> 5 & 31) * 255 / 31, (v16 & 31) * 255 / 31);
						case BGRA4444 -> argb((v16 >> 12) * 17, (v16 >> 8 & 15) * 17, (v16 >> 4 & 15) * 17, (v16 & 15) * 17);
						case I8 -> argb(255, b0, b0, b0);
						case IA88 -> argb(b1, b0, b0, b0);
						case A8 -> argb(b0, 255, 255, 255);
						case UV88 -> argb(255, b0, b1, 0);
						default -> 0;
					};
				}
			}
		}
		return out;
	}

	private static void dxt(ByteBuffer data, int offset, int format, int w, int h, int[] out) {
		int blockBytes = format == DXT1 || format == DXT1_ONEBITALPHA ? 8 : 16;
		int blocksWide = Math.max(1, (w + 3) / 4);
		int blocksHigh = Math.max(1, (h + 3) / 4);
		int[] colors = new int[4];
		int[] alphas = new int[16];
		for (int by = 0; by < blocksHigh; by++) {
			for (int bx = 0; bx < blocksWide; bx++) {
				int block = offset + (by * blocksWide + bx) * blockBytes;
				int colorAt = block + (blockBytes == 16 ? 8 : 0);
				java.util.Arrays.fill(alphas, 255);
				if (format == DXT3) {
					long bits = data.getLong(block);
					for (int i = 0; i < 16; i++) {
						alphas[i] = (int) (bits >>> (i * 4) & 15) * 17;
					}
				} else if (format == DXT5) {
					int a0 = Byte.toUnsignedInt(data.get(block));
					int a1 = Byte.toUnsignedInt(data.get(block + 1));
					long bits = 0;
					for (int i = 0; i < 6; i++) {
						bits |= (long) Byte.toUnsignedInt(data.get(block + 2 + i)) << (8 * i);
					}
					for (int i = 0; i < 16; i++) {
						int code = (int) (bits >>> (3 * i) & 7);
						int a;
						if (code == 0) {
							a = a0;
						} else if (code == 1) {
							a = a1;
						} else if (a0 > a1) {
							a = ((8 - code) * a0 + (code - 1) * a1) / 7;
						} else if (code == 6) {
							a = 0;
						} else if (code == 7) {
							a = 255;
						} else {
							a = ((6 - code) * a0 + (code - 1) * a1) / 5;
						}
						alphas[i] = a;
					}
				}
				int c0 = Short.toUnsignedInt(data.getShort(colorAt));
				int c1 = Short.toUnsignedInt(data.getShort(colorAt + 2));
				int[] rgb0 = rgb565(c0);
				int[] rgb1 = rgb565(c1);
				colors[0] = argb(255, rgb0[0], rgb0[1], rgb0[2]);
				colors[1] = argb(255, rgb1[0], rgb1[1], rgb1[2]);
				if (c0 > c1 || blockBytes == 16) {
					colors[2] = argb(255, (2 * rgb0[0] + rgb1[0]) / 3, (2 * rgb0[1] + rgb1[1]) / 3, (2 * rgb0[2] + rgb1[2]) / 3);
					colors[3] = argb(255, (rgb0[0] + 2 * rgb1[0]) / 3, (rgb0[1] + 2 * rgb1[1]) / 3, (rgb0[2] + 2 * rgb1[2]) / 3);
				} else {
					colors[2] = argb(255, (rgb0[0] + rgb1[0]) / 2, (rgb0[1] + rgb1[1]) / 2, (rgb0[2] + rgb1[2]) / 2);
					// Three-colour blocks: the fourth colour is transparent, as Direct3D decodes DXT1.
					colors[3] = 0;
				}
				int indices = data.getInt(colorAt + 4);
				for (int i = 0; i < 16; i++) {
					int x = bx * 4 + (i & 3);
					int y = by * 4 + (i >> 2);
					if (x >= w || y >= h) {
						continue;
					}
					int c = colors[indices >>> (2 * i) & 3];
					out[y * w + x] = (c & 0xFFFFFF) | (Math.min(c >>> 24, alphas[i]) << 24);
				}
			}
		}
	}

	private static int[] rgb565(int c) {
		return new int[] {(c >> 11 & 31) * 255 / 31, (c >> 5 & 63) * 255 / 63, (c & 31) * 255 / 31};
	}
}
