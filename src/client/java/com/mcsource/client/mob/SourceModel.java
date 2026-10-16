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

/**
 * One of the mod's rigged models ({@code assets/mcsource/models/<name>.mdl}),
 * ready to draw: its textures packed into one image on the GPU and its
 * triangles' texture coordinates mapped into that image.
 */
public final class SourceModel {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final Map<String, Optional<SourceModel>> CACHE = new HashMap<>();

	public final GoldSrcModel model;
	public final Identifier texture;
	private final int[] corners;
	private final int[] normals;
	private final float[] uvs;

	/** Bone-posed vertex positions and normals, reused between draws. */
	private final float[] posed;
	private final float[] posedNormals;

	/** The model with this name, loaded the first time it is asked for, or null if it can't be read. */
	public static SourceModel get(String name) {
		return CACHE.computeIfAbsent(name, SourceModel::load).orElse(null);
	}

	private static Optional<SourceModel> load(String name) {
		Identifier location = Identifier.fromNamespaceAndPath("mcsource", "models/" + name + ".mdl");
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
			if (resource.isEmpty()) {
				LOGGER.warn("Missing model {}", location);
				return Optional.empty();
			}
			byte[] bytes;
			try (InputStream in = resource.get().open()) {
				bytes = in.readAllBytes();
			}
			return Optional.of(new SourceModel(name, GoldSrcModel.read(bytes)));
		} catch (Exception e) {
			LOGGER.warn("Could not read model {}", location, e);
			return Optional.empty();
		}
	}

	private SourceModel(String name, GoldSrcModel model) {
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
			GoldSrcModel.Texture t = model.textures[i];
			for (int y = 0; y < t.height(); y++) {
				for (int x = 0; x < t.width(); x++) {
					image.setPixel(x, top[i] + y, t.pixels()[y * t.width() + x]);
				}
			}
		}
		texture = Identifier.fromNamespaceAndPath("mcsource", "model/" + name);
		Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "MC:Source model " + name, image));

		int count = model.triangles.size();
		corners = new int[count * 3];
		normals = new int[count * 3];
		uvs = new float[count * 6];
		for (int i = 0; i < count; i++) {
			GoldSrcModel.Triangle tri = model.triangles.get(i);
			GoldSrcModel.Texture t = model.textures.length > 0 ? model.textures[tri.texture()] : null;
			for (int k = 0; k < 3; k++) {
				corners[i * 3 + k] = tri.vertex()[k];
				normals[i * 3 + k] = tri.normal()[k];
				if (t != null) {
					// Texel centres, kept inside the triangle's own texture.
					float s = Mth.clamp(tri.s()[k] + 0.5F, 0.5F, t.width() - 0.5F);
					float v = Mth.clamp(tri.t()[k] + 0.5F, 0.5F, t.height() - 0.5F);
					uvs[i * 6 + k * 2] = s / width;
					uvs[i * 6 + k * 2 + 1] = (top[tri.texture()] + v) / height;
				}
			}
		}
		posed = new float[model.vertices.length];
		posedNormals = new float[model.normals.length];
	}

	/** Moves every vertex and normal with its bone. */
	public void skin(Matrix4f[] bones) {
		Vector3f p = new Vector3f();
		for (int i = 0; i < model.vertexBones.length; i++) {
			bones[model.vertexBones[i]].transformPosition(model.vertices[i * 3], model.vertices[i * 3 + 1], model.vertices[i * 3 + 2], p);
			posed[i * 3] = p.x;
			posed[i * 3 + 1] = p.y;
			posed[i * 3 + 2] = p.z;
		}
		for (int i = 0; i < model.normalBones.length; i++) {
			bones[model.normalBones[i]].transformDirection(model.normals[i * 3], model.normals[i * 3 + 1], model.normals[i * 3 + 2], p);
			p.normalize();
			posedNormals[i * 3] = p.x;
			posedNormals[i * 3 + 1] = p.y;
			posedNormals[i * 3 + 2] = p.z;
		}
	}

	/** How model axes become drawing axes. */
	public enum Axes {
		/** Half-Life's z-up, x-forward axes to Minecraft's y-up, z-forward ones. */
		HALF_LIFE,
		/** Already in the target space. */
		SAME,
		/** Already in the target space, mirrored left to right. */
		MIRRORED
	}

	/**
	 * Writes the last skinned pose as triangles (each a quad with the last
	 * corner repeated). Positions are multiplied by {@code scale}.
	 */
	public void emit(PoseStack.Pose pose, VertexConsumer consumer, Axes axes, float scale, int light, int overlay) {
		int count = corners.length / 3;
		boolean mirrored = axes == Axes.MIRRORED;
		for (int i = 0; i < count; i++) {
			for (int c = 0; c < 4; c++) {
				// A mirrored mesh is wound the other way round.
				int k = c == 3 ? 2 : mirrored ? 2 - c : c;
				int v = corners[i * 3 + k] * 3;
				int n = normals[i * 3 + k] * 3;
				float x = posed[v];
				float y = posed[v + 1];
				float z = posed[v + 2];
				float nx = posedNormals[n];
				float ny = posedNormals[n + 1];
				float nz = posedNormals[n + 2];
				switch (axes) {
					case HALF_LIFE -> {
						float t = x;
						x = y;
						y = z;
						z = t;
						t = nx;
						nx = ny;
						ny = nz;
						nz = t;
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
						.setUv(uvs[i * 6 + k * 2], uvs[i * 6 + k * 2 + 1])
						.setOverlay(overlay)
						.setLight(light)
						.setNormal(pose, nx, ny, nz);
			}
		}
	}
}
