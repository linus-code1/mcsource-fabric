package com.mcsource.world.hl2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mcsource.model.source.GameFiles;

/**
 * The solid of a Source map as the game itself has it: every brush, the
 * triangles of the bent terrain and the boxes of solid props, swept against
 * an entity's box as the game sweeps its player's box. Walls, slopes and
 * steps stop and carry you exactly where the map has them, not where the
 * blocks underneath are.
 *
 * <p>Positions here are in Source units. Written for this mod from the
 * published descriptions of the map format and of box tracing; no Valve
 * code is used.
 */
public final class MapCollision {
	/** How far a trace stops short of a surface, in units, so it never ends up inside one. */
	private static final float EPSILON = 0.03125F;
	/** How far from a surface a box still counts as touching it from outside, in units. */
	private static final float CONTACT = 0.25F;
	/** Size of a cell of the lookup grid, in units. */
	private static final float CELL = 256.0F;
	/** How thick bent terrain is under its surface, in units. */
	private static final float GROUND_DEPTH = 24.0F;
	/** Steepest ground that can be stood on, as the up part of its normal (as in Source). */
	public static final float WALKABLE = 0.7F;
	private static final int SOLID_CONTENTS = Bsp.CONTENTS_SOLID | Bsp.CONTENTS_WINDOW | Bsp.CONTENTS_GRATE | Bsp.CONTENTS_PLAYERCLIP;
	/** Brush entities that stay put and stop you; doors and the like are left open (as for the blocks). */
	private static final Set<String> KEPT_ENTITIES = Set.of("func_brush", "func_wall", "func_detail", "func_lod", "func_wall_toggle",
			"func_breakable", "func_breakable_surf", "func_monitor", "func_reflective_glass", "func_illusionary_solid",
			// Trains, lifts and platforms stand where they start, so you can stand on them.
			"func_tracktrain", "func_train", "func_plat", "func_platrot", "func_conveyor", "func_pushable", "func_physbox", "func_movelinear");

	/** Every solid's planes, four floats each (normal and distance), solid after solid. */
	private float[] planes = new float[4096];
	private int planeCount;
	/** Where each solid's planes start, with one more entry past the last. */
	private int[] firsts = new int[1024];
	/** Each solid's bounds: min x, y, z, max x, y, z. */
	private float[] bounds = new float[6 * 1024];
	private int solids;

	private final float gridX;
	private final float gridY;
	private final int cellsX;
	private final int cellsY;
	private int[][] cells;
	private int[] stamp;
	private int stampValue;

	/** Converts between this map's units and world blocks. */
	private final double offsetX;
	private final double offsetY;
	private final double offsetZ;

	/** The planes the last {@link #move} ran into, as normals in world axes, three doubles each. */
	private final ThreadLocal<double[]> lastPlanes = ThreadLocal.withInitial(() -> new double[0]);

	MapCollision(MapVoxels voxels) {
		Bsp map = voxels.map;
		offsetX = voxels.minX;
		offsetY = voxels.minY - MapVoxels.BASE_Y;
		offsetZ = voxels.minZ;
		brushes(map);
		for (Bsp.Displacement displacement : map.displacements) {
			displacement(displacement);
		}
		props(map, new BspFiles(map, voxels.files));
		firsts[solids] = planeCount;

		float[] w = map.worldBounds;
		gridX = w[0] - 64;
		gridY = w[1] - 64;
		cellsX = Math.max(1, (int) Math.ceil((w[3] - w[0] + 128) / CELL));
		cellsY = Math.max(1, (int) Math.ceil((w[4] - w[1] + 128) / CELL));
		List<List<Integer>> lists = new ArrayList<>(cellsX * cellsY);
		for (int i = 0; i < cellsX * cellsY; i++) {
			lists.add(null);
		}
		for (int s = 0; s < solids; s++) {
			int x0 = cellX(bounds[s * 6]);
			int x1 = cellX(bounds[s * 6 + 3]);
			int y0 = cellY(bounds[s * 6 + 1]);
			int y1 = cellY(bounds[s * 6 + 4]);
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					int c = y * cellsX + x;
					if (lists.get(c) == null) {
						lists.set(c, new ArrayList<>());
					}
					lists.get(c).add(s);
				}
			}
		}
		cells = new int[lists.size()][];
		for (int i = 0; i < cells.length; i++) {
			List<Integer> list = lists.get(i);
			if (list != null) {
				cells[i] = list.stream().mapToInt(Integer::intValue).toArray();
			}
		}
		stamp = new int[solids];
	}

	public int solids() {
		return solids;
	}

	private int cellX(float x) {
		return Math.max(0, Math.min(cellsX - 1, (int) Math.floor((x - gridX) / CELL)));
	}

	private int cellY(float y) {
		return Math.max(0, Math.min(cellsY - 1, (int) Math.floor((y - gridY) / CELL)));
	}

	// ---------------------------------------------------------------- building

	private void begin() {
		if (solids + 2 > firsts.length) {
			firsts = java.util.Arrays.copyOf(firsts, firsts.length * 2);
			bounds = java.util.Arrays.copyOf(bounds, bounds.length * 2);
		}
		firsts[solids] = planeCount;
	}

	private void plane(float nx, float ny, float nz, float d) {
		if ((planeCount + 1) * 4 > planes.length) {
			planes = java.util.Arrays.copyOf(planes, planes.length * 2);
		}
		int p = planeCount * 4;
		planes[p] = nx;
		planes[p + 1] = ny;
		planes[p + 2] = nz;
		planes[p + 3] = d;
		planeCount++;
	}

	/** Closes the solid begun last, with these bounds; dropped if it has no planes. */
	private void end(float[] b) {
		if (planeCount == firsts[solids]) {
			return;
		}
		System.arraycopy(b, 0, bounds, solids * 6, 6);
		solids++;
	}

	private void brushes(Bsp map) {
		Map<Integer, float[][]> placed = new HashMap<>();
		placed.put(0, null);
		for (Map<String, String> entity : map.entities) {
			String model = entity.get("model");
			if (model == null || !model.startsWith("*") || !KEPT_ENTITIES.contains(entity.getOrDefault("classname", ""))) {
				continue;
			}
			if ("1".equals(entity.get("startdisabled")) && "func_brush".equals(entity.get("classname"))) {
				continue;
			}
			try {
				float[] origin = Bsp.vector(entity.get("origin"));
				float[] angles = Bsp.vector(entity.get("angles"));
				boolean moved = (origin != null && (origin[0] != 0 || origin[1] != 0 || origin[2] != 0))
						|| (angles != null && (angles[0] != 0 || angles[1] != 0 || angles[2] != 0));
				placed.put(Integer.parseInt(model.substring(1)),
						moved ? new float[][] {MapScene.rotation(angles), origin == null ? new float[3] : origin} : null);
			} catch (NumberFormatException e) {
				// Not a brush model.
			}
		}
		float[] b = new float[6];
		for (int i = 0; i < map.brushes.size(); i++) {
			Bsp.Brush brush = map.brushes.get(i);
			int model = map.brushModel[i];
			if ((brush.contents() & SOLID_CONTENTS) == 0 || (model > 0 && !placed.containsKey(model))) {
				continue;
			}
			float[][] move = model > 0 ? placed.get(model) : null;
			begin();
			for (Bsp.Side side : brush.sides()) {
				Bsp.Plane p = side.plane();
				if (move == null) {
					plane(p.nx(), p.ny(), p.nz(), p.dist());
				} else {
					float[] m = move[0];
					float nx = m[0] * p.nx() + m[1] * p.ny() + m[2] * p.nz();
					float ny = m[3] * p.nx() + m[4] * p.ny() + m[5] * p.nz();
					float nz = m[6] * p.nx() + m[7] * p.ny() + m[8] * p.nz();
					float[] o = move[1];
					plane(nx, ny, nz, p.dist() + nx * o[0] + ny * o[1] + nz * o[2]);
				}
			}
			if (!boundsOfPlanes(firsts[solids], planeCount, b) || b[3] - b[0] > 40000 || b[4] - b[1] > 40000 || b[5] - b[2] > 40000) {
				planeCount = firsts[solids];
				continue;
			}
			if (move != null) {
				// Turned brushes lose their axial sides; put them back from the bounds.
				addBevels(b);
			}
			end(b);
		}
	}

	/** The box bounding a solid, from its axis-aligned planes; false if it lacks one of them. */
	private boolean boundsOfPlanes(int from, int to, float[] b) {
		b[0] = b[1] = b[2] = -Float.MAX_VALUE;
		b[3] = b[4] = b[5] = Float.MAX_VALUE;
		for (int i = from; i < to; i++) {
			int p = i * 4;
			for (int axis = 0; axis < 3; axis++) {
				if (planes[p + axis] > 0.999F) {
					b[3 + axis] = Math.min(b[3 + axis], planes[p + 3]);
				} else if (planes[p + axis] < -0.999F) {
					b[axis] = Math.max(b[axis], -planes[p + 3]);
				}
			}
		}
		if (b[0] == -Float.MAX_VALUE || b[1] == -Float.MAX_VALUE || b[2] == -Float.MAX_VALUE || b[3] == Float.MAX_VALUE
				|| b[4] == Float.MAX_VALUE || b[5] == Float.MAX_VALUE) {
			// A turned brush: bound it by its corners instead.
			return cornerBounds(from, to, b);
		}
		return b[0] <= b[3] && b[1] <= b[4] && b[2] <= b[5];
	}

	/** Bounds of a convex solid from the corners where three of its planes meet. */
	private boolean cornerBounds(int from, int to, float[] b) {
		b[0] = b[1] = b[2] = Float.MAX_VALUE;
		b[3] = b[4] = b[5] = -Float.MAX_VALUE;
		boolean any = false;
		for (int i = from; i < to; i++) {
			for (int j = i + 1; j < to; j++) {
				for (int k = j + 1; k < to; k++) {
					float[] c = meet(i, j, k);
					if (c == null || !inside(from, to, c[0], c[1], c[2], 0.01F)) {
						continue;
					}
					any = true;
					for (int a = 0; a < 3; a++) {
						b[a] = Math.min(b[a], c[a]);
						b[3 + a] = Math.max(b[3 + a], c[a]);
					}
				}
			}
		}
		return any;
	}

	private float[] meet(int i, int j, int k) {
		float[] a = {planes[i * 4], planes[i * 4 + 1], planes[i * 4 + 2]};
		float[] bb = {planes[j * 4], planes[j * 4 + 1], planes[j * 4 + 2]};
		float[] c = {planes[k * 4], planes[k * 4 + 1], planes[k * 4 + 2]};
		float[] bc = cross(bb, c);
		float det = a[0] * bc[0] + a[1] * bc[1] + a[2] * bc[2];
		if (Math.abs(det) < 1.0E-6F) {
			return null;
		}
		float[] ca = cross(c, a);
		float[] ab = cross(a, bb);
		float da = planes[i * 4 + 3];
		float db = planes[j * 4 + 3];
		float dc = planes[k * 4 + 3];
		return new float[] {(da * bc[0] + db * ca[0] + dc * ab[0]) / det, (da * bc[1] + db * ca[1] + dc * ab[1]) / det,
				(da * bc[2] + db * ca[2] + dc * ab[2]) / det};
	}

	private boolean inside(int from, int to, float x, float y, float z, float slack) {
		for (int i = from; i < to; i++) {
			int p = i * 4;
			if (planes[p] * x + planes[p + 1] * y + planes[p + 2] * z - planes[p + 3] > slack) {
				return false;
			}
		}
		return true;
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	/** The six axis-aligned planes of a box, so a box swept against a solid stops at its corners and edges in time. */
	private void addBevels(float[] b) {
		plane(1, 0, 0, b[3]);
		plane(-1, 0, 0, -b[0]);
		plane(0, 1, 0, b[4]);
		plane(0, -1, 0, -b[1]);
		plane(0, 0, 1, b[5]);
		plane(0, 0, -1, -b[2]);
	}

	private void displacement(Bsp.Displacement d) {
		int n = d.size();
		float[] p = d.points();
		for (int y = 0; y < n - 1; y++) {
			for (int x = 0; x < n - 1; x++) {
				int a = (y * n + x) * 3;
				int b = (y * n + x + 1) * 3;
				int c = ((y + 1) * n + x + 1) * 3;
				int e = ((y + 1) * n + x) * 3;
				triangle(p, a, b, c);
				triangle(p, a, c, e);
			}
		}
	}

	/** A triangle of bent terrain as a thin solid: its face, a back a little below, and its three edges. */
	private void triangle(float[] p, int a, int b, int c) {
		float[] pa = {p[a], p[a + 1], p[a + 2]};
		float[] pb = {p[b], p[b + 1], p[b + 2]};
		float[] pc = {p[c], p[c + 1], p[c + 2]};
		float[] n = cross(new float[] {pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]}, new float[] {pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2]});
		float length = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
		if (length < 1.0E-4F) {
			return;
		}
		n[0] /= length;
		n[1] /= length;
		n[2] /= length;
		if (n[2] < 0) {
			// Terrain faces up; a triangle wound the other way is turned over.
			n[0] = -n[0];
			n[1] = -n[1];
			n[2] = -n[2];
		}
		begin();
		float d = n[0] * pa[0] + n[1] * pa[1] + n[2] * pa[2];
		plane(n[0], n[1], n[2], d);
		plane(-n[0], -n[1], -n[2], -(d - GROUND_DEPTH));
		float[][] corners = {pa, pb, pc};
		for (int i = 0; i < 3; i++) {
			float[] from = corners[i];
			float[] to = corners[(i + 1) % 3];
			float[] other = corners[(i + 2) % 3];
			float[] edge = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
			float[] out = cross(edge, n);
			float l = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
			if (l < 1.0E-5F) {
				planeCount = firsts[solids];
				return;
			}
			out[0] /= l;
			out[1] /= l;
			out[2] /= l;
			float ed = out[0] * from[0] + out[1] * from[1] + out[2] * from[2];
			if (out[0] * other[0] + out[1] * other[1] + out[2] * other[2] > ed) {
				out[0] = -out[0];
				out[1] = -out[1];
				out[2] = -out[2];
				ed = -ed;
			}
			plane(out[0], out[1], out[2], ed);
		}
		float[] box = new float[6];
		box[0] = box[1] = box[2] = Float.MAX_VALUE;
		box[3] = box[4] = box[5] = -Float.MAX_VALUE;
		for (float[] corner : corners) {
			for (int k = 0; k < 3; k++) {
				box[k] = Math.min(box[k], Math.min(corner[k], corner[k] - n[k] * GROUND_DEPTH));
				box[3 + k] = Math.max(box[3 + k], Math.max(corner[k], corner[k] - n[k] * GROUND_DEPTH));
			}
		}
		addBevels(box);
		end(box);
	}

	// ---------------------------------------------------------------- props

	private void props(Bsp map, GameFiles files) {
		Map<String, float[]> hulls = new HashMap<>();
		ByteBuffer game = map.lump(35);
		if (game.limit() >= 4) {
			int lumpCount = game.getInt(0);
			for (int i = 0; i < lumpCount && 4 + i * 16 + 16 <= game.limit(); i++) {
				int at = 4 + i * 16;
				if (game.getInt(at) != 0x73707270) { // "sprp"
					continue;
				}
				try {
					staticProps(map.whole().slice(game.getInt(at + 8), game.getInt(at + 12)).order(ByteOrder.LITTLE_ENDIAN), files, hulls);
				} catch (RuntimeException e) {
					// Props left out: they are walked through.
				}
			}
		}
		for (Map<String, String> entity : map.entities) {
			String type = entity.getOrDefault("classname", "");
			String model = entity.get("model");
			if (model == null || !model.toLowerCase(Locale.ROOT).endsWith(".mdl")) {
				continue;
			}
			// Props that stay where they are; physics props would be knocked about, so they don't block.
			if (!type.equals("prop_static") && !type.equals("prop_dynamic") && !type.equals("prop_dynamic_override")) {
				continue;
			}
			if ("0".equals(entity.get("solid"))) {
				continue;
			}
			float[] origin = Bsp.vector(entity.get("origin"));
			if (origin != null) {
				prop(model, origin, Bsp.vector(entity.get("angles")), files, hulls);
			}
		}
	}

	private void staticProps(ByteBuffer lump, GameFiles files, Map<String, float[]> hulls) {
		int at = 0;
		int names = lump.getInt(at);
		at += 4;
		String[] dictionary = new String[names];
		for (int i = 0; i < names; i++) {
			int end = at;
			while (end < at + 128 && lump.get(end) != 0) {
				end++;
			}
			byte[] bytes = new byte[end - at];
			lump.get(at, bytes);
			dictionary[i] = new String(bytes, StandardCharsets.ISO_8859_1);
			at += 128;
		}
		int leaves = lump.getInt(at);
		at += 4 + leaves * 2;
		int count = lump.getInt(at);
		at += 4;
		if (count <= 0) {
			return;
		}
		int size = (lump.limit() - at) / count;
		if (size < 56) {
			return;
		}
		for (int i = 0; i < count; i++) {
			int p = at + i * size;
			int type = Short.toUnsignedInt(lump.getShort(p + 24));
			int solid = Byte.toUnsignedInt(lump.get(p + 30));
			// Solid 0: walked through in the game too.
			if (type >= dictionary.length || solid == 0) {
				continue;
			}
			float[] origin = {lump.getFloat(p), lump.getFloat(p + 4), lump.getFloat(p + 8)};
			float[] angles = {lump.getFloat(p + 12), lump.getFloat(p + 16), lump.getFloat(p + 20)};
			prop(dictionary[type], origin, angles, files, hulls);
		}
	}

	/** A prop as its model's collision box, turned as the prop is. */
	private void prop(String path, float[] origin, float[] angles, GameFiles files, Map<String, float[]> hulls) {
		String clean = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		float[] hull = hulls.computeIfAbsent(clean, key -> hull(files, key));
		if (hull.length == 0) {
			return;
		}
		float[] m = MapScene.rotation(angles);
		float[] centre = new float[3];
		float[] half = new float[3];
		for (int k = 0; k < 3; k++) {
			centre[k] = (hull[k] + hull[3 + k]) * 0.5F;
			half[k] = (hull[3 + k] - hull[k]) * 0.5F;
		}
		float[] c = {m[0] * centre[0] + m[1] * centre[1] + m[2] * centre[2] + origin[0],
				m[3] * centre[0] + m[4] * centre[1] + m[5] * centre[2] + origin[1],
				m[6] * centre[0] + m[7] * centre[1] + m[8] * centre[2] + origin[2]};
		begin();
		float[] box = new float[6];
		for (int axis = 0; axis < 3; axis++) {
			// The model's axis in the world: a column of the rotation.
			float ax = m[axis];
			float ay = m[3 + axis];
			float az = m[6 + axis];
			float d = ax * c[0] + ay * c[1] + az * c[2];
			plane(ax, ay, az, d + half[axis]);
			plane(-ax, -ay, -az, -(d - half[axis]));
		}
		for (int k = 0; k < 3; k++) {
			float reach = Math.abs(m[k * 3]) * half[0] + Math.abs(m[k * 3 + 1]) * half[1] + Math.abs(m[k * 3 + 2]) * half[2];
			box[k] = c[k] - reach;
			box[3 + k] = c[k] + reach;
		}
		addBevels(box);
		end(box);
	}

	/** A model's collision box from its header (min x, y, z, max x, y, z), or nothing if it can't be read or is tiny. */
	private static float[] hull(GameFiles files, String path) {
		if (files == null) {
			return new float[0];
		}
		try {
			byte[] bytes = files.read(path);
			if (bytes == null || bytes.length < 128) {
				return new float[0];
			}
			ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			// After id, version, checksum, the name, the length, the eye and the light origin.
			int at = 4 + 4 + 4 + 64 + 4 + 12 + 12;
			float[] hull = new float[6];
			for (int k = 0; k < 6; k++) {
				hull[k] = header.getFloat(at + k * 4);
			}
			// Use the box the model is drawn in when it is larger: the
			// collision box of many props is left at nothing.
			float[] view = new float[6];
			for (int k = 0; k < 6; k++) {
				view[k] = header.getFloat(at + 24 + k * 4);
			}
			if (volume(view) > volume(hull)) {
				hull = view;
			}
			// Small things (bottles, cans) don't stop you in the game either.
			if (hull[3] - hull[0] < 12 && hull[4] - hull[1] < 12 || hull[5] - hull[2] < 4) {
				return new float[0];
			}
			return hull;
		} catch (IOException | RuntimeException e) {
			return new float[0];
		}
	}

	private static float volume(float[] b) {
		return Math.max(0, b[3] - b[0]) * Math.max(0, b[4] - b[1]) * Math.max(0, b[5] - b[2]);
	}

	// ---------------------------------------------------------------- tracing

	/** The result of one sweep: how far along it got (0 to 1) and the plane it stopped at. */
	private static final class Hit {
		float fraction = 1.0F;
		float nx;
		float ny;
		float nz;
		boolean startSolid;
	}

	/** Sweeps a box with these half sizes from a centre to another, all in units. */
	private Hit trace(float sx, float sy, float sz, float ex, float ey, float ez, float hx, float hy, float hz) {
		Hit hit = new Hit();
		float minX = Math.min(sx, ex) - hx - 1;
		float maxX = Math.max(sx, ex) + hx + 1;
		float minY = Math.min(sy, ey) - hy - 1;
		float maxY = Math.max(sy, ey) + hy + 1;
		float minZ = Math.min(sz, ez) - hz - 1;
		float maxZ = Math.max(sz, ez) + hz + 1;
		synchronized (this) {
			stampValue++;
			if (stampValue == Integer.MAX_VALUE) {
				java.util.Arrays.fill(stamp, 0);
				stampValue = 1;
			}
			int x0 = cellX(minX);
			int x1 = cellX(maxX);
			int y0 = cellY(minY);
			int y1 = cellY(maxY);
			for (int cy = y0; cy <= y1; cy++) {
				for (int cx = x0; cx <= x1; cx++) {
					int[] list = cells[cy * cellsX + cx];
					if (list == null) {
						continue;
					}
					for (int s : list) {
						if (stamp[s] == stampValue) {
							continue;
						}
						stamp[s] = stampValue;
						int b = s * 6;
						if (bounds[b] > maxX || bounds[b + 3] < minX || bounds[b + 1] > maxY || bounds[b + 4] < minY || bounds[b + 2] > maxZ
								|| bounds[b + 5] < minZ) {
							continue;
						}
						clip(s, sx, sy, sz, ex, ey, ez, hx, hy, hz, hit);
					}
				}
			}
		}
		return hit;
	}

	/** Sweeps the box against one solid, keeping the nearer of this and the hit so far. */
	private void clip(int s, float sx, float sy, float sz, float ex, float ey, float ez, float hx, float hy, float hz, Hit hit) {
		float enter = -1.0F;
		float leave = 1.0F;
		float nx = 0;
		float ny = 0;
		float nz = 0;
		boolean startsOut = false;
		for (int i = firsts[s]; i < firsts[s + 1]; i++) {
			int p = i * 4;
			float px = planes[p];
			float py = planes[p + 1];
			float pz = planes[p + 2];
			// The plane pushed out by the box, so the box's centre can be traced
			// as a point, less a hair, so a box resting exactly on a surface (or
			// a hair into it) counts as outside it rather than stuck in it.
			float dist = planes[p + 3] + Math.abs(px) * hx + Math.abs(py) * hy + Math.abs(pz) * hz - CONTACT;
			float ds = px * sx + py * sy + pz * sz - dist;
			float de = px * ex + py * ey + pz * ez - dist;
			if (ds > 0) {
				startsOut = true;
			}
			if (ds > 0 && (de >= EPSILON || de >= ds)) {
				return;
			}
			if (ds <= 0 && de <= 0) {
				continue;
			}
			if (ds > de) {
				float f = (ds - EPSILON) / (ds - de);
				if (f > enter) {
					enter = f;
					nx = px;
					ny = py;
					nz = pz;
				}
			} else {
				float f = (ds + EPSILON) / (ds - de);
				if (f < leave) {
					leave = f;
				}
			}
		}
		if (!startsOut) {
			// Already inside: let it move out freely rather than stick fast.
			hit.startSolid = true;
			return;
		}
		if (enter < leave && enter > -1.0F && enter < hit.fraction) {
			hit.fraction = Math.max(0.0F, enter);
			hit.nx = nx;
			hit.ny = ny;
			hit.nz = nz;
		}
	}

	// ---------------------------------------------------------------- world blocks

	private float unitsX(double x) {
		return (float) ((x + offsetX) * MapVoxels.UNITS_PER_BLOCK);
	}

	private float unitsY(double z) {
		return (float) (-(z + offsetZ) * MapVoxels.UNITS_PER_BLOCK);
	}

	private float unitsZ(double y) {
		return (float) ((y + offsetY) * MapVoxels.UNITS_PER_BLOCK);
	}

	/** Whether a box in the world, in blocks, overlaps the map's solid. */
	public boolean blocked(AABB box) {
		float hx = (float) (box.getXsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		float hy = (float) (box.getZsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		float hz = (float) (box.getYsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		Vec3 c = box.getCenter();
		float x = unitsX(c.x);
		float y = unitsY(c.z);
		float z = unitsZ(c.y);
		// A tiny sweep: it is blocked if it can't make it, or starts inside.
		Hit hit = trace(x, y, z + 0.1F, x, y, z, hx, hy, hz);
		return hit.fraction < 1.0F || hit.startSolid;
	}

	/** The planes the last {@link #move} on this thread ran into (normals in world axes, x y z each). */
	public double[] lastPlanes() {
		return lastPlanes.get();
	}

	/**
	 * How far a box in the world (in blocks) gets of a wished movement, sliding
	 * along what it runs into and stepping up ledges no higher than
	 * {@code stepHeight} blocks when on the ground, as a Source player does.
	 */
	public Vec3 move(AABB box, Vec3 movement, double stepHeight, boolean onGround) {
		float hx = (float) (box.getXsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		float hy = (float) (box.getZsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		float hz = (float) (box.getYsize() * 0.5 * MapVoxels.UNITS_PER_BLOCK);
		Vec3 c = box.getCenter();
		float[] start = {unitsX(c.x), unitsY(c.z), unitsZ(c.y)};
		float[] wish = {(float) (movement.x * MapVoxels.UNITS_PER_BLOCK), (float) (-movement.z * MapVoxels.UNITS_PER_BLOCK),
				(float) (movement.y * MapVoxels.UNITS_PER_BLOCK)};
		List<float[]> touched = new ArrayList<>();
		float[] end = slide(start, wish, hx, hy, hz, touched);

		boolean horizontal = Math.abs(wish[0]) + Math.abs(wish[1]) > 1.0E-4F;
		if (stepHeight > 0 && onGround && horizontal && wish[2] <= 1.0E-3F) {
			float step = (float) (stepHeight * MapVoxels.UNITS_PER_BLOCK);
			// Up as far as the step allows, across, then back down onto the ground.
			Hit up = trace(start[0], start[1], start[2], start[0], start[1], start[2] + step, hx, hy, hz);
			float raised = step * up.fraction;
			float[] top = {start[0], start[1], start[2] + raised};
			List<float[]> stepTouched = new ArrayList<>();
			float[] across = slide(top, new float[] {wish[0], wish[1], 0}, hx, hy, hz, stepTouched);
			float drop = raised - Math.min(0, wish[2]) + 1.0F;
			Hit down = trace(across[0], across[1], across[2], across[0], across[1], across[2] - drop, hx, hy, hz);
			float[] landed = {across[0], across[1], across[2] - drop * down.fraction};
			boolean onFloor = down.fraction < 1.0F && down.nz >= WALKABLE;
			float plain = (end[0] - start[0]) * (end[0] - start[0]) + (end[1] - start[1]) * (end[1] - start[1]);
			float stepped = (landed[0] - start[0]) * (landed[0] - start[0]) + (landed[1] - start[1]) * (landed[1] - start[1]);
			if (onFloor && stepped > plain + 1.0E-3F) {
				end = landed;
				touched = stepTouched;
				touched.add(new float[] {down.nx, down.ny, down.nz});
			}
		}
		double[] normals = new double[touched.size() * 3];
		for (int i = 0; i < touched.size(); i++) {
			float[] n = touched.get(i);
			normals[i * 3] = n[0];
			normals[i * 3 + 1] = n[2];
			normals[i * 3 + 2] = -n[1];
		}
		lastPlanes.set(normals);
		return new Vec3((end[0] - start[0]) / MapVoxels.UNITS_PER_BLOCK, (end[2] - start[2]) / MapVoxels.UNITS_PER_BLOCK,
				-(end[1] - start[1]) / MapVoxels.UNITS_PER_BLOCK);
	}

	/** Moves as far as it can, sliding along up to four surfaces in turn; returns where it ends. */
	private float[] slide(float[] start, float[] wish, float hx, float hy, float hz, List<float[]> touched) {
		float[] at = start.clone();
		float[] left = wish.clone();
		List<float[]> hitPlanes = new ArrayList<>();
		for (int bump = 0; bump < 4; bump++) {
			if (Math.abs(left[0]) + Math.abs(left[1]) + Math.abs(left[2]) < 1.0E-4F) {
				break;
			}
			Hit hit = trace(at[0], at[1], at[2], at[0] + left[0], at[1] + left[1], at[2] + left[2], hx, hy, hz);
			at[0] += left[0] * hit.fraction;
			at[1] += left[1] * hit.fraction;
			at[2] += left[2] * hit.fraction;
			if (hit.fraction >= 1.0F) {
				break;
			}
			float[] n = {hit.nx, hit.ny, hit.nz};
			touched.add(n);
			hitPlanes.add(n);
			float rest = 1.0F - hit.fraction;
			left = new float[] {left[0] * rest, left[1] * rest, left[2] * rest};
			// Slide along every plane met so far; in a crease between two, along the crease.
			for (float[] plane : hitPlanes) {
				float into = left[0] * plane[0] + left[1] * plane[1] + left[2] * plane[2];
				if (into < 0) {
					left[0] -= plane[0] * into;
					left[1] -= plane[1] * into;
					left[2] -= plane[2] * into;
				}
			}
			if (hitPlanes.size() >= 2) {
				float[] a = hitPlanes.get(hitPlanes.size() - 2);
				float[] b = hitPlanes.get(hitPlanes.size() - 1);
				if (left[0] * a[0] + left[1] * a[1] + left[2] * a[2] < -1.0E-4F) {
					float[] crease = cross(a, b);
					float l = (float) Math.sqrt(crease[0] * crease[0] + crease[1] * crease[1] + crease[2] * crease[2]);
					if (l < 1.0E-4F) {
						break;
					}
					float along = (left[0] * crease[0] + left[1] * crease[1] + left[2] * crease[2]) / (l * l);
					left = new float[] {crease[0] * along, crease[1] * along, crease[2] * along};
				}
			}
			// Don't slide back the way it came.
			if (left[0] * wish[0] + left[1] * wish[1] + left[2] * wish[2] <= 0) {
				break;
			}
		}
		return at;
	}

	// ---------------------------------------------------------------- which world

	private static volatile MapCollision active;
	private static volatile Object activeOwner;
	/** Set by the client: whether a client level is a map world played on this computer. */
	public static volatile java.util.function.Predicate<net.minecraft.world.level.Level> clientCheck = level -> false;

	/** The map collision of a level, or null when it is not a map world. */
	public static MapCollision of(net.minecraft.world.level.Level level) {
		if (level == null || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return null;
		}
		if (level instanceof net.minecraft.server.level.ServerLevel server) {
			if (server.getChunkSource().getGenerator() instanceof Hl2MapGenerator generator) {
				return generator.collision();
			}
			return null;
		}
		return clientCheck.test(level) ? active : null;
	}

	static void setActive(MapCollision collision) {
		active = collision;
	}

	/** Builds a map's collision; logs and gives null on failure. */
	static MapCollision build(MapVoxels voxels) {
		try {
			long start = System.nanoTime();
			MapCollision collision = new MapCollision(voxels);
			org.slf4j.LoggerFactory.getLogger("mcsource").info("Map collision: {} solids, {} planes, in {} ms", collision.solids,
					collision.planeCount, (System.nanoTime() - start) / 1_000_000);
			return collision;
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("mcsource").warn("Could not build the map's collision", e);
			return null;
		}
	}

	static Set<String> keptEntities() {
		return new HashSet<>(KEPT_ENTITIES);
	}
}
