package com.mcsource.client.mob;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import com.mcsource.model.GoldSrcModel;

/**
 * Draws a mob with its Source-style rigged model: picks and blends the
 * model's idle, walk, attack and death animations from what the mob is doing,
 * skins the mesh to the posed bones, and submits the triangles.
 */
public final class SourceMobRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final Map<String, Loaded> MODELS = new HashMap<>();
	/** Minecraft's walk animation advances about four steps per block walked. */
	private static final float WALK_STEPS_PER_BLOCK = 4.0F;

	private SourceMobRenderer() {
	}

	/** Draws the mob if it has a rig and the model file is there; returns false to draw it normally. */
	public static boolean submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		SourceRigs.Rig rig = SourceRigs.get(state.entityType);
		if (rig == null) {
			return false;
		}
		Loaded loaded = load(rig);
		if (loaded == null) {
			return false;
		}
		if (state.isInvisible) {
			return true;
		}
		GoldSrcModel model = loaded.model;
		GoldSrcModel.Pose pose = new GoldSrcModel.Pose(model);
		animate(loaded, state, pose);
		Matrix4f[] bones = new Matrix4f[model.bones.length];
		model.matrices(pose, bones);

		poseStack.pushPose();
		poseStack.rotateDegrees(Axis.YP, -state.bodyRot);
		float scale = rig.scale() * state.scale * state.ageScale;
		poseStack.scale(scale, scale, scale);
		int light = state.lightCoords;
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(loaded.texture), (matrices, consumer) -> {
			Vector3f p = new Vector3f();
			Vector3f n = new Vector3f();
			for (Loaded.Face face : loaded.faces) {
				for (int k = 0; k < 3; k++) {
					int v = face.vertex[k];
					bones[model.vertexBones[v]].transformPosition(model.vertices[v * 3], model.vertices[v * 3 + 1], model.vertices[v * 3 + 2], p);
					int ni = face.normal[k];
					bones[model.normalBones[ni]].transformDirection(model.normals[ni * 3], model.normals[ni * 3 + 1], model.normals[ni * 3 + 2], n);
					n.normalize();
					// Half-Life models are z-up and face +x; Minecraft is y-up and
					// a mob at rest faces +z.
					consumer.addVertex(matrices, p.y, p.z, p.x)
							.setColor(-1)
							.setUv(face.u[k], face.v[k])
							.setOverlay(overlay)
							.setLight(light)
							.setNormal(matrices, n.y, n.z, n.x);
				}
				// Triangles go in as quads with the last corner repeated.
				int v = face.vertex[2];
				bones[model.vertexBones[v]].transformPosition(model.vertices[v * 3], model.vertices[v * 3 + 1], model.vertices[v * 3 + 2], p);
				consumer.addVertex(matrices, p.y, p.z, p.x)
						.setColor(-1)
						.setUv(face.u[2], face.v[2])
						.setOverlay(overlay)
						.setLight(light)
						.setNormal(matrices, n.y, n.z, n.x);
			}
		});
		poseStack.popPose();
		return true;
	}

	private static void animate(Loaded loaded, LivingEntityRenderState state, GoldSrcModel.Pose pose) {
		GoldSrcModel model = loaded.model;
		float seconds = state.ageInTicks / 20.0F;
		if (state.deathTime > 0.0F && loaded.death != null) {
			// Minecraft removes a dead mob after a second; play the fall in that time.
			GoldSrcModel.Sequence death = loaded.death;
			model.pose(death, Math.min(state.deathTime / 20.0F, 1.0F) * (death.frameCount() - 1), pose);
			return;
		}
		GoldSrcModel.Sequence idle = loaded.idle != null ? loaded.idle : model.sequences[0];
		model.pose(idle, seconds * idle.fps(), pose);

		if (loaded.walk != null) {
			float weight = Mth.clamp(state.walkAnimationSpeed * 1.5F, 0.0F, 1.0F);
			if (weight > 0.01F) {
				GoldSrcModel.Sequence walk = loaded.walk;
				float frame;
				if (walk.distance() > 1.0F) {
					// Step in time with the ground covered, so feet don't slide.
					float units = state.walkAnimationPos / WALK_STEPS_PER_BLOCK / loaded.rig.scale();
					frame = units / walk.distance() * walk.frameCount();
				} else {
					frame = seconds * walk.fps();
				}
				GoldSrcModel.Pose walking = new GoldSrcModel.Pose(model);
				model.pose(walk, frame, walking);
				model.blend(pose, walking, weight);
			}
		}

		if (loaded.attack != null && state instanceof ArmedEntityRenderState armed && armed.swingAnimation > 0.0F) {
			GoldSrcModel.Pose attacking = new GoldSrcModel.Pose(model);
			GoldSrcModel.Sequence attack = loaded.attack;
			model.pose(attack, armed.swingAnimation * (attack.frameCount() - 1), attacking);
			model.blend(pose, attacking, Mth.clamp(Mth.sin(armed.swingAnimation * Mth.PI) * 3.0F, 0.0F, 1.0F));
		}
	}

	private static Loaded load(SourceRigs.Rig rig) {
		if (MODELS.containsKey(rig.file())) {
			return MODELS.get(rig.file());
		}
		Loaded loaded = null;
		Path file = HalfLifeFiles.model(rig.file());
		if (file == null) {
			LOGGER.info("No {} found in your Half-Life files; that mob keeps its Minecraft look", rig.file());
		} else {
			try {
				loaded = new Loaded(rig, GoldSrcModel.load(file));
				LOGGER.info("Loaded {} ({} bones, {} triangles, {} animations)", file, loaded.model.bones.length,
						loaded.model.triangles.size(), loaded.model.sequences.length);
			} catch (Exception e) {
				LOGGER.warn("Could not read {}", file, e);
			}
		}
		MODELS.put(rig.file(), loaded);
		return loaded;
	}

	/** A model with its textures packed into one image uploaded to the GPU. */
	private static final class Loaded {
		final SourceRigs.Rig rig;
		final GoldSrcModel model;
		final Identifier texture;
		final Face[] faces;
		final GoldSrcModel.Sequence idle;
		final GoldSrcModel.Sequence walk;
		final GoldSrcModel.Sequence attack;
		final GoldSrcModel.Sequence death;

		record Face(int[] vertex, int[] normal, float[] u, float[] v) {
		}

		Loaded(SourceRigs.Rig rig, GoldSrcModel model) {
			this.rig = rig;
			this.model = model;
			idle = model.find(rig.idle());
			walk = model.find(rig.walk());
			attack = model.find(rig.attack());
			death = model.find(rig.death());

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
			String name = rig.file().replaceAll("[^a-z0-9_.-]", "_");
			texture = Identifier.fromNamespaceAndPath("mcsource", "halflife/" + name);
			Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "MC:Source " + rig.file(), image));

			faces = new Face[model.triangles.size()];
			for (int i = 0; i < faces.length; i++) {
				GoldSrcModel.Triangle tri = model.triangles.get(i);
				float[] u = new float[3];
				float[] v = new float[3];
				if (model.textures.length > 0) {
					GoldSrcModel.Texture t = model.textures[tri.texture()];
					for (int k = 0; k < 3; k++) {
						u[k] = tri.s()[k] / width;
						v[k] = (top[tri.texture()] + tri.t()[k]) / height;
						// Keep texture coordinates inside their own texture.
						u[k] = Mth.clamp(u[k], 0.0F, (float) t.width() / width);
					}
				}
				faces[i] = new Face(tri.vertex(), tri.normal(), u, v);
			}
		}
	}
}
