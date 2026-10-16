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
			LOGGER.info("Using model {} ({} bones, {} triangles, {} animations)", reference, model.boneCount(), model.triangleCount(),
					model.sequences.length);
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
		Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "MC:Source model " + source, image));

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
