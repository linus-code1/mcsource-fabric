package com.mcsource.client.map;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.mcsource.client.mob.MippedTexture;
import com.mcsource.model.RiggedModel;
import com.mcsource.world.hl2.Hl2MapGenerator;
import com.mcsource.world.hl2.MapScene;
import com.mcsource.world.hl2.MapVoxels;

/**
 * Draws a map world's map exactly as the game draws it: its own faces,
 * textures, baked light and props, over the unseen blocks you walk on.
 * The map is read once on a worker thread when the world is entered, then
 * kept on the graphics card, one batch of triangles per material.
 */
public final class MapRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");

	private static final VertexFormat FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.build();
	private static final int VERTEX_BYTES = 32;

	public static final RenderPipeline OPAQUE = pipeline("map_world", false, false);
	public static final RenderPipeline CUTOUT = pipeline("map_world_cutout", true, false);
	public static final RenderPipeline TRANSLUCENT = pipeline("map_world_translucent", false, true);

	private static RenderPipeline pipeline(String name, boolean cutout, boolean translucent) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("mcsource", "pipeline/" + name))
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayouts.PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
				.withVertexShader(Identifier.fromNamespaceAndPath("mcsource", "core/map_world"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("mcsource", "core/map_world"))
				.withVertexBinding(0, FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false);
		if (cutout || translucent) {
			builder.withShaderDefine("ALPHA_CUTOUT", translucent ? 0.02F : 0.5F);
		}
		if (translucent) {
			builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false));
		} else {
			builder.withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT);
		}
		return builder.build();
	}

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MC:Source map");
		thread.setDaemon(true);
		return thread;
	});

	/** One material's triangles on the graphics card. */
	private record Batch(GpuBuffer vertices, int count, int mode, GpuTextureView base, GpuSampler baseSampler, GpuTextureView base2,
			GpuSampler base2Sampler) {
	}

	private static @Nullable ClientLevel level;
	private static @Nullable String mapName;
	private static @Nullable CompletableFuture<MapScene> building;
	private static final List<Batch> BATCHES = new ArrayList<>();
	private static final List<AutoCloseable> OWNED = new ArrayList<>();
	private static @Nullable GpuTexture lightmap;
	private static @Nullable GpuTextureView lightmapView;

	private MapRenderer() {
	}

	/** The map generator of the world being played, when it is a map world played on this computer. */
	private static @Nullable Hl2MapGenerator generator(@Nullable ClientLevel clientLevel) {
		if (clientLevel == null || clientLevel.dimension() != Level.OVERWORLD) {
			return null;
		}
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || server.overworld() == null) {
			return null;
		}
		return server.overworld().getChunkSource().getGenerator() instanceof Hl2MapGenerator map ? map : null;
	}

	/** Whether this level is a map world, drawn by this renderer. */
	public static boolean isMapWorld(@Nullable ClientLevel clientLevel) {
		return generator(clientLevel) != null;
	}

	/** Draws the map into the pass the solid blocks are drawn in. */
	public static void draw(RenderPass pass) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel current = minecraft.level;
		if (current != level) {
			clear();
			level = current;
			Hl2MapGenerator map = generator(current);
			if (map != null) {
				mapName = map.map();
				building = CompletableFuture.supplyAsync(() -> {
					MapVoxels voxels = map.voxels();
					if (voxels == null) {
						return null;
					}
					long start = System.nanoTime();
					MapScene scene = new MapScene(voxels.map, voxels.files, voxels.minX, voxels.minY - MapVoxels.BASE_Y, voxels.minZ);
					LOGGER.info("Read map {} to draw: {} faces, {} props, {} materials, lightmap {} x {}, in {} ms", map.map(), scene.faces,
							scene.props, scene.materials.size(), scene.lightmapWidth, scene.lightmapHeight, (System.nanoTime() - start) / 1_000_000);
					return scene;
				}, WORKER);
			}
		}
		if (building != null && building.isDone()) {
			CompletableFuture<MapScene> done = building;
			building = null;
			try {
				MapScene scene = done.join();
				if (scene != null) {
					upload(scene);
				}
			} catch (RuntimeException e) {
				LOGGER.error("Could not read map {} to draw", mapName, e);
			}
		}
		if (BATCHES.isEmpty() || lightmapView == null) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		Vector3f offset = new Vector3f((float) -camera.x, (float) -camera.y, (float) -camera.z);
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),
				new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), offset, new Matrix4f());
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLES);
		GpuSampler lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		pass.pushDebugGroup(() -> "MC:Source map");
		for (int mode = MapScene.OPAQUE; mode <= MapScene.TRANSLUCENT; mode++) {
			RenderPipeline pipeline = mode == MapScene.OPAQUE ? OPAQUE : mode == MapScene.CUTOUT ? CUTOUT : TRANSLUCENT;
			boolean set = false;
			for (Batch batch : BATCHES) {
				if (batch.mode != mode) {
					continue;
				}
				if (!set) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
					pass.setUniform("DynamicTransforms", transforms);
					set = true;
				}
				GpuBuffer indexBuffer = indices.getBuffer(batch.count);
				pass.setUniform("Sampler0", batch.base, batch.baseSampler);
				pass.setUniform("Sampler1", batch.base2, batch.base2Sampler);
				pass.setUniform("Sampler2", lightmapView, lightSampler);
				pass.setVertexBuffer(0, batch.vertices.slice());
				pass.setIndexBuffer(indexBuffer, indices.type());
				pass.drawIndexed(batch.count, 1, 0, 0, 0);
			}
		}
		pass.popDebugGroup();
	}

	private static void upload(MapScene scene) {
		GpuDevice device = RenderSystem.getDevice();
		Map<RiggedModel.Texture, MippedTexture> made = new IdentityHashMap<>();
		Map<String, MippedTexture> byName = new HashMap<>();
		for (MapScene.Material material : scene.materials) {
			int count = material.vertices();
			if (count == 0) {
				continue;
			}
			MippedTexture base = texture(material.base, made, byName);
			MippedTexture base2 = material.base2 == null ? base : texture(material.base2, made, byName);
			ByteBuffer data = ByteBuffer.allocateDirect(count * VERTEX_BYTES).order(ByteOrder.nativeOrder());
			float[] floats = material.data();
			for (int v = 0; v < count; v++) {
				int f = v * MapScene.FLOATS;
				data.putFloat(floats[f]).putFloat(floats[f + 1]).putFloat(floats[f + 2]);
				data.putFloat(floats[f + 3]).putFloat(floats[f + 4]);
				data.putFloat(floats[f + 5]).putFloat(floats[f + 6]);
				int colour = Float.floatToRawIntBits(floats[f + 7]);
				// Red, green, blue, alpha, one byte each, as the shader reads them.
				data.put((byte) (colour >> 16)).put((byte) (colour >> 8)).put((byte) colour).put((byte) (colour >>> 24));
			}
			data.flip();
			GpuBuffer buffer = device.createBuffer(() -> "MC:Source map " + material.name, GpuBuffer.USAGE_VERTEX, data);
			OWNED.add(buffer::close);
			BATCHES.add(new Batch(buffer, count, material.mode, base.getTextureView(), base.getSampler(), base2.getTextureView(),
					base2.getSampler()));
		}
		NativeImage image = new NativeImage(scene.lightmapWidth, scene.lightmapHeight, false);
		for (int y = 0; y < scene.lightmapHeight; y++) {
			for (int x = 0; x < scene.lightmapWidth; x++) {
				int pixel = scene.lightmap[y * scene.lightmapWidth + x];
				image.setPixel(x, y, pixel == 0 ? 0xFF000000 : pixel);
			}
		}
		lightmap = device.createTexture(() -> "MC:Source map light", 5, GpuFormat.RGBA8_UNORM, scene.lightmapWidth, scene.lightmapHeight, 1, 1);
		lightmapView = device.createTextureView(lightmap);
		device.createCommandEncoder().writeToTexture(lightmap, image, 0, 0, 0, 0);
		image.close();
		LOGGER.info("Drawing map {}: {} batches", mapName, BATCHES.size());
	}

	private static MippedTexture texture(RiggedModel.Texture texture, Map<RiggedModel.Texture, MippedTexture> made,
			Map<String, MippedTexture> byName) {
		MippedTexture known = made.get(texture);
		if (known != null) {
			return known;
		}
		String key = texture.name() + "#" + texture.width() + "x" + texture.height() + (texture.masked() ? "a" : "");
		known = byName.get(key);
		if (known == null) {
			NativeImage image = new NativeImage(Math.max(1, texture.width()), Math.max(1, texture.height()), true);
			for (int y = 0; y < texture.height(); y++) {
				for (int x = 0; x < texture.width(); x++) {
					image.setPixel(x, y, texture.pixels()[y * texture.width() + x]);
				}
			}
			String name = texture.name();
			known = new MippedTexture(() -> "MC:Source map " + name, image);
			byName.put(key, known);
			OWNED.add(known::close);
		}
		made.put(texture, known);
		return known;
	}

	/** Lets go of the map on the graphics card, as when leaving the world. */
	private static void clear() {
		BATCHES.clear();
		for (AutoCloseable owned : OWNED) {
			try {
				owned.close();
			} catch (Exception e) {
				// Already gone.
			}
		}
		OWNED.clear();
		if (lightmapView != null) {
			lightmapView.close();
			lightmapView = null;
		}
		if (lightmap != null) {
			lightmap.close();
			lightmap = null;
		}
		building = null;
		mapName = null;
	}
}
