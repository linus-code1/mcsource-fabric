package com.mcsource.world.hl2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.GameFiles;
import com.mcsource.model.source.StudioModel;
import com.mcsource.model.source.Vtf;

/**
 * A Source map exactly as it is drawn in the game, ready to be drawn in
 * Minecraft: every visible face of the world and its brush entities with
 * its own material and its baked light (the map's lightmaps), the bent
 * terrain of its displacements with their blended second textures, and
 * every prop placed in the map (static props and prop entities) with its
 * own model, lit by the map's light samples. Nothing is swapped for a
 * Minecraft block; models and textures packed inside the map file are used
 * too.
 *
 * <p>Positions are in Minecraft blocks, laid out as {@link MapVoxels} lays
 * the map's collision blocks, so the two line up. Each vertex is
 * {@link #FLOATS} floats: x, y, z, texture u, v, lightmap x, y (in texels
 * of {@link #lightmap}) and a packed colour (light multiplier for props,
 * halved, and the displacement's blend to its second texture in alpha).
 *
 * <p>Written for this mod from public descriptions of the file formats; no
 * Valve code is used.
 */
public final class MapScene {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	public static final int FLOATS = 8;
	public static final int OPAQUE = 0;
	public static final int CUTOUT = 1;
	public static final int TRANSLUCENT = 2;
	private static final int MAX_TEXTURE = 512;

	private static final int LUMP_PLANES = 1;
	private static final int LUMP_TEXDATA = 2;
	private static final int LUMP_VERTEXES = 3;
	private static final int LUMP_NODES = 5;
	private static final int LUMP_TEXINFO = 6;
	private static final int LUMP_FACES = 7;
	private static final int LUMP_LIGHTING = 8;
	private static final int LUMP_LEAFS = 10;
	private static final int LUMP_EDGES = 12;
	private static final int LUMP_LEAFFACES = 16;
	private static final int LUMP_SURFEDGES = 13;
	private static final int LUMP_MODELS = 14;
	private static final int LUMP_DISPINFO = 26;
	private static final int LUMP_DISPVERTS = 33;
	private static final int LUMP_GAME = 35;
	private static final int LUMP_TEXDATA_STRING_DATA = 43;
	private static final int LUMP_TEXDATA_STRING_TABLE = 44;
	private static final int LUMP_LEAF_AMBIENT_INDEX_HDR = 51;
	private static final int LUMP_LEAF_AMBIENT_INDEX = 52;
	private static final int LUMP_LIGHTING_HDR = 53;
	private static final int LUMP_LEAF_AMBIENT_LIGHTING_HDR = 55;
	private static final int LUMP_LEAF_AMBIENT_LIGHTING = 56;
	private static final int LUMP_FACES_HDR = 58;

	private static final int SURF_SKY2D = 0x2;
	private static final int SURF_SKY = 0x4;
	private static final int SURF_TRIGGER = 0x40;
	private static final int SURF_NODRAW = 0x80;
	private static final int SURF_HINT = 0x100;
	private static final int SURF_SKIP = 0x200;
	private static final int SURF_NOLIGHT = 0x400;
	private static final int SKIP_FLAGS = SURF_SKY2D | SURF_SKY | SURF_TRIGGER | SURF_NODRAW | SURF_HINT | SURF_SKIP;

	/** Brush entities that are never seen. */
	private static final Set<String> HIDDEN_BRUSHES = Set.of("func_areaportal", "func_areaportalwindow", "func_occluder", "func_viscluster",
			"func_clip_vphysics", "func_precipitation", "func_dustmotes", "func_dustcloud", "func_smokevolume", "func_buyzone", "func_ladder",
			"func_vehicleclip", "func_nobuild", "func_respawnroomvisualizer");

	/** One material of the map (or of a prop) and the triangles drawn with it. */
	public static final class Material {
		public final String name;
		public final RiggedModel.Texture base;
		/** The second texture a displacement blends towards, or null. */
		public final RiggedModel.Texture base2;
		public final int mode;
		float[] data = new float[FLOATS * 96];
		int size;

		Material(String name, RiggedModel.Texture base, RiggedModel.Texture base2, int mode) {
			this.name = name;
			this.base = base;
			this.base2 = base2;
			this.mode = mode;
		}

		public float[] data() {
			return data;
		}

		public int vertices() {
			return size / FLOATS;
		}

		void vertex(float x, float y, float z, float u, float v, float lx, float ly, int colour) {
			if (size + FLOATS > data.length) {
				data = Arrays.copyOf(data, data.length * 2);
			}
			data[size++] = x;
			data[size++] = y;
			data[size++] = z;
			data[size++] = u;
			data[size++] = v;
			data[size++] = lx;
			data[size++] = ly;
			data[size++] = Float.intBitsToFloat(colour);
		}
	}

	public final List<Material> materials = new ArrayList<>();
	/** The map's baked light, packed into one image: ARGB, gamma encoded, half strength. */
	public int lightmapWidth = 2048;
	public int lightmapHeight;
	public int[] lightmap = new int[0];
	public int faces;
	public int props;

	private final Bsp map;
	private final GameFiles files;
	private final float originX;
	private final float originY;
	private final float originZ;
	private final ByteBuffer planes;
	private final ByteBuffer vertexes;
	private final ByteBuffer edges;
	private final ByteBuffer surfEdges;
	private final ByteBuffer texinfo;
	private final ByteBuffer texdata;
	private final ByteBuffer facesLump;
	private final ByteBuffer lighting;
	private final String[] texdataNames;
	private final Map<String, Material> byName = new HashMap<>();
	private final Map<String, RiggedModel.Texture> textures = new HashMap<>();
	private final Map<String, Material[]> propMaterials = new HashMap<>();
	private final Map<String, RiggedModel> models = new HashMap<>();
	private final Map<String, Map<String, String>> materialKeys = new HashMap<>();

	/** The area of the map holding its 3D sky (a small model of the far scenery, drawn scaled up in the game), or -1. */
	private int skyArea = -1;
	/** World faces inside the 3D sky, which are left out. */
	private boolean[] skyFaces = new boolean[0];

	// Lightmap packing: rows of rectangles, left to right.
	private int shelfX;
	private int shelfY;
	private int shelfHeight;
	private int whiteX;
	private int whiteY;

	/**
	 * Reads everything drawn of {@code map}. {@code originX}, {@code originY}
	 * and {@code originZ} are where the map's block grid starts, as
	 * {@link MapVoxels} has it.
	 */
	public MapScene(Bsp map, GameFiles game, float originX, float originY, float originZ) {
		this.map = map;
		this.files = new BspFiles(map, game);
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		planes = map.lump(LUMP_PLANES);
		vertexes = map.lump(LUMP_VERTEXES);
		edges = map.lump(LUMP_EDGES);
		surfEdges = map.lump(LUMP_SURFEDGES);
		texinfo = map.lump(LUMP_TEXINFO);
		texdata = map.lump(LUMP_TEXDATA);
		boolean ldr = map.lump(LUMP_LIGHTING).limit() > 0;
		lighting = ldr ? map.lump(LUMP_LIGHTING) : map.lump(LUMP_LIGHTING_HDR);
		ByteBuffer hdrFaces = map.lump(LUMP_FACES_HDR);
		facesLump = !ldr && hdrFaces.limit() > 0 ? hdrFaces : map.lump(LUMP_FACES);
		texdataNames = texdataNames(map);

		// A patch of plain light for what has no lightmap of its own.
		int[] white = allocate(4, 4);
		whiteX = white[0] + 2;
		whiteY = white[1] + 2;
		int full = encode(1.0F, 1.0F, 1.0F);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				lightmap[(white[1] + y) * lightmapWidth + white[0] + x] = full;
			}
		}

		findSkybox();
		brushModels();
		staticProps();
		propEntities();
		materials.removeIf(material -> material.size == 0);
		lightmapHeight = Math.max(1, shelfY + shelfHeight);
		lightmap = Arrays.copyOf(lightmap, lightmapWidth * lightmapHeight);
	}

	// ---------------------------------------------------------------- positions

	private float mcX(float sx) {
		return sx / MapVoxels.UNITS_PER_BLOCK - originX;
	}

	private float mcY(float sz) {
		return sz / MapVoxels.UNITS_PER_BLOCK - originY;
	}

	private float mcZ(float sy) {
		return -sy / MapVoxels.UNITS_PER_BLOCK - originZ;
	}

	/** A rotation from Source's pitch, yaw and roll (degrees), as a 3 by 3 matrix, row by row. */
	static float[] rotation(float[] angles) {
		if (angles == null) {
			return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
		}
		double pitch = Math.toRadians(angles[0]);
		double yaw = Math.toRadians(angles[1]);
		double roll = Math.toRadians(angles[2]);
		float sp = (float) Math.sin(pitch);
		float cp = (float) Math.cos(pitch);
		float sy = (float) Math.sin(yaw);
		float cy = (float) Math.cos(yaw);
		float sr = (float) Math.sin(roll);
		float cr = (float) Math.cos(roll);
		// Columns are the model's forward, left and up in the world.
		return new float[] {
				cp * cy, sr * sp * cy - cr * sy, cr * sp * cy + sr * sy,
				cp * sy, sr * sp * sy + cr * cy, cr * sp * sy - sr * cy,
				-sp, sr * cp, cr * cp};
	}

	private static void transform(float[] m, float[] origin, float x, float y, float z, float[] out) {
		out[0] = m[0] * x + m[1] * y + m[2] * z + origin[0];
		out[1] = m[3] * x + m[4] * y + m[5] * z + origin[1];
		out[2] = m[6] * x + m[7] * y + m[8] * z + origin[2];
	}

	// ---------------------------------------------------------------- materials

	private static String[] texdataNames(Bsp map) {
		ByteBuffer stringData = map.lump(LUMP_TEXDATA_STRING_DATA);
		ByteBuffer table = map.lump(LUMP_TEXDATA_STRING_TABLE);
		ByteBuffer texdata = map.lump(LUMP_TEXDATA);
		String[] names = new String[texdata.limit() / 32];
		for (int i = 0; i < names.length; i++) {
			int nameId = texdata.getInt(i * 32 + 12);
			if (nameId < 0 || nameId * 4 + 4 > table.limit()) {
				continue;
			}
			int start = table.getInt(nameId * 4);
			int end = start;
			while (end < stringData.limit() && stringData.get(end) != 0) {
				end++;
			}
			byte[] bytes = new byte[Math.max(0, end - start)];
			stringData.get(start, bytes);
			names[i] = new String(bytes, StandardCharsets.ISO_8859_1).replace('\\', '/').toLowerCase(Locale.ROOT);
		}
		return names;
	}

	/**
	 * A material's settings, following patch materials (such as the copies
	 * a map makes of its materials for its reflections) back to the
	 * material they change; the patch's own settings win.
	 */
	private Map<String, String> keys(String name) {
		Map<String, String> known = materialKeys.get(name);
		if (known != null) {
			return known;
		}
		Map<String, String> keys = new HashMap<>();
		String path = name;
		for (int depth = 0; depth < 5 && path != null; depth++) {
			byte[] bytes = null;
			try {
				bytes = files.read("materials/" + path + ".vmt");
			} catch (IOException e) {
				// Treated as missing.
			}
			if (bytes == null) {
				break;
			}
			Map<String, String> these = Vmt.parse(new String(bytes, StandardCharsets.ISO_8859_1));
			String shader = these.get("");
			for (Map.Entry<String, String> entry : these.entrySet()) {
				if (entry.getKey().isEmpty()) {
					continue;
				}
				keys.putIfAbsent(entry.getKey(), entry.getValue());
			}
			if (shader != null && shader.equals("patch")) {
				String include = these.get("include");
				path = include == null ? null : clean(include, ".vmt");
			} else {
				keys.put("", shader == null ? "" : shader);
				path = null;
			}
		}
		materialKeys.put(name, keys);
		return keys;
	}

	private static String clean(String path, String extension) {
		String p = path.replace('\\', '/').toLowerCase(Locale.ROOT).trim();
		if (p.startsWith("materials/")) {
			p = p.substring("materials/".length());
		}
		if (p.endsWith(extension)) {
			p = p.substring(0, p.length() - extension.length());
		}
		return p;
	}

	private RiggedModel.Texture texture(String path, boolean keepAlpha) {
		if (path == null) {
			return null;
		}
		String clean = clean(path, ".vtf");
		String key = clean + (keepAlpha ? "#a" : "");
		if (textures.containsKey(key)) {
			return textures.get(key);
		}
		RiggedModel.Texture texture = null;
		try {
			byte[] bytes = files.read("materials/" + clean + ".vtf");
			if (bytes != null) {
				Vtf image = Vtf.decode(bytes, MAX_TEXTURE);
				int[] pixels = image.pixels;
				if (!keepAlpha) {
					for (int i = 0; i < pixels.length; i++) {
						pixels[i] |= 0xFF000000;
					}
				}
				texture = new RiggedModel.Texture(clean, image.width, image.height, pixels, keepAlpha);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Could not read texture {}", clean, e);
		}
		textures.put(key, texture);
		return texture;
	}

	private static boolean on(Map<String, String> keys, String key) {
		String value = keys.get(key);
		if (value == null) {
			return false;
		}
		value = value.trim();
		return !value.isEmpty() && !value.equals("0") && !value.startsWith("0.");
	}

	/** The drawn material for a texdata name, or null if it is never drawn (tools, water, sky). */
	private Material material(String name) {
		if (name == null) {
			return null;
		}
		if (byName.containsKey(name)) {
			return byName.get(name);
		}
		Material material = null;
		String plain = MaterialBlocks.plain(name);
		if (!plain.startsWith("tools/")) {
			Map<String, String> keys = keys(name);
			String shader = keys.getOrDefault("", "");
			boolean water = shader.contains("water") || on(keys, "%compilewater");
			boolean sky = shader.contains("sky") || on(keys, "%compilesky") || on(keys, "%compile2dsky") || on(keys, "%compilenodraw");
			if (!water && !sky) {
				boolean translucent = on(keys, "$translucent") || on(keys, "$additive");
				boolean cutout = on(keys, "$alphatest");
				int mode = translucent ? TRANSLUCENT : cutout ? CUTOUT : OPAQUE;
				RiggedModel.Texture base = texture(keys.get("$basetexture"), mode != OPAQUE);
				if (base == null) {
					base = missing(name);
				}
				RiggedModel.Texture base2 = texture(keys.get("$basetexture2"), false);
				material = new Material(name, base, base2, mode);
				materials.add(material);
			}
		}
		byName.put(name, material);
		return material;
	}

	private static RiggedModel.Texture missing(String name) {
		int[] pixels = new int[16];
		Arrays.fill(pixels, 0xFF808080);
		return new RiggedModel.Texture(name, 4, 4, pixels, false);
	}

	// ---------------------------------------------------------------- lightmaps

	/** Room in the lightmap image for a {@code w} by {@code h} block; its corner. */
	private int[] allocate(int w, int h) {
		if (shelfX + w > lightmapWidth) {
			shelfX = 0;
			shelfY += shelfHeight;
			shelfHeight = 0;
		}
		int[] at = {shelfX, shelfY};
		shelfX += w;
		shelfHeight = Math.max(shelfHeight, h);
		int needed = (shelfY + shelfHeight) * lightmapWidth;
		if (needed > lightmap.length) {
			lightmap = Arrays.copyOf(lightmap, Math.max(needed, lightmap.length * 2));
		}
		return at;
	}

	/**
	 * Light, from linear values where 1 shows a texture as it is, stored
	 * gamma encoded at half strength so brighter light still fits.
	 */
	static int encode(float r, float g, float b) {
		return 0xFF000000 | channel(r) << 16 | channel(g) << 8 | channel(b);
	}

	private static int channel(float linear) {
		float v = (float) Math.pow(Math.max(0.0F, Math.min(1.0F, linear * 0.5F)), 1.0 / 2.2);
		return Math.round(v * 255.0F);
	}

	/** One light sample: red, green, blue and a shared power of two. */
	private static float sample(ByteBuffer data, int at, int channel) {
		int exponent = data.get(at + 3);
		return Byte.toUnsignedInt(data.get(at + channel)) / 255.0F * (float) Math.pow(2.0, exponent);
	}

	/**
	 * Copies a face's lightmap into the image, with a border of its edge
	 * samples so smooth filtering doesn't pick up its neighbours. Returns
	 * where its first sample landed, or null if it has none.
	 */
	private int[] lightmapOf(int face) {
		int f = face * 56;
		int styles0 = Byte.toUnsignedInt(facesLump.get(f + 16));
		int offset = facesLump.getInt(f + 20);
		int w = facesLump.getInt(f + 36) + 1;
		int h = facesLump.getInt(f + 40) + 1;
		if (styles0 == 255 || offset < 0 || w <= 0 || h <= 0 || w > 1024 || h > 1024 || offset + w * h * 4 > lighting.limit()) {
			return null;
		}
		int[] at = allocate(w + 2, h + 2);
		for (int y = -1; y <= h; y++) {
			for (int x = -1; x <= w; x++) {
				int sx = Math.max(0, Math.min(w - 1, x));
				int sy = Math.max(0, Math.min(h - 1, y));
				int s = offset + (sy * w + sx) * 4;
				lightmap[(at[1] + 1 + y) * lightmapWidth + at[0] + 1 + x] = encode(sample(lighting, s, 0), sample(lighting, s, 1),
						sample(lighting, s, 2));
			}
		}
		return new int[] {at[0] + 1, at[1] + 1};
	}

	// ---------------------------------------------------------------- 3D sky

	private int leafSize() {
		ByteBuffer leafs = map.lump(LUMP_LEAFS);
		return map.version <= 19 && leafs.limit() % 56 == 0 ? 56 : 32;
	}

	private int areaOf(int leaf) {
		ByteBuffer leafs = map.lump(LUMP_LEAFS);
		int at = leaf * leafSize() + 6;
		return leaf < 0 || at + 2 > leafs.limit() ? -1 : leafs.getShort(at) & 0x1FF;
	}

	/**
	 * Finds the map's 3D sky, a miniature of the far scenery the game draws
	 * scaled up behind everything. Drawn as it is, it would float in the
	 * air beside the map, so its faces and props are left out.
	 */
	private void findSkybox() {
		for (Map<String, String> entity : map.entities) {
			if (entity.getOrDefault("classname", "").equals("sky_camera")) {
				float[] origin = Bsp.vector(entity.get("origin"));
				if (origin != null) {
					skyArea = areaOf(leafAt(origin));
				}
			}
		}
		if (skyArea <= 0) {
			skyArea = -1;
			return;
		}
		ByteBuffer leafs = map.lump(LUMP_LEAFS);
		ByteBuffer leafFaces = map.lump(LUMP_LEAFFACES);
		skyFaces = new boolean[facesLump.limit() / 56];
		int size = leafSize();
		for (int leaf = 0; leaf * size + size <= leafs.limit(); leaf++) {
			if (areaOf(leaf) != skyArea) {
				continue;
			}
			int first = Short.toUnsignedInt(leafs.getShort(leaf * size + 20));
			int count = Short.toUnsignedInt(leafs.getShort(leaf * size + 22));
			for (int i = first; i < first + count && i * 2 + 2 <= leafFaces.limit(); i++) {
				int face = Short.toUnsignedInt(leafFaces.getShort(i * 2));
				if (face < skyFaces.length) {
					skyFaces[face] = true;
				}
			}
		}
	}

	private boolean inSkybox(float[] point) {
		return skyArea >= 0 && areaOf(leafAt(point)) == skyArea;
	}

	// ---------------------------------------------------------------- brushes

	private void brushModels() {
		ByteBuffer models = map.lump(LUMP_MODELS);
		int modelCount = models.limit() / 48;
		float[] none = {0, 0, 0};
		drawModel(models, 0, rotation(null), none);
		for (Map<String, String> entity : map.entities) {
			String model = entity.get("model");
			String type = entity.getOrDefault("classname", "");
			if (model == null || !model.startsWith("*") || type.startsWith("trigger_") || HIDDEN_BRUSHES.contains(type)) {
				continue;
			}
			if ("1".equals(entity.get("startdisabled")) && type.equals("func_brush")) {
				continue;
			}
			int index;
			try {
				index = Integer.parseInt(model.substring(1));
			} catch (NumberFormatException e) {
				continue;
			}
			if (index <= 0 || index >= modelCount) {
				continue;
			}
			float[] origin = Bsp.vector(entity.get("origin"));
			if (origin == null) {
				origin = none;
			}
			float[] centre = new float[3];
			for (int k = 0; k < 3; k++) {
				centre[k] = origin[k] + (models.getFloat(index * 48 + k * 4) + models.getFloat(index * 48 + 12 + k * 4)) * 0.5F;
			}
			if (inSkybox(centre)) {
				continue;
			}
			drawModel(models, index, rotation(Bsp.vector(entity.get("angles"))), origin);
		}
	}

	private void drawModel(ByteBuffer models, int model, float[] rotation, float[] origin) {
		int first = models.getInt(model * 48 + 40);
		int count = models.getInt(model * 48 + 44);
		int faceCount = facesLump.limit() / 56;
		for (int i = first; i < first + count && i < faceCount; i++) {
			face(i, rotation, origin);
		}
	}

	private void face(int face, float[] rotation, float[] origin) {
		if (face < skyFaces.length && skyFaces[face]) {
			return;
		}
		int f = face * 56;
		int firstEdge = facesLump.getInt(f + 4);
		int edgeCount = facesLump.getShort(f + 8);
		int info = facesLump.getShort(f + 10);
		int disp = facesLump.getShort(f + 12);
		if (info < 0 || info * 72 + 72 > texinfo.limit() || edgeCount < 3) {
			return;
		}
		int t = info * 72;
		int flags = texinfo.getInt(t + 64);
		int data = texinfo.getInt(t + 68);
		if ((flags & SKIP_FLAGS) != 0 || data < 0 || data >= texdataNames.length) {
			return;
		}
		Material material = material(texdataNames[data]);
		if (material == null) {
			return;
		}
		float[] s = new float[4];
		float[] tv = new float[4];
		float[] ls = new float[4];
		float[] lt = new float[4];
		for (int k = 0; k < 4; k++) {
			s[k] = texinfo.getFloat(t + k * 4);
			tv[k] = texinfo.getFloat(t + 16 + k * 4);
			ls[k] = texinfo.getFloat(t + 32 + k * 4);
			lt[k] = texinfo.getFloat(t + 48 + k * 4);
		}
		// Texture coordinates are in texels of the material's own size.
		float width = Math.max(1, texdata.getInt(data * 32 + 16));
		float height = Math.max(1, texdata.getInt(data * 32 + 20));
		int minS = facesLump.getInt(f + 28);
		int minT = facesLump.getInt(f + 32);
		float[][] corners = new float[edgeCount][];
		float[] centre = new float[3];
		for (int e = 0; e < edgeCount; e++) {
			int surfEdge = surfEdges.getInt((firstEdge + e) * 4);
			int edge = Math.abs(surfEdge);
			int vertex = Short.toUnsignedInt(edges.getShort(edge * 4 + (surfEdge >= 0 ? 0 : 2)));
			corners[e] = new float[] {vertexes.getFloat(vertex * 12), vertexes.getFloat(vertex * 12 + 4), vertexes.getFloat(vertex * 12 + 8)};
			for (int k = 0; k < 3; k++) {
				centre[k] += corners[e][k] / edgeCount;
			}
		}
		if (disp >= 0 && skyArea >= 0) {
			// Displacements aren't listed in the leaves; test a point just in front of the face.
			int plane = Short.toUnsignedInt(facesLump.getShort(f));
			float out = facesLump.get(f + 2) != 0 ? -8.0F : 8.0F;
			if (plane * 20 + 12 <= planes.limit()) {
				for (int k = 0; k < 3; k++) {
					centre[k] += planes.getFloat(plane * 20 + k * 4) * out;
				}
			}
			if (inSkybox(centre)) {
				return;
			}
		}
		int[] light = (flags & SURF_NOLIGHT) != 0 ? null : lightmapOf(face);
		faces++;
		Corner corner = new Corner(material, s, tv, ls, lt, width, height, minS, minT, light, rotation, origin);
		if (disp >= 0) {
			displacement(disp, corners, corner);
			return;
		}
		for (int i = 1; i + 1 < edgeCount; i++) {
			corner.emit(corners[0], corners[0], 0);
			corner.emit(corners[i], corners[i], 0);
			corner.emit(corners[i + 1], corners[i + 1], 0);
		}
	}

	/** What is needed to lay one vertex of a face. */
	private final class Corner {
		final Material material;
		final float[] s;
		final float[] t;
		final float[] ls;
		final float[] lt;
		final float width;
		final float height;
		final int minS;
		final int minT;
		final int[] light;
		final float[] rotation;
		final float[] origin;
		final float[] world = new float[3];

		Corner(Material material, float[] s, float[] t, float[] ls, float[] lt, float width, float height, int minS, int minT, int[] light,
				float[] rotation, float[] origin) {
			this.material = material;
			this.s = s;
			this.t = t;
			this.ls = ls;
			this.lt = lt;
			this.width = width;
			this.height = height;
			this.minS = minS;
			this.minT = minT;
			this.light = light;
			this.rotation = rotation;
			this.origin = origin;
		}

		/**
		 * One vertex at {@code p}; texture and lightmap coordinates come from
		 * {@code flat}, the point before a displacement moved it.
		 */
		void emit(float[] p, float[] flat, float blend) {
			float u = (flat[0] * s[0] + flat[1] * s[1] + flat[2] * s[2] + s[3]) / width;
			float v = (flat[0] * t[0] + flat[1] * t[1] + flat[2] * t[2] + t[3]) / height;
			float lx;
			float ly;
			if (light == null) {
				lx = whiteX;
				ly = whiteY;
			} else {
				lx = light[0] + (flat[0] * ls[0] + flat[1] * ls[1] + flat[2] * ls[2] + ls[3] - minS) + 0.5F;
				ly = light[1] + (flat[0] * lt[0] + flat[1] * lt[1] + flat[2] * lt[2] + lt[3] - minT) + 0.5F;
			}
			emitAt(p, u, v, lx, ly, blend);
		}

		void emitAt(float[] p, float u, float v, float lx, float ly, float blend) {
			transform(rotation, origin, p[0], p[1], p[2], world);
			int alpha = Math.round(Math.max(0.0F, Math.min(1.0F, blend)) * 255.0F);
			material.vertex(mcX(world[0]), mcY(world[2]), mcZ(world[1]), u, v, lx, ly, alpha << 24 | 0x808080);
		}
	}

	/**
	 * A displacement: its grid of points laid over its base face, each moved
	 * along its own direction. Texture and light are laid on the flat face
	 * underneath and carried up with the points, as the game does.
	 */
	private void displacement(int index, float[][] base, Corner corner) {
		ByteBuffer infos = map.lump(LUMP_DISPINFO);
		ByteBuffer verts = map.lump(LUMP_DISPVERTS);
		int o = index * 176;
		if (o + 176 > infos.limit() || base.length != 4) {
			return;
		}
		float[] start = {infos.getFloat(o), infos.getFloat(o + 4), infos.getFloat(o + 8)};
		int vertStart = infos.getInt(o + 12);
		int power = infos.getInt(o + 20);
		if (power < 1 || power > 4) {
			return;
		}
		int first = 0;
		float best = Float.MAX_VALUE;
		for (int c = 0; c < 4; c++) {
			float dx = base[c][0] - start[0];
			float dy = base[c][1] - start[1];
			float dz = base[c][2] - start[2];
			float d = dx * dx + dy * dy + dz * dz;
			if (d < best) {
				best = d;
				first = c;
			}
		}
		float[] p0 = base[first];
		float[] p1 = base[(first + 1) % 4];
		float[] p2 = base[(first + 2) % 4];
		float[] p3 = base[(first + 3) % 4];
		int size = (1 << power) + 1;
		float[][] points = new float[size * size][];
		float[][] flats = new float[size * size][];
		float[] blends = new float[size * size];
		for (int y = 0; y < size; y++) {
			float ty = y / (float) (size - 1);
			for (int x = 0; x < size; x++) {
				float tx = x / (float) (size - 1);
				int v = (vertStart + y * size + x) * 20;
				float[] flat = new float[3];
				for (int k = 0; k < 3; k++) {
					float left = p0[k] + (p1[k] - p0[k]) * ty;
					float right = p3[k] + (p2[k] - p3[k]) * ty;
					flat[k] = left + (right - left) * tx;
				}
				float[] point = flat.clone();
				if (v + 20 <= verts.limit()) {
					float dist = verts.getFloat(v + 12);
					for (int k = 0; k < 3; k++) {
						point[k] += verts.getFloat(v + k * 4) * dist;
					}
					blends[y * size + x] = verts.getFloat(v + 16) / 255.0F;
				}
				points[y * size + x] = point;
				flats[y * size + x] = flat;
			}
		}
		for (int y = 0; y < size - 1; y++) {
			for (int x = 0; x < size - 1; x++) {
				int a = y * size + x;
				int b = y * size + x + 1;
				int c = (y + 1) * size + x + 1;
				int d = (y + 1) * size + x;
				// Alternate the diagonal, as the game does, so slopes come out the same.
				if ((a & 1) == 0) {
					emitTriangle(corner, points, flats, blends, a, b, c);
					emitTriangle(corner, points, flats, blends, a, c, d);
				} else {
					emitTriangle(corner, points, flats, blends, a, b, d);
					emitTriangle(corner, points, flats, blends, b, c, d);
				}
			}
		}
	}

	private static void emitTriangle(Corner corner, float[][] points, float[][] flats, float[] blends, int a, int b, int c) {
		corner.emit(points[a], flats[a], blends[a]);
		corner.emit(points[b], flats[b], blends[b]);
		corner.emit(points[c], flats[c], blends[c]);
	}

	// ---------------------------------------------------------------- props

	/** Props compiled into the map: every one its model, place, turn and light. */
	private void staticProps() {
		ByteBuffer game = map.lump(LUMP_GAME);
		if (game.limit() < 4) {
			return;
		}
		int lumpCount = game.getInt(0);
		for (int i = 0; i < lumpCount && 4 + i * 16 + 16 <= game.limit(); i++) {
			int at = 4 + i * 16;
			int id = game.getInt(at);
			int version = Short.toUnsignedInt(game.getShort(at + 6));
			int offset = game.getInt(at + 8);
			int length = game.getInt(at + 12);
			if (id != 0x73707270) { // "sprp"
				continue;
			}
			try {
				staticProps(map.whole().slice(offset, length).order(java.nio.ByteOrder.LITTLE_ENDIAN), version);
			} catch (RuntimeException e) {
				LOGGER.warn("Could not read the map's props", e);
			}
		}
	}

	private void staticProps(ByteBuffer lump, int version) {
		int at = 0;
		int names = lump.getInt(at);
		at += 4;
		String[] dictionary = new String[names];
		for (int i = 0; i < names; i++) {
			dictionary[i] = string(lump, at, 128);
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
			float[] origin = {lump.getFloat(p), lump.getFloat(p + 4), lump.getFloat(p + 8)};
			float[] angles = {lump.getFloat(p + 12), lump.getFloat(p + 16), lump.getFloat(p + 20)};
			int type = Short.toUnsignedInt(lump.getShort(p + 24));
			int flags = Byte.toUnsignedInt(lump.get(p + 31));
			float[] lightOrigin = {lump.getFloat(p + 44), lump.getFloat(p + 48), lump.getFloat(p + 52)};
			if (type >= dictionary.length) {
				continue;
			}
			// Flag 2: the prop is lit from a chosen point instead of its origin.
			prop(dictionary[type], origin, angles, (flags & 2) != 0 ? lightOrigin : origin);
		}
	}

	/** Props placed as entities: physics props, dynamic props and the like, drawn where they start. */
	private void propEntities() {
		for (Map<String, String> entity : map.entities) {
			String type = entity.getOrDefault("classname", "");
			String model = entity.get("model");
			if (model == null || !model.toLowerCase(Locale.ROOT).endsWith(".mdl") || type.equals("prop_ragdoll")) {
				continue;
			}
			if (!type.startsWith("prop_") && !type.equals("physics_prop") && !type.startsWith("func_physbox")) {
				continue;
			}
			float[] origin = Bsp.vector(entity.get("origin"));
			if (origin == null) {
				continue;
			}
			prop(model, origin, Bsp.vector(entity.get("angles")), origin);
		}
	}

	private static String string(ByteBuffer data, int at, int max) {
		int end = at;
		while (end < at + max && end < data.limit() && data.get(end) != 0) {
			end++;
		}
		byte[] bytes = new byte[end - at];
		data.get(at, bytes);
		return new String(bytes, StandardCharsets.ISO_8859_1).replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	private RiggedModel model(String path) {
		if (models.containsKey(path)) {
			return models.get(path);
		}
		RiggedModel model = null;
		try {
			model = StudioModel.load(files, path, null);
		} catch (IOException | RuntimeException e) {
			LOGGER.info("Map prop {} could not be read: {}", path, e.getMessage());
		}
		models.put(path, model);
		return model;
	}

	private void prop(String path, float[] origin, float[] angles, float[] lightOrigin) {
		if (inSkybox(origin)) {
			return;
		}
		String clean = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		RiggedModel model = model(clean);
		if (model == null) {
			return;
		}
		Material[] own = propMaterials.computeIfAbsent(clean, key -> {
			Material[] list = new Material[model.textures.length];
			for (int i = 0; i < list.length; i++) {
				RiggedModel.Texture texture = model.textures[i];
				list[i] = new Material(key + "#" + i, texture, null, texture.masked() ? CUTOUT : OPAQUE);
				materials.add(list[i]);
			}
			return list;
		});
		float[] m = rotation(angles);
		float[][] cube = ambientCube(lightOrigin);
		float[] world = new float[3];
		float[] normal = new float[3];
		float[] none = {0, 0, 0};
		for (int tri = 0; tri < model.triangleCount(); tri++) {
			int texture = model.triangleTexture[tri];
			if (texture < 0 || texture >= own.length) {
				continue;
			}
			Material material = own[texture];
			for (int k = 0; k < 3; k++) {
				int corner = tri * 3 + k;
				int v = model.cornerVertex[corner] * 3;
				int n = model.cornerNormal[corner] * 3;
				transform(m, origin, model.positions[v], model.positions[v + 1], model.positions[v + 2], world);
				transform(m, none, model.normals[n], model.normals[n + 1], model.normals[n + 2], normal);
				int colour = 0xFF000000 | light(cube, normal);
				material.vertex(mcX(world[0]), mcY(world[2]), mcZ(world[1]), model.cornerUv[corner * 2], model.cornerUv[corner * 2 + 1], whiteX,
						whiteY, colour & 0x00FFFFFF);
			}
		}
		props++;
	}

	/** Light from the six sides of an ambient cube, for a normal; packed RGB at half strength, gamma encoded. */
	private static int light(float[][] cube, float[] n) {
		float x2 = n[0] * n[0];
		float y2 = n[1] * n[1];
		float z2 = n[2] * n[2];
		float[] px = cube[n[0] >= 0 ? 0 : 1];
		float[] py = cube[n[1] >= 0 ? 2 : 3];
		float[] pz = cube[n[2] >= 0 ? 4 : 5];
		int out = 0;
		for (int c = 0; c < 3; c++) {
			float linear = px[c] * x2 + py[c] * y2 + pz[c] * z2;
			float v = (float) Math.pow(Math.max(0.0F, linear), 1.0 / 2.2) * 0.5F;
			out = out << 8 | Math.round(Math.min(1.0F, v) * 255.0F);
		}
		return out;
	}

	/** The map's light samples at a point: six colours, for light from +x, -x, +y, -y, +z and -z. */
	private float[][] ambientCube(float[] point) {
		float[][] cube = new float[6][];
		for (int i = 0; i < 6; i++) {
			cube[i] = new float[] {0.5F, 0.5F, 0.5F};
		}
		int leaf = leafAt(point);
		if (leaf < 0) {
			return cube;
		}
		ByteBuffer leafs = map.lump(LUMP_LEAFS);
		ByteBuffer data;
		int at;
		if (map.version <= 19 && leafs.limit() % 56 == 0) {
			data = leafs;
			at = leaf * 56 + 30;
			if (at + 24 > leafs.limit()) {
				return cube;
			}
		} else {
			ByteBuffer index = map.lump(LUMP_LEAF_AMBIENT_INDEX);
			ByteBuffer samples = map.lump(LUMP_LEAF_AMBIENT_LIGHTING);
			if (index.limit() == 0 || samples.limit() == 0) {
				index = map.lump(LUMP_LEAF_AMBIENT_INDEX_HDR);
				samples = map.lump(LUMP_LEAF_AMBIENT_LIGHTING_HDR);
			}
			if (leaf * 4 + 4 > index.limit()) {
				return cube;
			}
			int count = Short.toUnsignedInt(index.getShort(leaf * 4));
			int first = Short.toUnsignedInt(index.getShort(leaf * 4 + 2));
			if (count == 0 || first * 28 + 28 > samples.limit()) {
				return cube;
			}
			data = samples;
			at = first * 28;
			// A leaf holds several samples, each placed by fractions of the
			// leaf's box; the nearest one to the point is used.
			if (count > 1 && leaf * 32 + 20 <= leafs.limit() && first * 28 + count * 28 <= samples.limit()) {
				float best = Float.MAX_VALUE;
				for (int i = 0; i < count; i++) {
					int s = (first + i) * 28 + 24;
					float d = 0;
					for (int k = 0; k < 3; k++) {
						float lo = leafs.getShort(leaf * 32 + 8 + k * 2);
						float hi = leafs.getShort(leaf * 32 + 14 + k * 2);
						float c = lo + (hi - lo) * Byte.toUnsignedInt(samples.get(s + k)) / 255.0F;
						d += (c - point[k]) * (c - point[k]);
					}
					if (d < best) {
						best = d;
						at = (first + i) * 28;
					}
				}
			}
		}
		for (int i = 0; i < 6; i++) {
			int s = at + i * 4;
			// The leaves' light samples are kept 255 times smaller than the
			// faces' (read as the faces' are, every prop came out black).
			cube[i] = new float[] {sample(data, s, 0) * 255.0F, sample(data, s, 1) * 255.0F, sample(data, s, 2) * 255.0F};
		}
		return cube;
	}

	/** The leaf of the world's tree holding a point, or -1. */
	private int leafAt(float[] p) {
		ByteBuffer nodes = map.lump(LUMP_NODES);
		ByteBuffer models = map.lump(LUMP_MODELS);
		if (models.limit() < 48) {
			return -1;
		}
		int node = models.getInt(36);
		for (int guard = 0; node >= 0 && guard < 4096; guard++) {
			if (node * 32 + 12 > nodes.limit()) {
				return -1;
			}
			int plane = nodes.getInt(node * 32);
			if (plane < 0 || plane * 20 + 16 > planes.limit()) {
				return -1;
			}
			float d = planes.getFloat(plane * 20) * p[0] + planes.getFloat(plane * 20 + 4) * p[1] + planes.getFloat(plane * 20 + 8) * p[2]
					- planes.getFloat(plane * 20 + 12);
			node = nodes.getInt(node * 32 + (d >= 0 ? 4 : 8));
		}
		return node < 0 ? -1 - node : -1;
	}

	/** A Valve material file's settings: key to value, lower case keys; the shader name under "". */
	static final class Vmt {
		private Vmt() {
		}

		static Map<String, String> parse(String text) {
			Map<String, String> out = new LinkedHashMap<>();
			List<String> tokens = tokens(text);
			if (!tokens.isEmpty()) {
				out.put("", tokens.get(0).toLowerCase(Locale.ROOT));
			}
			// Keys and values at any depth (a patch's insert and replace blocks
			// included); the first of a key wins, which is the patch's own.
			for (int i = 1; i + 1 < tokens.size(); i++) {
				String key = tokens.get(i);
				String value = tokens.get(i + 1);
				if (key.equals("{") || key.equals("}")) {
					continue;
				}
				if (value.equals("{")) {
					continue;
				}
				if (value.equals("}")) {
					continue;
				}
				out.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
				i++;
			}
			return out;
		}

		private static List<String> tokens(String text) {
			List<String> out = new ArrayList<>();
			int i = 0;
			int n = text.length();
			while (i < n) {
				char c = text.charAt(i);
				if (Character.isWhitespace(c)) {
					i++;
				} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
					while (i < n && text.charAt(i) != '\n') {
						i++;
					}
				} else if (c == '{' || c == '}') {
					out.add(String.valueOf(c));
					i++;
				} else if (c == '"') {
					int end = text.indexOf('"', i + 1);
					if (end < 0) {
						end = n;
					}
					out.add(text.substring(i + 1, end));
					i = end + 1;
				} else {
					int start = i;
					while (i < n && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '{' && text.charAt(i) != '}'
							&& text.charAt(i) != '"') {
						i++;
					}
					out.add(text.substring(start, i));
				}
			}
			return out;
		}
	}
}
