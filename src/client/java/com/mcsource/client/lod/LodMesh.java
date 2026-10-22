package com.mcsource.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds distant terrain as rings of square cells around the player, each
 * ring twice as coarse as the one inside it: 8 blocks a cell close by, then
 * 16, 32 and 64 out to the horizon. A cell is a quad over four ground samples,
 * shaded by the same fixed sun as the blocks; cells over loaded chunks are
 * left out for the real blocks, and wherever a cell's edge has no neighbour
 * of its own size a skirt hangs down from it, so no cracks show between rings
 * or against the loaded world.
 */
final class LodMesh {
	/** Bytes a vertex takes: position as three floats, then colour as four bytes. */
	static final int VERTEX_SIZE = 16;
	private static final int[] STEPS = {8, 16, 32, 64};
	/** Coarse cells of the land carried on from what is known, on a server. */
	private static final int FIELD = 64;

	// The fixed sun of SunLight, and its split of light into sky fill and sun.
	private static final float SUN_X;
	private static final float SUN_Y;
	private static final float SUN_Z;
	private static final float AMBIENT = 0.7F;
	private static final float DIRECT = 0.6F;
	private static final float[] FILL = {0.78F, 0.87F, 1.06F};
	private static final float[] SUN = {1.06F, 0.98F, 0.86F};
	/** Overall brightness, to meet the real blocks where the two join. */
	private static final float EXPOSURE = 0.82F;
	/** Distant terrain sits this far below the real ground, so it never pokes through it. */
	private static final float SINK = 0.4F;

	static {
		// The same sun as SunLight.
		float x = -0.62F;
		float y = 0.62F;
		float z = 0.35F;
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		SUN_X = x / length;
		SUN_Y = y / length;
		SUN_Z = z / length;
	}

	final int originX;
	final int originZ;
	final int changes;
	ByteBuffer data;
	int quads;

	private final @Nullable ServerLevel server;
	private final int sea;
	private Field field;

	/** Cells over loaded chunks nearer the origin than this are covered by the real chunks and left out. */
	private float cut;

	private LodMesh(int originX, int originZ, int changes, @Nullable ServerLevel server) {
		this.originX = originX;
		this.originZ = originZ;
		this.changes = changes;
		this.server = server;
		this.sea = LodTerrain.seaLevel;
		this.data = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
	}

	/** Rings of the given half-widths, finest first; the last reaches the full radius. */
	static int[] radii(int renderDistanceBlocks) {
		int near = Math.max(256, round64(renderDistanceBlocks / 2 + 64));
		int middle = Math.max(near * 2, round64(renderDistanceBlocks + 96));
		int far = Math.max(middle * 2, 1024);
		return new int[] {near, middle, far, LodTerrain.RADIUS};
	}

	private static int round64(int value) {
		return (value + 63) / 64 * 64;
	}

	static LodMesh build(int originX, int originZ, int renderDistanceBlocks, int changes) {
		LodMesh mesh = new LodMesh(originX, originZ, changes, LodTerrain.serverLevel);
		if (mesh.server == null) {
			mesh.field = Field.build(originX, originZ, mesh.sea);
		}
		// The camera may be up to a snap away from the origin before the mesh is rebuilt.
		mesh.cut = LodRenderer.innerRadius(renderDistanceBlocks / 16) - LodRenderer.SNAP * 1.5F;
		int[] radii = radii(renderDistanceBlocks);
		int inner = 0;
		for (int level = 0; level < STEPS.length; level++) {
			mesh.ring(STEPS[level], inner, radii[level]);
			inner = radii[level];
		}
		mesh.data.flip();
		return mesh;
	}

	/** The ground at a block column, packed: from the chunk if it was seen, else predicted. */
	private long ground(int x, int z) {
		int[] column = LodTerrain.COLUMNS.get(net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4));
		if (column != null) {
			int index = ((x & 15) >> 2) + ((z & 15) >> 2) * LodTerrain.SAMPLES;
			int height = column[index];
			if (height != LodTerrain.UNKNOWN) {
				return LodTerrain.pack(height, column[LodTerrain.SAMPLES * LodTerrain.SAMPLES + index]);
			}
		}
		if (server != null) {
			return LodTerrain.predict(server, x, z);
		}
		return field.sample(x, z);
	}

	private void ring(int step, int inner, int outer) {
		int n = outer * 2 / step;
		int startX = originX - outer;
		int startZ = originZ - outer;
		float[] heights = new float[(n + 1) * (n + 1)];
		int[] colours = new int[(n + 1) * (n + 1)];
		for (int j = 0; j <= n; j++) {
			for (int i = 0; i <= n; i++) {
				int x = startX + i * step;
				int z = startZ + j * step;
				// Skip the samples deep inside the inner ring, which no cell here uses.
				int dx = Math.abs(x - originX);
				int dz = Math.abs(z - originZ);
				if (dx < inner - step && dz < inner - step) {
					continue;
				}
				long packed = ground(x, z);
				heights[i + j * (n + 1)] = (int) (packed >> 32);
				colours[i + j * (n + 1)] = (int) packed;
			}
		}
		boolean[] drawn = new boolean[n * n];
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int x0 = startX + i * step;
				int z0 = startZ + j * step;
				int x1 = x0 + step;
				int z1 = z0 + step;
				boolean insideInner = x0 >= originX - inner && x1 <= originX + inner && z0 >= originZ - inner && z1 <= originZ + inner;
				// Cells over loaded chunks are left out only well inside the
				// circle the real chunks are drawn in; nearer its edge they are
				// drawn and cut away exactly at the circle as they are drawn.
				drawn[i + j * n] = !insideInner && !(loaded(x0, z0, step) && farthest(x0, z0, x1, z1) < cut);
			}
		}
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				if (!drawn[i + j * n]) {
					continue;
				}
				int a = i + j * (n + 1);
				int b = a + 1;
				int c = a + (n + 1) + 1;
				int d = a + (n + 1);
				float x0 = startX + i * step - originX;
				float z0 = startZ + j * step - originZ;
				float x1 = x0 + step;
				float z1 = z0 + step;
				int ca = shade(heights, colours, n, i, j, step);
				int cb = shade(heights, colours, n, i + 1, j, step);
				int cc = shade(heights, colours, n, i + 1, j + 1, step);
				int cd = shade(heights, colours, n, i, j + 1, step);
				float ha = heights[a] - SINK;
				float hb = heights[b] - SINK;
				float hc = heights[c] - SINK;
				float hd = heights[d] - SINK;
				quad(x0, ha, z0, ca, x0, hd, z1, cd, x1, hc, z1, cc, x1, hb, z0, cb);
				float drop = step + 6.0F;
				if (j == 0 || !drawn[i + (j - 1) * n]) {
					skirt(x0, ha, z0, ca, x1, hb, z0, cb, drop);
				}
				if (j == n - 1 || !drawn[i + (j + 1) * n]) {
					skirt(x0, hd, z1, cd, x1, hc, z1, cc, drop);
				}
				if (i == 0 || !drawn[i - 1 + j * n]) {
					skirt(x0, ha, z0, ca, x0, hd, z1, cd, drop);
				}
				if (i == n - 1 || !drawn[i + 1 + j * n]) {
					skirt(x1, hb, z0, cb, x1, hc, z1, cc, drop);
				}
			}
		}
	}

	/** The greatest distance across the ground from the mesh's origin to a cell's corners. */
	private float farthest(int x0, int z0, int x1, int z1) {
		float dx = Math.max(Math.abs(x0 - originX), Math.abs(x1 - originX));
		float dz = Math.max(Math.abs(z0 - originZ), Math.abs(z1 - originZ));
		return (float) Math.sqrt(dx * dx + dz * dz);
	}

	/** Whether the real blocks cover a cell: its chunk is loaded, or all of them for a big cell. */
	private static boolean loaded(int x0, int z0, int step) {
		if (step <= 16) {
			return LodTerrain.LOADED.contains(net.minecraft.world.level.ChunkPos.pack(x0 >> 4, z0 >> 4));
		}
		for (int cz = z0 >> 4; cz < (z0 + step) >> 4; cz++) {
			for (int cx = x0 >> 4; cx < (x0 + step) >> 4; cx++) {
				if (!LodTerrain.LOADED.contains(net.minecraft.world.level.ChunkPos.pack(cx, cz))) {
					return false;
				}
			}
		}
		return true;
	}

	/** A sample's colour lit by the fixed sun, by the slope of the ground around it. */
	private int shade(float[] heights, int[] colours, int n, int i, int j, int step) {
		int row = n + 1;
		float here = heights[i + j * row];
		float left = i > 0 ? heights[i - 1 + j * row] : here;
		float right = i < n ? heights[i + 1 + j * row] : here;
		float back = j > 0 ? heights[i + (j - 1) * row] : here;
		float front = j < n ? heights[i + (j + 1) * row] : here;
		float spanX = (i > 0 && i < n ? 2 : 1) * step;
		float spanZ = (j > 0 && j < n ? 2 : 1) * step;
		float nx = -(right - left) / spanX;
		float nz = -(front - back) / spanZ;
		float length = (float) Math.sqrt(nx * nx + 1.0F + nz * nz);
		float facing = Math.max(0.0F, (nx * SUN_X + SUN_Y + nz * SUN_Z) / length);
		int colour = colours[i + j * row];
		float r = ARGB.red(colour) / 255.0F * (AMBIENT * FILL[0] + DIRECT * SUN[0] * facing) * EXPOSURE;
		float g = ARGB.green(colour) / 255.0F * (AMBIENT * FILL[1] + DIRECT * SUN[1] * facing) * EXPOSURE;
		float b = ARGB.blue(colour) / 255.0F * (AMBIENT * FILL[2] + DIRECT * SUN[2] * facing) * EXPOSURE;
		return ARGB.colorFromFloat(1.0F, Math.min(r, 1.0F), Math.min(g, 1.0F), Math.min(b, 1.0F));
	}

	private void skirt(float xa, float ya, float za, int ca, float xb, float yb, float zb, int cb, float drop) {
		int da = ARGB.scaleRGB(ca, 0.75F);
		int db = ARGB.scaleRGB(cb, 0.75F);
		quad(xa, ya, za, ca, xb, yb, zb, cb, xb, yb - drop, zb, db, xa, ya - drop, za, da);
	}

	private void quad(float x0, float y0, float z0, int c0, float x1, float y1, float z1, int c1,
			float x2, float y2, float z2, int c2, float x3, float y3, float z3, int c3) {
		if (data.remaining() < 4 * VERTEX_SIZE) {
			ByteBuffer bigger = ByteBuffer.allocateDirect(data.capacity() * 2).order(ByteOrder.nativeOrder());
			data.flip();
			bigger.put(data);
			data = bigger;
		}
		vertex(x0, y0, z0, c0);
		vertex(x1, y1, z1, c1);
		vertex(x2, y2, z2, c2);
		vertex(x3, y3, z3, c3);
		quads++;
	}

	private void vertex(float x, float y, float z, int colour) {
		data.putFloat(x).putFloat(y).putFloat(z);
		data.put((byte) ARGB.red(colour)).put((byte) ARGB.green(colour)).put((byte) ARGB.blue(colour)).put((byte) 255);
	}

	/**
	 * The land on a server, where there is no generator to ask: what has been
	 * seen, averaged into coarse cells, then carried outwards cell by cell
	 * into the unknown, easing towards a little above sea level, with gentle
	 * made-up hills so it doesn't read as a flat plain.
	 */
	private static final class Field {
		private final int startX;
		private final int startZ;
		private final int size;
		private final float[] heights;
		private final int[] colours;
		/** How many cells away from anything seen each cell is. */
		private final int[] distance;

		private Field(int startX, int startZ, int size) {
			this.startX = startX;
			this.startZ = startZ;
			this.size = size;
			this.heights = new float[size * size];
			this.colours = new int[size * size];
			this.distance = new int[size * size];
		}

		static Field build(int originX, int originZ, int sea) {
			int reach = LodTerrain.RADIUS + FIELD * 2;
			int size = reach * 2 / FIELD + 1;
			Field field = new Field(originX - reach, originZ - reach, size);
			int chunks = FIELD / 16;
			double total = 0;
			int counted = 0;
			long red = 0;
			long green = 0;
			long blue = 0;
			for (int j = 0; j < size; j++) {
				for (int i = 0; i < size; i++) {
					int cell = i + j * size;
					int baseChunkX = (field.startX + i * FIELD - FIELD / 2) >> 4;
					int baseChunkZ = (field.startZ + j * FIELD - FIELD / 2) >> 4;
					float sum = 0;
					int r = 0;
					int g = 0;
					int b = 0;
					int n = 0;
					for (int cz = 0; cz < chunks; cz++) {
						for (int cx = 0; cx < chunks; cx++) {
							int[] column = LodTerrain.COLUMNS.get(net.minecraft.world.level.ChunkPos.pack(baseChunkX + cx, baseChunkZ + cz));
							if (column == null) {
								continue;
							}
							int index = 5;
							if (column[index] == LodTerrain.UNKNOWN) {
								continue;
							}
							sum += column[index];
							int colour = column[LodTerrain.SAMPLES * LodTerrain.SAMPLES + index];
							r += ARGB.red(colour);
							g += ARGB.green(colour);
							b += ARGB.blue(colour);
							n++;
						}
					}
					if (n > 0) {
						field.heights[cell] = sum / n;
						field.colours[cell] = ARGB.color(255, r / n, g / n, b / n);
						field.distance[cell] = 0;
						total += sum / n;
						red += r / n;
						green += g / n;
						blue += b / n;
						counted++;
					} else {
						field.distance[cell] = Integer.MAX_VALUE;
					}
				}
			}
			float base = sea + 4.0F;
			int baseColour = LodTerrain.PALETTE.getOrDefault(Blocks.GRASS_BLOCK, 0xFF8F8F8F);
			baseColour = ARGB.multiply(baseColour, 0xFF79C05A);
			if (counted > 0) {
				base = (float) (total / counted) * 0.5F + base * 0.5F;
				baseColour = ARGB.color(255, (int) (red / counted), (int) (green / counted), (int) (blue / counted));
			}
			if (counted == 0) {
				for (int cell = 0; cell < size * size; cell++) {
					field.heights[cell] = base;
					field.colours[cell] = baseColour;
					field.distance[cell] = 64;
				}
				return field;
			}
			// Carry the known land outwards one cell at a time.
			float[] nextHeights = field.heights.clone();
			int[] nextColours = field.colours.clone();
			for (int pass = 1; pass < size; pass++) {
				boolean grew = false;
				for (int j = 0; j < size; j++) {
					for (int i = 0; i < size; i++) {
						int cell = i + j * size;
						if (field.distance[cell] != Integer.MAX_VALUE) {
							continue;
						}
						float sum = 0;
						int r = 0;
						int g = 0;
						int b = 0;
						int n = 0;
						for (int dj = -1; dj <= 1; dj++) {
							for (int di = -1; di <= 1; di++) {
								int ni = i + di;
								int nj = j + dj;
								if (ni < 0 || nj < 0 || ni >= size || nj >= size) {
									continue;
								}
								int other = ni + nj * size;
								if (field.distance[other] >= pass) {
									continue;
								}
								sum += field.heights[other];
								int colour = field.colours[other];
								r += ARGB.red(colour);
								g += ARGB.green(colour);
								b += ARGB.blue(colour);
								n++;
							}
						}
						if (n == 0) {
							continue;
						}
						nextHeights[cell] = sum / n + (base - sum / n) * 0.12F;
						nextColours[cell] = ARGB.srgbLerp(0.05F, ARGB.color(255, r / n, g / n, b / n), baseColour);
						field.distance[cell] = pass;
						grew = true;
					}
				}
				System.arraycopy(nextHeights, 0, field.heights, 0, field.heights.length);
				System.arraycopy(nextColours, 0, field.colours, 0, field.colours.length);
				if (!grew) {
					break;
				}
			}
			return field;
		}

		long sample(int x, int z) {
			float fx = (x - startX) / (float) FIELD;
			float fz = (z - startZ) / (float) FIELD;
			int i = Math.max(0, Math.min(size - 2, (int) Math.floor(fx)));
			int j = Math.max(0, Math.min(size - 2, (int) Math.floor(fz)));
			float u = Math.max(0.0F, Math.min(1.0F, fx - i));
			float v = Math.max(0.0F, Math.min(1.0F, fz - j));
			int a = i + j * size;
			int b = a + 1;
			int c = a + size;
			int d = c + 1;
			float height = (heights[a] * (1 - u) + heights[b] * u) * (1 - v) + (heights[c] * (1 - u) + heights[d] * u) * v;
			// Made-up hills, growing the further the land is from anything seen.
			float away = Math.min(Math.min(distance[a], distance[d]), 6) / 6.0F;
			height += away * (hills(x, z) * 14.0F);
			int colour = v < 0.5F ? (u < 0.5F ? colours[a] : colours[b]) : (u < 0.5F ? colours[c] : colours[d]);
			return LodTerrain.pack(Math.round(height), colour);
		}

		/** Smooth value noise between -1 and 1, at the scale of a few hundred blocks. */
		private static float hills(int x, int z) {
			return 0.65F * noise(x / 256.0F, z / 256.0F) + 0.35F * noise(x / 96.0F + 17.3F, z / 96.0F - 4.1F);
		}

		private static float noise(float x, float z) {
			int ix = (int) Math.floor(x);
			int iz = (int) Math.floor(z);
			float fx = x - ix;
			float fz = z - iz;
			fx = fx * fx * (3 - 2 * fx);
			fz = fz * fz * (3 - 2 * fz);
			float a = hash(ix, iz);
			float b = hash(ix + 1, iz);
			float c = hash(ix, iz + 1);
			float d = hash(ix + 1, iz + 1);
			return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
		}

		private static float hash(int x, int z) {
			int h = x * 374761393 + z * 668265263;
			h = (h ^ h >>> 13) * 1274126177;
			h ^= h >>> 16;
			return (h & 0xFFFF) / 32767.5F - 1.0F;
		}
	}
}
