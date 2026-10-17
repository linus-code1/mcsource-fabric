package com.mcsource.client.prop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.BlockTextures;
import com.mcsource.model.source.GameFiles;
import com.mcsource.model.source.StudioModel;

/**
 * Furniture blocks (chests, crafting tables, beds, campfires and so on) drawn
 * as Half-Life 2 props. They stay ordinary blocks: the prop's triangles go
 * into the block's chunk mesh with its textures in the block atlas, so a
 * chest is still a chest you open, only it looks like a wooden crate.
 *
 * <p>Props are made with the block texture pack: each one is read from the
 * game, posed, fitted into its block and written to the pack as a mesh file
 * plus its textures, which the block atlas then picks up.
 */
public final class PropModels {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final int MAGIC = 0x4D435350;
	private static final int MAX_TEXTURE = 512;

	/**
	 * Which prop each block becomes: block ids (a {@code *} matches any
	 * text) and model paths to try in order, then options.
	 * {@code size=WxD} is the footprint in blocks (a bed is 1x2),
	 * {@code h=} the most height it may take, {@code fill=} how much of
	 * the footprint it fills, {@code yaw=} turns the model, {@code turn=no}
	 * keeps it from turning with the block, and {@code keep=slanted} keeps
	 * the block's own slanted quads (a campfire's flames).
	 */
	public static final String DEFAULTS = """
			chest = models/props_junk/wood_crate001a.mdl models/props_junk/wood_crate002a.mdl models/props_junk/wood_crate00*.mdl fill=0.88
			trapped_chest = models/props_junk/wood_crate002a.mdl models/props_junk/wood_crate001a.mdl models/props_junk/wood_crate00*.mdl fill=0.88
			crafting_table = models/props_c17/furnituretable002a.mdl models/props_c17/furnituretable001a.mdl models/props_c17/furnituretable00*.mdl models/props_wasteland/cafeteria_table001a.mdl models/props_interiors/furniture_desk01a.mdl
			fletching_table = models/props_c17/furnituretable003a.mdl models/props_c17/furnituretable00*.mdl models/props_interiors/furniture_desk01a.mdl
			cartography_table = models/props_interiors/furniture_desk01a.mdl models/props_c17/furnituretable00*.mdl
			*_bed = models/props_c17/furniturebed001a.mdl models/props_c17/furniturebed*.mdl models/props_wasteland/prison_bedframe001*.mdl size=1x2 h=0.7
			campfire = models/props_*/*campfire*.mdl models/props_*/*firepit*.mdl models/props_*/*fire_pit*.mdl models/props_junk/wood_pallet001a.mdl h=0.45 keep=slanted
			soul_campfire = models/props_*/*campfire*.mdl models/props_*/*firepit*.mdl models/props_*/*fire_pit*.mdl models/props_junk/wood_pallet001a.mdl h=0.45 keep=slanted
			furnace = models/props_c17/furniturestove001a.mdl models/props_wasteland/kitchen_stove00*.mdl models/props_c17/furnace01a.mdl
			smoker = models/props_wasteland/kitchen_stove00*.mdl models/props_c17/furniturestove001a.mdl
			blast_furnace = models/props_c17/furnace01a.mdl models/props_c17/furniturestove001a.mdl
			barrel = models/props_c17/oildrum001.mdl models/props_c17/oildrum*.mdl models/bluebarrel001.mdl turn=no
			cauldron = models/props_c17/metalpot001a.mdl models/props_c17/metalpot*.mdl models/props_interiors/pot0*.mdl turn=no h=0.8
			composter = models/props_junk/trashbin01a.mdl models/props_trainstation/trashcan_indoor001a.mdl models/props_junk/trashdumpster0*.mdl turn=no
			jukebox = models/props_lab/citizenradio.mdl models/props_c17/consolebox0*.mdl h=0.8
			lectern = models/props_c17/furnituredrawer001a.mdl models/props_c17/furnituredrawer00*.mdl
			""";

	private static final String USER_LIST = """
			# Your changes to which Half-Life 2 prop each furniture block becomes.
			# A line here replaces the mod's own line for that block, for example:
			#   chest = models/props_junk/wood_crate002a.mdl fill=0.9
			# Give a block no models to keep Minecraft's look for it:
			#   crafting_table =
			# Options: size=WxD (footprint in blocks), h= (most height), fill= (how
			# much of the footprint), yaw= (degrees), turn=no, keep=slanted.
			# Delete the hl2_block_pack folder or change this file, then restart.
			""";

	/** A prop fitted into its block: triangles in block units, for a block facing south. */
	public record Mesh(String[] sprites, boolean[] masked, boolean turns, boolean keepSlanted, short[] texture, float[] positions,
			float[] uvs) {
		public int triangles() {
			return texture.length;
		}
	}

	private record Entry(String key, Pattern blocks, String[] models, Map<String, String> options) {
	}

	private static volatile Map<Block, Mesh> meshes;

	private PropModels() {
	}

	/** The user's own list, made on first use. */
	public static String userList(Path root) throws IOException {
		Path file = root.resolve("props.txt");
		if (!Files.isRegularFile(file)) {
			Files.writeString(file, USER_LIST);
		}
		return Files.readString(file);
	}

	private static List<Entry> parse(String text) {
		Map<String, Entry> out = new LinkedHashMap<>();
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
				continue;
			}
			String key = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
			List<String> models = new ArrayList<>();
			Map<String, String> options = new LinkedHashMap<>();
			for (String word : trimmed.substring(equals + 1).trim().split("\\s+")) {
				int is = word.indexOf('=');
				if (word.isEmpty()) {
					continue;
				}
				if (is > 0) {
					options.put(word.substring(0, is), word.substring(is + 1));
				} else {
					models.add(word.toLowerCase(Locale.ROOT));
				}
			}
			out.put(key, new Entry(key, glob(key.contains(":") ? key : "minecraft:" + key), models.toArray(String[]::new), options));
		}
		return new ArrayList<>(out.values());
	}

	private static Pattern glob(String text) {
		return Pattern.compile(Pattern.quote(text).replace("*", "\\E.*\\Q"));
	}

	private static String fileName(String key) {
		return key.replaceAll("[^a-z0-9_]", "_");
	}

	// ------------------------------------------------------------ making props

	/**
	 * Reads, fits and writes every prop into the pack at {@code packRoot}.
	 * Returns a line per block for the pack's report.
	 */
	public static String generate(GameFiles files, String userList, Path packRoot) throws IOException {
		List<String> all;
		try {
			all = files.list("models/");
		} catch (IOException | RuntimeException e) {
			all = List.of();
		}
		Map<String, Entry> entries = new LinkedHashMap<>();
		for (Entry entry : parse(DEFAULTS)) {
			entries.put(entry.key, entry);
		}
		for (Entry entry : parse(userList)) {
			entries.put(entry.key, entry);
		}
		Path textures = packRoot.resolve("assets/mcsource/textures/block/props");
		Path meshFolder = packRoot.resolve("assets/mcsource/props");
		Files.createDirectories(textures);
		Files.createDirectories(meshFolder);
		StringBuilder report = new StringBuilder();
		StringBuilder index = new StringBuilder();
		for (Entry entry : entries.values()) {
			String used = null;
			for (String pattern : entry.models) {
				for (String path : matches(pattern, all)) {
					try {
						if (files.read(path) == null) {
							continue;
						}
						RiggedModel model = StudioModel.load(files, path, null);
						if (model == null || model.triangleCount() == 0) {
							continue;
						}
						byte[] mesh = fit(model, entry, textures);
						if (mesh == null) {
							continue;
						}
						Files.write(meshFolder.resolve(fileName(entry.key) + ".mesh"), mesh);
						index.append(entry.key).append('\n');
						used = path;
					} catch (IOException | RuntimeException e) {
						LOGGER.warn("Could not make a prop from {}", path, e);
					}
					break;
				}
				if (used != null) {
					break;
				}
			}
			report.append("prop ").append(entry.key).append(" = ").append(used == null ? "(nothing found, Minecraft's is kept)" : used)
					.append('\n');
		}
		Files.writeString(meshFolder.resolve("props.txt"), index.toString());
		return report.toString();
	}

	/** Model paths matching a pattern: the path itself, or those a {@code *} pattern matches in the list. */
	private static List<String> matches(String pattern, List<String> all) {
		if (!pattern.contains("*")) {
			return List.of(pattern);
		}
		Pattern regex = glob(pattern);
		List<String> out = new ArrayList<>();
		for (String path : all) {
			if (regex.matcher(path.toLowerCase(Locale.ROOT)).matches()) {
				out.add(path);
			}
		}
		out.sort(null);
		return out;
	}

	private static float option(Entry entry, String name, float fallback) {
		try {
			return entry.options.containsKey(name) ? Float.parseFloat(entry.options.get(name)) : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Poses the model, fits it into the block and writes its textures; the mesh file's bytes, or null. */
	private static byte[] fit(RiggedModel model, Entry entry, Path textures) throws IOException {
		RiggedModel.Pose pose = new RiggedModel.Pose(model);
		model.bindPose(pose);
		Matrix4f[] bones = new Matrix4f[model.boneCount()];
		model.skinning(pose, bones);
		int vertices = model.positions.length / 3;
		float[] posed = new float[vertices * 3];
		Vector3f p = new Vector3f();
		for (int i = 0; i < vertices; i++) {
			float x = 0;
			float y = 0;
			float z = 0;
			for (int k = 0; k < 3; k++) {
				float w = model.positionWeights[i * 3 + k];
				if (w <= 0.0F) {
					continue;
				}
				bones[model.positionBones[i * 3 + k]].transformPosition(model.positions[i * 3], model.positions[i * 3 + 1],
						model.positions[i * 3 + 2], p);
				x += p.x * w;
				y += p.y * w;
				z += p.z * w;
			}
			// Half-Life's z-up axes to Minecraft's y-up ones; the model's front (+x) faces south.
			posed[i * 3] = y;
			posed[i * 3 + 1] = z;
			posed[i * 3 + 2] = x;
		}
		float yaw = (float) Math.toRadians(option(entry, "yaw", 0.0F));
		float width = 1.0F;
		float depth = 1.0F;
		String size = entry.options.get("size");
		if (size != null && size.contains("x")) {
			try {
				width = Float.parseFloat(size.substring(0, size.indexOf('x')));
				depth = Float.parseFloat(size.substring(size.indexOf('x') + 1));
			} catch (NumberFormatException e) {
				// keep 1x1
			}
		}
		float[] low = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] high = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < vertices; i++) {
			float x = posed[i * 3];
			float z = posed[i * 3 + 2];
			posed[i * 3] = (float) (x * Math.cos(yaw) - z * Math.sin(yaw));
			posed[i * 3 + 2] = (float) (x * Math.sin(yaw) + z * Math.cos(yaw));
		}
		boolean[] seen = new boolean[vertices];
		for (int corner : model.cornerVertex) {
			if (seen[corner]) {
				continue;
			}
			seen[corner] = true;
			for (int a = 0; a < 3; a++) {
				low[a] = Math.min(low[a], posed[corner * 3 + a]);
				high[a] = Math.max(high[a], posed[corner * 3 + a]);
			}
		}
		float dx = high[0] - low[0];
		float dy = high[1] - low[1];
		float dz = high[2] - low[2];
		if (dx <= 0.0F || dy <= 0.0F || dz <= 0.0F) {
			return null;
		}
		// A long prop lies along the long side of its footprint.
		boolean swap = (width > depth) != (dx > dz) && Math.abs(width - depth) > 0.01F;
		if (swap) {
			float t = dx;
			dx = dz;
			dz = t;
		}
		float fill = option(entry, "fill", 1.0F);
		float scale = Math.min(Math.min(width * fill / dx, depth * fill / dz), option(entry, "h", 1.0F) / dy);
		// The footprint runs from the block back over the next one (a bed's foot).
		float centreX = 0.5F;
		float centreZ = 1.0F - depth / 2.0F;
		float midX = (low[0] + high[0]) / 2.0F;
		float midZ = (low[2] + high[2]) / 2.0F;

		// Textures.
		int count = model.textures.length;
		String[] sprites = new String[count];
		boolean[] masked = new boolean[count];
		for (int i = 0; i < count; i++) {
			RiggedModel.Texture t = model.textures[i];
			int[] pixels = t.pixels();
			int w = Math.max(1, t.width());
			int h = Math.max(1, t.height());
			while (w > MAX_TEXTURE || h > MAX_TEXTURE) {
				pixels = half(pixels, w, h);
				w = Math.max(1, w / 2);
				h = Math.max(1, h / 2);
			}
			// Tiny textures (a flat colour) would hold back mipmapping for the whole block atlas.
			int grow = 1;
			while (Math.min(w, h) * grow < 16) {
				grow *= 2;
			}
			if (grow > 1) {
				int[] big = new int[w * grow * h * grow];
				for (int y = 0; y < h * grow; y++) {
					for (int x = 0; x < w * grow; x++) {
						big[y * w * grow + x] = pixels[(y / grow) * w + x / grow];
					}
				}
				pixels = big;
				w *= grow;
				h *= grow;
			}
			String name = fileName(entry.key) + "_" + i;
			BlockTextures.writePng(textures.resolve(name + ".png"), pixels, w, h);
			sprites[i] = "mcsource:block/props/" + name;
			masked[i] = t.masked();
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(MAGIC);
		out.writeInt(1);
		out.writeInt(count);
		for (int i = 0; i < count; i++) {
			out.writeUTF(sprites[i]);
			out.writeBoolean(masked[i]);
		}
		out.writeBoolean(!"no".equals(entry.options.get("turn")));
		out.writeBoolean("slanted".equals(entry.options.get("keep")));
		int triangles = model.triangleCount();
		out.writeInt(triangles);
		for (int t = 0; t < triangles; t++) {
			out.writeShort(model.triangleTexture[t]);
			float[] u = new float[3];
			float[] v = new float[3];
			for (int k = 0; k < 3; k++) {
				int corner = t * 3 + k;
				u[k] = model.cornerUv[corner * 2];
				v[k] = model.cornerUv[corner * 2 + 1];
			}
			// Atlas sprites don't repeat: bring each triangle's coordinates into one tile.
			float shiftU = (float) Math.floor(Math.min(u[0], Math.min(u[1], u[2])));
			float shiftV = (float) Math.floor(Math.min(v[0], Math.min(v[1], v[2])));
			for (int k = 0; k < 3; k++) {
				int vertex = model.cornerVertex[t * 3 + k];
				float x = posed[vertex * 3] - midX;
				float y = posed[vertex * 3 + 1] - low[1];
				float z = posed[vertex * 3 + 2] - midZ;
				if (swap) {
					float s = x;
					x = -z;
					z = s;
				}
				out.writeFloat(centreX + x * scale);
				out.writeFloat(y * scale);
				out.writeFloat(centreZ + z * scale);
				out.writeFloat(clamp01(u[k] - shiftU));
				out.writeFloat(clamp01(v[k] - shiftV));
			}
		}
		out.flush();
		return bytes.toByteArray();
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	/** Halves an image, averaging each 2x2 square (weighted by alpha so cut-out edges stay clean). */
	private static int[] half(int[] pixels, int w, int h) {
		int nw = Math.max(1, w / 2);
		int nh = Math.max(1, h / 2);
		int[] out = new int[nw * nh];
		for (int y = 0; y < nh; y++) {
			for (int x = 0; x < nw; x++) {
				float a = 0;
				float r = 0;
				float g = 0;
				float b = 0;
				for (int k = 0; k < 4; k++) {
					int sx = Math.min(w - 1, x * 2 + (k & 1));
					int sy = Math.min(h - 1, y * 2 + (k >> 1));
					int c = pixels[sy * w + sx];
					float alpha = (c >>> 24) / 255.0F;
					a += alpha;
					r += (c >> 16 & 255) * alpha;
					g += (c >> 8 & 255) * alpha;
					b += (c & 255) * alpha;
				}
				int ia = Math.round(a / 4.0F * 255.0F);
				if (a > 0.0F) {
					r /= a;
					g /= a;
					b /= a;
				}
				out[y * nw + x] = ia << 24 | Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
			}
		}
		return out;
	}

	// ------------------------------------------------------------ using props

	/** The prop this block is drawn as, or null. */
	public static Mesh mesh(BlockState state) {
		Map<Block, Mesh> all = meshes;
		if (all == null) {
			all = load();
		}
		return all.get(state.getBlock());
	}

	/** Whether this block is drawn as a prop (so blocks beside it don't hide their faces behind it). */
	public static boolean isProp(BlockState state) {
		return mesh(state) != null;
	}

	/** Forgets the loaded props, after resources reload. */
	public static void reset() {
		meshes = null;
	}

	private static synchronized Map<Block, Mesh> load() {
		if (meshes != null) {
			return meshes;
		}
		Map<Block, Mesh> out = new java.util.HashMap<>();
		try {
			var manager = Minecraft.getInstance().getResourceManager();
			Optional<Resource> index = manager.getResource(Identifier.fromNamespaceAndPath("mcsource", "props/props.txt"));
			if (index.isPresent()) {
				String text;
				try (InputStream in = index.get().open()) {
					text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				}
				for (String key : text.split("\n")) {
					key = key.trim();
					if (key.isEmpty()) {
						continue;
					}
					Optional<Resource> file = manager.getResource(Identifier.fromNamespaceAndPath("mcsource", "props/" + fileName(key) + ".mesh"));
					if (file.isEmpty()) {
						continue;
					}
					Mesh mesh;
					try (InputStream in = file.get().open()) {
						mesh = read(in.readAllBytes());
					}
					if (mesh == null) {
						continue;
					}
					Pattern blocks = glob(key.contains(":") ? key : "minecraft:" + key);
					for (Block block : BuiltInRegistries.BLOCK) {
						if (blocks.matcher(BuiltInRegistries.BLOCK.getKey(block).toString()).matches()) {
							out.putIfAbsent(block, mesh);
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read the furniture props", e);
		}
		if (!out.isEmpty()) {
			LOGGER.info("{} blocks are drawn as Half-Life 2 props", out.size());
		}
		meshes = out;
		return out;
	}

	private static Mesh read(byte[] bytes) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		if (in.readInt() != MAGIC || in.readInt() != 1) {
			return null;
		}
		int count = in.readInt();
		String[] sprites = new String[count];
		boolean[] masked = new boolean[count];
		for (int i = 0; i < count; i++) {
			sprites[i] = in.readUTF();
			masked[i] = in.readBoolean();
		}
		boolean turns = in.readBoolean();
		boolean keep = in.readBoolean();
		int triangles = in.readInt();
		short[] texture = new short[triangles];
		float[] positions = new float[triangles * 9];
		float[] uvs = new float[triangles * 6];
		for (int t = 0; t < triangles; t++) {
			texture[t] = in.readShort();
			for (int k = 0; k < 3; k++) {
				positions[t * 9 + k * 3] = in.readFloat();
				positions[t * 9 + k * 3 + 1] = in.readFloat();
				positions[t * 9 + k * 3 + 2] = in.readFloat();
				uvs[t * 6 + k * 2] = in.readFloat();
				uvs[t * 6 + k * 2 + 1] = in.readFloat();
			}
		}
		return new Mesh(sprites, masked, turns, keep, texture, positions, uvs);
	}
}
