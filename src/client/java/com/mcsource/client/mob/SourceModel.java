package com.mcsource.client.mob;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;

import com.mcsource.model.GoldSrcModel;
import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.StudioModel;

/**
 * A rigged model ready to draw, with its textures packed into one image on
 * the GPU. Models are named by where they come from:
 * <ul>
 * <li>{@code source:models/zombie/classic.mdl}, a Source game model from the
 * player's own Half-Life 2 or other Source game install;</li>
 * <li>{@code halflife:models/zombie.mdl}, a model from the player's own
 * Half-Life install;</li>
 * <li>{@code builtin:zombie}, one of the mod's own models.</li>
 * </ul>
 * A {@code #word,word} suffix keeps only the parts whose material names
 * contain one of the words.
 */
public final class SourceModel {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final Map<String, Optional<SourceModel>> CACHE = new HashMap<>();
	private static int textureCount;

	public final RiggedModel model;
	public final Identifier texture;
	public final String source;
	/** Bind pose height in model units (Half-Life's z axis). */
	public final float height;
	private final float[] uvs;
	private final float[] posed;
	private final float[] posedNormals;

	/** The first of these models that can be loaded, or null. */
	public static SourceModel first(String... references) {
		for (String reference : references) {
			SourceModel model = get(reference);
			if (model != null) {
				return model;
			}
		}
		return null;
	}

	public static SourceModel get(String reference) {
		return CACHE.computeIfAbsent(reference, SourceModel::load).orElse(null);
	}

	private static Optional<SourceModel> load(String reference) {
		String spec = reference;
		String[] filter = null;
		int hash = spec.indexOf('#');
		if (hash >= 0) {
			filter = spec.substring(hash + 1).split(",");
			spec = spec.substring(0, hash);
		}
		int colon = spec.indexOf(':');
		String kind = spec.substring(0, colon);
		String path = spec.substring(colon + 1);
		try {
			RiggedModel model = switch (kind) {
				case "source" -> GameInstalls.source().read(path) == null ? null : StudioModel.load(GameInstalls.source(), path, filter);
				case "halflife" -> {
					byte[] bytes = GameInstalls.halfLife().read(path);
					yield bytes == null ? null : GoldSrcModel.read(bytes).toRigged();
				}
				default -> builtin(path);
			};
			if (model == null) {
				LOGGER.info("Model {} not found", reference);
				return Optional.empty();
			}
			StringBuilder animations = new StringBuilder();
			for (RiggedModel.Sequence sequence : model.sequences) {
				animations.append(animations.isEmpty() ? "" : ", ").append(sequence.label());
				if (!sequence.activity().isEmpty()) {
					animations.append(" (").append(sequence.activity()).append(')');
				}
			}
			LOGGER.info("Using model {} ({} bones, {} triangles, {} textures, animations: {})", reference, model.boneCount(),
					model.triangleCount(), model.textures.length, animations);
			return Optional.of(new SourceModel(reference, model));
		} catch (Exception e) {
			LOGGER.warn("Could not read model {}", reference, e);
			return Optional.empty();
		}
	}

	private static RiggedModel builtin(String name) throws Exception {
		Identifier location = Identifier.fromNamespaceAndPath("mcsource", "models/" + name + ".mdl");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return GoldSrcModel.read(in.readAllBytes()).toRigged();
		}
	}

	private SourceModel(String source, RiggedModel model) {
		this.source = source;
		this.model = model;
		// Stack the textures top to bottom in one image.
		int width = 1;
		int height = 0;
		int[] top = new int[model.textures.length];
		for (int i = 0; i < model.textures.length; i++) {
			top[i] = height;
			width = Math.max(width, model.textures[i].width());
			height += model.textures[i].height();
		}
		height = Math.max(height, 1);
		NativeImage image = new NativeImage(width, height, true);
		for (int i = 0; i < model.textures.length; i++) {
			RiggedModel.Texture t = model.textures[i];
			for (int y = 0; y < t.height(); y++) {
				for (int x = 0; x < t.width(); x++) {
					image.setPixel(x, top[i] + y, t.pixels()[y * t.width() + x]);
				}
			}
		}
		texture = Identifier.fromNamespaceAndPath("mcsource", "model/" + textureCount++);
		DynamicTexture dynamic = new DynamicTexture(() -> "MC:Source model " + source, image) {
			{
				// Smoothly filtered, as Source and GoldSrc draw model textures.
				sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
			}
		};
		Minecraft.getInstance().getTextureManager().register(texture, dynamic);

		// Texture coordinates wrap inside their own texture's band of the image.
		int corners = model.cornerVertex.length;
		uvs = new float[corners * 2];
		for (int c = 0; c < corners; c++) {
			RiggedModel.Texture t = model.textures[model.triangleTexture[c / 3]];
			float u = model.cornerUv[c * 2];
			float v = model.cornerUv[c * 2 + 1];
			u = u - (float) Math.floor(u);
			v = v - (float) Math.floor(v);
			float halfX = 0.5F / t.width();
			float halfY = 0.5F / t.height();
			u = Mth.clamp(u, halfX, 1.0F - halfX);
			v = Mth.clamp(v, halfY, 1.0F - halfY);
			uvs[c * 2] = u * t.width() / width;
			uvs[c * 2 + 1] = (top[model.triangleTexture[c / 3]] + v * t.height()) / height;
		}
		posed = new float[model.positions.length];
		posedNormals = new float[model.normals.length];

		// Height of the model standing in its bind pose, in model units.
		RiggedModel.Pose bind = new RiggedModel.Pose(model);
		model.bindPose(bind);
		Matrix4f[] bones = new Matrix4f[model.boneCount()];
		model.skinning(bind, bones);
		skin(bones);
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (int corner : model.cornerVertex) {
			float z = posed[corner * 3 + 2];
			low = Math.min(low, z);
			high = Math.max(high, z);
		}
		this.height = high > low ? high - low : 0.0F;
	}

	/** One texture per material with wrapping coordinates, for {@link #emitMaterial}; made when first asked for. */
	private Identifier[] materialTextures;

	/**
	 * Each of the model's textures on its own, repeating, so texture
	 * coordinates that run past one tile (as on a tree's bark) wrap properly.
	 */
	public Identifier[] materialTextures() {
		if (materialTextures == null) {
			materialTextures = new Identifier[model.textures.length];
			for (int i = 0; i < model.textures.length; i++) {
				RiggedModel.Texture t = model.textures[i];
				NativeImage image = new NativeImage(Math.max(1, t.width()), Math.max(1, t.height()), true);
				for (int y = 0; y < t.height(); y++) {
					for (int x = 0; x < t.width(); x++) {
						image.setPixel(x, y, t.pixels()[y * t.width() + x]);
					}
				}
				Identifier id = Identifier.fromNamespaceAndPath("mcsource", "model/" + textureCount++);
				String name = source + " " + t.name();
				Minecraft.getInstance().getTextureManager().register(id, new MippedTexture(() -> "MC:Source model " + name, image));
				materialTextures[i] = id;
			}
		}
		return materialTextures;
	}

	/** A flat picture of the model from the front and the side, for drawing it far away. */
	public record Impostor(Identifier front, Identifier side, float frontMin, float frontMax, float sideMin, float sideMax, float bottom,
			float top) {
	}

	private Impostor impostor;
	private static final int IMPOSTOR_WIDTH = 128;
	private static final int IMPOSTOR_HEIGHT = 256;

	/**
	 * Pictures of the model seen from the front (along its x axis) and the
	 * side (along y), drawn once in software from its own triangles and
	 * textures. Far-off trees are drawn as these two crossed pictures: four
	 * quads instead of thousands of triangles.
	 */
	public Impostor impostor() {
		if (impostor == null) {
			float yMin = Float.MAX_VALUE;
			float yMax = -Float.MAX_VALUE;
			float xMin = Float.MAX_VALUE;
			float xMax = -Float.MAX_VALUE;
			float zMin = Float.MAX_VALUE;
			float zMax = -Float.MAX_VALUE;
			for (int i = 0; i < posed.length / 3; i++) {
				xMin = Math.min(xMin, posed[i * 3]);
				xMax = Math.max(xMax, posed[i * 3]);
				yMin = Math.min(yMin, posed[i * 3 + 1]);
				yMax = Math.max(yMax, posed[i * 3 + 1]);
				zMin = Math.min(zMin, posed[i * 3 + 2]);
				zMax = Math.max(zMax, posed[i * 3 + 2]);
			}
			Identifier front = picture(1, 0, yMin, yMax, zMin, zMax, "front");
			Identifier side = picture(0, 1, xMin, xMax, zMin, zMax, "side");
			impostor = new Impostor(front, side, yMin, yMax, xMin, xMax, zMin, zMax);
		}
		return impostor;
	}

	/**
	 * Draws the model looking along the {@code depthAxis}, with
	 * {@code across} running left to right and z up, into a new texture.
	 */
	private Identifier picture(int across, int depthAxis, float min, float max, float bottom, float top, String name) {
		int w = IMPOSTOR_WIDTH;
		int h = IMPOSTOR_HEIGHT;
		int[] colour = new int[w * h];
		float[] depth = new float[w * h];
		java.util.Arrays.fill(depth, -Float.MAX_VALUE);
		float sx = (w - 1) / Math.max(1.0E-3F, max - min);
		float sy = (h - 1) / Math.max(1.0E-3F, top - bottom);
		float[] px = new float[3];
		float[] py = new float[3];
		float[] pd = new float[3];
		for (int t = 0; t < model.triangleCount(); t++) {
			if (hidden != null && hidden[t]) {
				continue;
			}
			RiggedModel.Texture texture = model.textures[model.triangleTexture[t]];
			float shade = 0.0F;
			for (int k = 0; k < 3; k++) {
				int v = model.cornerVertex[t * 3 + k] * 3;
				px[k] = (posed[v + across] - min) * sx;
				py[k] = (top - posed[v + 2]) * sy;
				pd[k] = posed[v + depthAxis];
				int n = model.cornerNormal[t * 3 + k] * 3;
				// Light from above and in front, both faces of a card lit alike.
				shade += 0.55F + 0.45F * Math.abs(posedNormals[n + 2] * 0.7F + posedNormals[n + depthAxis] * 0.3F);
			}
			shade /= 3.0F;
			float area = (px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0]);
			if (Math.abs(area) < 1.0E-6F) {
				continue;
			}
			int x0 = Math.max(0, (int) Math.floor(Math.min(px[0], Math.min(px[1], px[2]))));
			int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(px[0], Math.max(px[1], px[2]))));
			int y0 = Math.max(0, (int) Math.floor(Math.min(py[0], Math.min(py[1], py[2]))));
			int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(py[0], Math.max(py[1], py[2]))));
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					float cx = x + 0.5F;
					float cy = y + 0.5F;
					float b0 = ((px[1] - cx) * (py[2] - cy) - (px[2] - cx) * (py[1] - cy)) / area;
					float b1 = ((px[2] - cx) * (py[0] - cy) - (px[0] - cx) * (py[2] - cy)) / area;
					float b2 = 1.0F - b0 - b1;
					if (b0 < -0.02F || b1 < -0.02F || b2 < -0.02F) {
						continue;
					}
					float d = b0 * pd[0] + b1 * pd[1] + b2 * pd[2];
					int at = y * w + x;
					if (d <= depth[at]) {
						continue;
					}
					float u = b0 * model.cornerUv[t * 6] + b1 * model.cornerUv[t * 6 + 2] + b2 * model.cornerUv[t * 6 + 4];
					float v = b0 * model.cornerUv[t * 6 + 1] + b1 * model.cornerUv[t * 6 + 3] + b2 * model.cornerUv[t * 6 + 5];
					int tx = Math.floorMod((int) Math.floor(u * texture.width()), Math.max(1, texture.width()));
					int ty = Math.floorMod((int) Math.floor(v * texture.height()), Math.max(1, texture.height()));
					int p = texture.pixels()[ty * texture.width() + tx];
					if (texture.masked() && (p >>> 24) < 128) {
						continue;
					}
					depth[at] = d;
					int r = Math.min(255, Math.round((p >> 16 & 255) * shade));
					int g = Math.min(255, Math.round((p >> 8 & 255) * shade));
					int b = Math.min(255, Math.round((p & 255) * shade));
					colour[at] = 0xFF000000 | r << 16 | g << 8 | b;
				}
			}
		}
		NativeImage image = new NativeImage(w, h, true);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setPixel(x, y, colour[y * w + x]);
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath("mcsource", "model/" + textureCount++);
		String label = source + " " + name;
		Minecraft.getInstance().getTextureManager().register(id, new MippedTexture(() -> "MC:Source impostor " + label, image));
		return id;
	}

	private float[] areas;
	private float[] sortedAreas;

	/** Each triangle's area in the bind pose, in square model units. */
	private float[] areas() {
		if (areas == null) {
			float[] out = new float[model.triangleCount()];
			for (int i = 0; i < out.length; i++) {
				int a = model.cornerVertex[i * 3] * 3;
				int b = model.cornerVertex[i * 3 + 1] * 3;
				int c = model.cornerVertex[i * 3 + 2] * 3;
				float ux = posed[b] - posed[a];
				float uy = posed[b + 1] - posed[a + 1];
				float uz = posed[b + 2] - posed[a + 2];
				float vx = posed[c] - posed[a];
				float vy = posed[c + 1] - posed[a + 1];
				float vz = posed[c + 2] - posed[a + 2];
				float cx = uy * vz - uz * vy;
				float cy = uz * vx - ux * vz;
				float cz = ux * vy - uy * vx;
				out[i] = 0.5F * (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
			}
			sortedAreas = out.clone();
			java.util.Arrays.sort(sortedAreas);
			areas = out;
		}
		return areas;
	}

	/** The area below which about {@code 1 - keep} of the triangles fall. */
	private float smallArea(float keep) {
		areas();
		if (sortedAreas.length == 0) {
			return 0.0F;
		}
		return sortedAreas[Math.min(sortedAreas.length - 1, (int) ((1.0F - keep) * sortedAreas.length))];
	}

	/** Whether a material has see-through parts, such as leaves. */
	public boolean cutout(int material) {
		return model.textures[material].masked();
	}

	/**
	 * Like {@link #emit}, but only the triangles of one material, with their
	 * own texture coordinates for {@link #materialTextures()}. For a level of
	 * detail, a cut-out material (leaves) can be thinned to about
	 * {@code keep} of its triangles, each kept one grown by {@code grow}
	 * about its middle so the crown stays about as full. Cut-out cards are lit
	 * as if facing up, as Source lights its foliage, so the crown is not
	 * patchy light and dark where cards face away from the light.
	 */
	public void emitMaterial(PoseStack.Pose pose, VertexConsumer consumer, int material, float scale, int light, int overlay, float keep,
			float grow) {
		int count = model.triangleCount();
		boolean leaves = cutout(material);
		boolean thin = keep < 1.0F && leaves;
		int threshold = (int) (keep * 65536.0F);
		// Solid parts drop their smallest triangles (twigs and thin branches)
		// as the tree gets further away; the trunk and big boughs stay.
		float minArea = keep >= 1.0F || cutout(material) ? 0.0F : smallArea(keep);
		for (int i = 0; i < count; i++) {
			if (model.triangleTexture[i] != material || hidden != null && hidden[i]) {
				continue;
			}
			if (minArea > 0.0F && areas()[i] < minArea) {
				continue;
			}
			// Leaf cards are pairs of triangles; keep or drop both halves together.
			if (thin && ((i >> 1) * 0x9E3779B1 >>> 16) >= threshold) {
				continue;
			}
			float cx = 0.0F;
			float cy = 0.0F;
			float cz = 0.0F;
			float factor = thin ? grow : 1.0F;
			if (thin) {
				for (int k = 0; k < 3; k++) {
					int v = model.cornerVertex[i * 3 + k] * 3;
					cx += posed[v] / 3.0F;
					cy += posed[v + 1] / 3.0F;
					cz += posed[v + 2] / 3.0F;
				}
			}
			for (int c = 0; c < 4; c++) {
				int corner = i * 3 + (c == 3 ? 2 : c);
				int v = model.cornerVertex[corner] * 3;
				int n = model.cornerNormal[corner] * 3;
				float x = cx + (posed[v] - cx) * factor;
				float y = cy + (posed[v + 1] - cy) * factor;
				float z = cz + (posed[v + 2] - cz) * factor;
				// Half-Life's z-up axes to Minecraft's y-up ones.
				consumer.addVertex(pose, y * scale, z * scale, x * scale)
						.setColor(-1)
						.setUv(model.cornerUv[corner * 2], model.cornerUv[corner * 2 + 1])
						.setOverlay(overlay)
						.setLight(light)
						.setNormal(pose, leaves ? 0.0F : posedNormals[n + 1], leaves ? 1.0F : posedNormals[n + 2], leaves ? 0.0F : posedNormals[n]);
			}
		}
	}

	/**
	 * Middle of the vertices of the last skinned pose that follow mostly a
	 * bone whose name (lower case) contains the first of the words that any
	 * bone has, in model units; null when no bone matches.
	 */
	public Vector3f centroid(String... words) {
		for (String word : words) {
			float x = 0;
			float y = 0;
			float z = 0;
			int n = 0;
			for (int i = 0; i < posed.length / 3; i++) {
				int bone = model.positionBones[i * 3];
				if (bone >= 0 && bone < model.boneCount() && model.boneNames[bone].toLowerCase(java.util.Locale.ROOT).contains(word)) {
					x += posed[i * 3];
					y += posed[i * 3 + 1];
					z += posed[i * 3 + 2];
					n++;
				}
			}
			if (n > 0) {
				return new Vector3f(x / n, y / n, z / n);
			}
		}
		return null;
	}

	/** Triangles left out when drawing, or null for none. */
	private boolean[] hidden;

	/**
	 * Leaves out triangles whose corners all mostly follow bones with one of
	 * these words in their names (lower case), such as the upper arm of a
	 * view model, whose open end would otherwise show.
	 */
	public void hideBones(String... words) {
		boolean[] boneHidden = new boolean[model.boneCount()];
		for (int b = 0; b < boneHidden.length; b++) {
			String name = model.boneNames[b].toLowerCase(java.util.Locale.ROOT);
			for (String word : words) {
				boneHidden[b] |= name.contains(word);
			}
		}
		boolean[] mask = new boolean[model.triangleCount()];
		for (int t = 0; t < mask.length; t++) {
			boolean all = true;
			for (int k = 0; k < 3 && all; k++) {
				int v = model.cornerVertex[t * 3 + k];
				int best = 0;
				for (int w = 1; w < 3; w++) {
					if (model.positionWeights[v * 3 + w] > model.positionWeights[v * 3 + best]) {
						best = w;
					}
				}
				all = boneHidden[model.positionBones[v * 3 + best]];
			}
			mask[t] = all;
		}
		hidden = mask;
	}

	/** Moves every vertex and normal with its bones. */
	public void skin(Matrix4f[] bones) {
		skinArray(bones, model.positions, model.positionBones, model.positionWeights, posed, true);
		skinArray(bones, model.normals, model.normalBones, model.normalWeights, posedNormals, false);
	}

	private static void skinArray(Matrix4f[] bones, float[] in, int[] boneIds, float[] weights, float[] out, boolean position) {
		Vector3f p = new Vector3f();
		int count = in.length / 3;
		for (int i = 0; i < count; i++) {
			float x = 0;
			float y = 0;
			float z = 0;
			for (int k = 0; k < 3; k++) {
				float w = weights[i * 3 + k];
				if (w <= 0.0F) {
					continue;
				}
				Matrix4f m = bones[boneIds[i * 3 + k]];
				if (position) {
					m.transformPosition(in[i * 3], in[i * 3 + 1], in[i * 3 + 2], p);
				} else {
					m.transformDirection(in[i * 3], in[i * 3 + 1], in[i * 3 + 2], p);
				}
				x += p.x * w;
				y += p.y * w;
				z += p.z * w;
			}
			if (!position) {
				float length = (float) Math.sqrt(x * x + y * y + z * z);
				if (length > 1.0E-6F) {
					x /= length;
					y /= length;
					z /= length;
				}
			}
			out[i * 3] = x;
			out[i * 3 + 1] = y;
			out[i * 3 + 2] = z;
		}
	}

	/** How model axes become drawing axes. */
	public enum Axes {
		/** Half-Life's z-up, x-forward axes to Minecraft's y-up, z-forward ones. */
		HALF_LIFE,
		/** Already in the target space. */
		SAME,
		/** Already in the target space, mirrored left to right. */
		MIRRORED,
		/** Half-Life's axes, then mirrored left to right (a left-handed view model). */
		HALF_LIFE_MIRRORED
	}

	/**
	 * Writes the last skinned pose as triangles (each a quad with the last
	 * corner repeated). Positions are multiplied by {@code scale}.
	 */
	public void emit(PoseStack.Pose pose, VertexConsumer consumer, Axes axes, float scale, int light, int overlay) {
		int count = model.triangleCount();
		boolean mirrored = axes == Axes.MIRRORED || axes == Axes.HALF_LIFE_MIRRORED;
		for (int i = 0; i < count; i++) {
			if (hidden != null && hidden[i]) {
				continue;
			}
			for (int c = 0; c < 4; c++) {
				// A mirrored mesh is wound the other way round.
				int k = c == 3 ? 2 : mirrored ? 2 - c : c;
				int corner = i * 3 + k;
				int v = model.cornerVertex[corner] * 3;
				int n = model.cornerNormal[corner] * 3;
				float x = posed[v];
				float y = posed[v + 1];
				float z = posed[v + 2];
				float nx = posedNormals[n];
				float ny = posedNormals[n + 1];
				float nz = posedNormals[n + 2];
				switch (axes) {
					case HALF_LIFE, HALF_LIFE_MIRRORED -> {
						float t = x;
						x = y;
						y = z;
						z = t;
						t = nx;
						nx = ny;
						ny = nz;
						nz = t;
						if (mirrored) {
							x = -x;
							nx = -nx;
						}
					}
					case MIRRORED -> {
						x = -x;
						nx = -nx;
					}
					case SAME -> {
					}
				}
				consumer.addVertex(pose, x * scale, y * scale, z * scale)
						.setColor(-1)
						.setUv(uvs[corner * 2], uvs[corner * 2 + 1])
						.setOverlay(overlay)
						.setLight(light)
						.setNormal(pose, nx, ny, nz);
			}
		}
	}
}
