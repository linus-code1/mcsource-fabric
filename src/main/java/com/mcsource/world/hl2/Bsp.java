package com.mcsource.world.hl2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A compiled Source engine map ({@code .bsp}, versions 19 to 21): the parts
 * needed to rebuild it in blocks. Its brushes (convex solids bounded by
 * planes) with their contents and materials, its displacements (the bent
 * terrain surfaces), which brushes belong to the world rather than to
 * moving entities, and the entities themselves. Written for this mod from
 * public descriptions of the format; no Valve code is used.
 */
public final class Bsp {
	public static final int CONTENTS_SOLID = 0x1;
	public static final int CONTENTS_WINDOW = 0x2;
	public static final int CONTENTS_GRATE = 0x8;
	public static final int CONTENTS_WATER = 0x20;
	public static final int CONTENTS_SLIME = 0x10;

	private static final int LUMP_ENTITIES = 0;
	private static final int LUMP_PLANES = 1;
	private static final int LUMP_TEXDATA = 2;
	private static final int LUMP_VERTEXES = 3;
	private static final int LUMP_NODES = 5;
	private static final int LUMP_TEXINFO = 6;
	private static final int LUMP_FACES = 7;
	private static final int LUMP_LEAFS = 10;
	private static final int LUMP_EDGES = 12;
	private static final int LUMP_SURFEDGES = 13;
	private static final int LUMP_MODELS = 14;
	private static final int LUMP_LEAFBRUSHES = 17;
	private static final int LUMP_BRUSHES = 18;
	private static final int LUMP_BRUSHSIDES = 19;
	private static final int LUMP_DISPINFO = 26;
	private static final int LUMP_DISPVERTS = 33;
	private static final int LUMP_TEXDATA_STRING_DATA = 43;
	private static final int LUMP_TEXDATA_STRING_TABLE = 44;

	/** A plane: points p with dot(normal, p) = dist; the brush lies on the side where it is less. */
	public record Plane(float nx, float ny, float nz, float dist) {
	}

	/** One side of a brush: its plane and its material (null for none). */
	public record Side(Plane plane, String material) {
	}

	public record Brush(int contents, Side[] sides) {
	}

	/** A displacement: a grid of points, row by row, {@code size} by {@code size}, and its material. */
	public record Displacement(int size, float[] points, String material) {
	}

	public final int version;
	public final List<Brush> brushes = new ArrayList<>();
	/** For each brush, the model it belongs to: 0 for the world, else a brush entity's model. */
	public final int[] brushModel;
	public final List<Displacement> displacements = new ArrayList<>();
	public final List<Map<String, String>> entities = new ArrayList<>();
	/** Bounds of the world: min x, y, z then max x, y, z. */
	public final float[] worldBounds = new float[6];

	private final ByteBuffer data;

	public Bsp(byte[] bytes) throws IOException {
		data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (bytes.length < 1036 || data.getInt(0) != 0x50534256) {
			throw new IOException("Not a Source map");
		}
		version = data.getInt(4);
		if (version < 19 || version > 21) {
			throw new IOException("Unsupported map version " + version);
		}
		for (int i = 0; i < 64; i++) {
			int length = data.getInt(8 + i * 16 + 4);
			int fourCc = data.getInt(8 + i * 16 + 12);
			if (fourCc != 0 && length > 0 && i != LUMP_ENTITIES) {
				// Compressed lumps (later games); Half-Life 2's own maps are not compressed.
				throw new IOException("Compressed maps are not supported");
			}
		}
		Plane[] planes = planes();
		String[] textures = textures();
		readBrushes(planes, textures);
		brushModel = new int[brushes.size()];
		readModels();
		readDisplacements(textures);
		readEntities();
	}

	private ByteBuffer lump(int index) {
		int offset = data.getInt(8 + index * 16);
		int length = data.getInt(8 + index * 16 + 4);
		ByteBuffer slice = data.slice(offset, length).order(ByteOrder.LITTLE_ENDIAN);
		return slice;
	}

	private Plane[] planes() {
		ByteBuffer lump = lump(LUMP_PLANES);
		Plane[] planes = new Plane[lump.limit() / 20];
		for (int i = 0; i < planes.length; i++) {
			int o = i * 20;
			planes[i] = new Plane(lump.getFloat(o), lump.getFloat(o + 4), lump.getFloat(o + 8), lump.getFloat(o + 12));
		}
		return planes;
	}

	/** The material name of each texinfo, lower case, or null. */
	private String[] textures() {
		ByteBuffer stringData = lump(LUMP_TEXDATA_STRING_DATA);
		ByteBuffer table = lump(LUMP_TEXDATA_STRING_TABLE);
		ByteBuffer texdata = lump(LUMP_TEXDATA);
		int texdataCount = texdata.limit() / 32;
		String[] names = new String[texdataCount];
		for (int i = 0; i < texdataCount; i++) {
			int nameId = texdata.getInt(i * 32 + 12);
			if (nameId < 0 || nameId * 4 >= table.limit()) {
				continue;
			}
			int start = table.getInt(nameId * 4);
			int end = start;
			while (end < stringData.limit() && stringData.get(end) != 0) {
				end++;
			}
			byte[] bytes = new byte[end - start];
			stringData.get(start, bytes);
			names[i] = new String(bytes, StandardCharsets.ISO_8859_1).replace('\\', '/').toLowerCase(Locale.ROOT);
		}
		ByteBuffer texinfo = lump(LUMP_TEXINFO);
		String[] out = new String[texinfo.limit() / 72];
		for (int i = 0; i < out.length; i++) {
			int texdataIndex = texinfo.getInt(i * 72 + 68);
			out[i] = texdataIndex >= 0 && texdataIndex < names.length ? names[texdataIndex] : null;
		}
		return out;
	}

	private void readBrushes(Plane[] planes, String[] textures) {
		ByteBuffer brushLump = lump(LUMP_BRUSHES);
		ByteBuffer sideLump = lump(LUMP_BRUSHSIDES);
		int count = brushLump.limit() / 12;
		for (int i = 0; i < count; i++) {
			int firstSide = brushLump.getInt(i * 12);
			int sideCount = brushLump.getInt(i * 12 + 4);
			int contents = brushLump.getInt(i * 12 + 8);
			Side[] sides = new Side[sideCount];
			for (int s = 0; s < sideCount; s++) {
				int o = (firstSide + s) * 8;
				int plane = Short.toUnsignedInt(sideLump.getShort(o));
				int texinfo = sideLump.getShort(o + 2);
				sides[s] = new Side(planes[plane], texinfo >= 0 && texinfo < textures.length ? textures[texinfo] : null);
			}
			brushes.add(new Brush(contents, sides));
		}
	}

	/** Marks which model each brush belongs to, by walking each model's tree down to its leaves. */
	private void readModels() {
		java.util.Arrays.fill(brushModel, -1);
		ByteBuffer models = lump(LUMP_MODELS);
		ByteBuffer nodes = lump(LUMP_NODES);
		ByteBuffer leafs = lump(LUMP_LEAFS);
		ByteBuffer leafBrushes = lump(LUMP_LEAFBRUSHES);
		int leafSize = version <= 19 && leafs.limit() % 56 == 0 ? 56 : 32;
		int modelCount = models.limit() / 48;
		for (int m = 0; m < modelCount; m++) {
			if (m == 0) {
				for (int k = 0; k < 6; k++) {
					worldBounds[k] = models.getFloat(k * 4);
				}
			}
			int head = models.getInt(m * 48 + 36);
			java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
			stack.push(head);
			while (!stack.isEmpty()) {
				int node = stack.pop();
				if (node >= 0) {
					if (node * 32 + 12 > nodes.limit()) {
						continue;
					}
					stack.push(nodes.getInt(node * 32 + 4));
					stack.push(nodes.getInt(node * 32 + 8));
				} else {
					int leaf = -1 - node;
					int o = leaf * leafSize;
					if (o + 28 > leafs.limit()) {
						continue;
					}
					int first = Short.toUnsignedInt(leafs.getShort(o + 24));
					int n = Short.toUnsignedInt(leafs.getShort(o + 26));
					for (int b = 0; b < n; b++) {
						int index = first + b;
						if (index * 2 + 2 > leafBrushes.limit()) {
							break;
						}
						int brush = Short.toUnsignedInt(leafBrushes.getShort(index * 2));
						if (brush < brushModel.length && brushModel[brush] < 0) {
							brushModel[brush] = m;
						}
					}
				}
			}
		}
	}

	private void readDisplacements(String[] textures) {
		ByteBuffer infos = lump(LUMP_DISPINFO);
		ByteBuffer verts = lump(LUMP_DISPVERTS);
		ByteBuffer faces = lump(LUMP_FACES);
		ByteBuffer vertexes = lump(LUMP_VERTEXES);
		ByteBuffer edges = lump(LUMP_EDGES);
		ByteBuffer surfEdges = lump(LUMP_SURFEDGES);
		int count = infos.limit() / 176;
		for (int i = 0; i < count; i++) {
			int o = i * 176;
			float sx = infos.getFloat(o);
			float sy = infos.getFloat(o + 4);
			float sz = infos.getFloat(o + 8);
			int vertStart = infos.getInt(o + 12);
			int power = infos.getInt(o + 20);
			int face = Short.toUnsignedInt(infos.getShort(o + 40));
			int f = face * 56;
			if (f + 56 > faces.limit() || power < 1 || power > 4) {
				continue;
			}
			int firstEdge = faces.getInt(f + 4);
			int edgeCount = faces.getShort(f + 8);
			int texinfo = faces.getShort(f + 10);
			if (edgeCount != 4) {
				continue;
			}
			float[][] corners = new float[4][];
			for (int e = 0; e < 4; e++) {
				int surfEdge = surfEdges.getInt((firstEdge + e) * 4);
				int edge = Math.abs(surfEdge);
				int vertex = Short.toUnsignedInt(edges.getShort(edge * 4 + (surfEdge >= 0 ? 0 : 2)));
				corners[e] = new float[] {vertexes.getFloat(vertex * 12), vertexes.getFloat(vertex * 12 + 4), vertexes.getFloat(vertex * 12 + 8)};
			}
			// The grid starts at the corner nearest the displacement's start position.
			int start = 0;
			float best = Float.MAX_VALUE;
			for (int c = 0; c < 4; c++) {
				float dx = corners[c][0] - sx;
				float dy = corners[c][1] - sy;
				float dz = corners[c][2] - sz;
				float d = dx * dx + dy * dy + dz * dz;
				if (d < best) {
					best = d;
					start = c;
				}
			}
			float[] p0 = corners[start];
			float[] p1 = corners[(start + 1) % 4];
			float[] p2 = corners[(start + 2) % 4];
			float[] p3 = corners[(start + 3) % 4];
			int size = (1 << power) + 1;
			float[] points = new float[size * size * 3];
			for (int y = 0; y < size; y++) {
				float ty = y / (float) (size - 1);
				for (int x = 0; x < size; x++) {
					float tx = x / (float) (size - 1);
					int v = (vertStart + y * size + x) * 20;
					if (v + 20 > verts.limit()) {
						continue;
					}
					float dist = verts.getFloat(v + 12);
					int p = (y * size + x) * 3;
					for (int k = 0; k < 3; k++) {
						float left = p0[k] + (p1[k] - p0[k]) * ty;
						float right = p3[k] + (p2[k] - p3[k]) * ty;
						points[p + k] = left + (right - left) * tx + verts.getFloat(v + k * 4) * dist;
					}
				}
			}
			String material = texinfo >= 0 && texinfo < textures.length ? textures[texinfo] : null;
			displacements.add(new Displacement(size, points, material));
		}
	}

	private void readEntities() {
		ByteBuffer lump = lump(LUMP_ENTITIES);
		byte[] bytes = new byte[lump.limit()];
		lump.get(0, bytes);
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		Map<String, String> current = null;
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '{') {
				current = new HashMap<>();
				i++;
			} else if (c == '}') {
				if (current != null) {
					entities.add(current);
				}
				current = null;
				i++;
			} else if (c == '"' && current != null) {
				int keyEnd = text.indexOf('"', i + 1);
				if (keyEnd < 0) {
					break;
				}
				String key = text.substring(i + 1, keyEnd);
				int valueStart = text.indexOf('"', keyEnd + 1);
				int valueEnd = valueStart < 0 ? -1 : text.indexOf('"', valueStart + 1);
				if (valueEnd < 0) {
					break;
				}
				current.put(key.toLowerCase(Locale.ROOT), text.substring(valueStart + 1, valueEnd));
				i = valueEnd + 1;
			} else {
				i++;
			}
		}
	}

	/** Parses a "x y z" entity value, or null. */
	public static float[] vector(String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.trim().split("\\s+");
		if (parts.length < 3) {
			return null;
		}
		try {
			return new float[] {Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
