package com.mcsource.client.lod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Draws distant terrain behind the world, in the same pass as the solid
 * blocks and just before them, so the real blocks cover it wherever both
 * are. The mesh is built on a worker thread and rebuilt as the player moves
 * or as chunks load, unload and get seen for the first time.
 */
public final class LodRenderer {
	public static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mcsource", "pipeline/distant_terrain"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withVertexShader(Identifier.fromNamespaceAndPath("mcsource", "core/distant_terrain"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("mcsource", "core/distant_terrain"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.build();

	/** The mesh is rebuilt when the player has moved this far, in blocks. */
	private static final int SNAP = 64;
	/** At most this often for chunks loading and unloading, in milliseconds. */
	private static final long REBUILD_DELAY = 1500;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MC:Source distant terrain");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static @Nullable GpuBuffer buffer;
	private static int quads;
	private static int originX;
	private static int originZ;
	private static int builtChanges = -1;
	private static int builtDistance;
	private static long lastStart;
	private static @Nullable CompletableFuture<LodMesh> building;
	private static boolean stale;

	private LodRenderer() {
	}

	/** Forgets the mesh, as when the level changes. */
	static void clear() {
		stale = true;
		builtChanges = -1;
	}

	/** Draws distant terrain into the pass the solid blocks are drawn in. */
	public static void draw(RenderPass pass) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		LodTerrain.follow(level);
		if (!LodTerrain.active(level)) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		int wantX = Math.floorDiv((int) Math.floor(camera.x), SNAP) * SNAP;
		int wantZ = Math.floorDiv((int) Math.floor(camera.z), SNAP) * SNAP;
		int distance = minecraft.options.getEffectiveRenderDistance() * 16;
		collect();
		long now = Util.getMillis();
		boolean moved = wantX != originX || wantZ != originZ || distance != builtDistance || buffer == null || stale;
		boolean changed = LodTerrain.changes != builtChanges && now - lastStart > REBUILD_DELAY;
		if (building == null && (moved || changed)) {
			lastStart = now;
			int changes = LodTerrain.changes;
			builtDistance = distance;
			stale = false;
			building = CompletableFuture.supplyAsync(() -> LodMesh.build(wantX, wantZ, distance, changes), WORKER);
		}
		if (buffer == null || quads == 0) {
			return;
		}
		Vector3f offset = new Vector3f((float) (originX - camera.x), (float) -camera.y, (float) (originZ - camera.z));
		// Night and storms darken distant land as they do the sky light on the blocks.
		float daylight = Math.max(0.1F, 1.0F - level.getSkyDarken() / 12.5F);
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(),
				new Vector4f(daylight, daylight, daylight * 1.04F, 1.0F), offset, new Matrix4f());
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = indices.getBuffer(quads * 6);
		pass.pushDebugGroup(() -> "Distant terrain");
		pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
		pass.setUniform("DynamicTransforms", transforms);
		pass.setVertexBuffer(0, buffer.slice());
		pass.setIndexBuffer(indexBuffer, indices.type());
		pass.drawIndexed(quads * 6, 1, 0, 0, 0);
		pass.popDebugGroup();
	}

	/** Uploads a finished mesh, if one is ready. */
	private static void collect() {
		CompletableFuture<LodMesh> done = building;
		if (done == null || !done.isDone()) {
			return;
		}
		building = null;
		LodMesh mesh;
		try {
			mesh = done.join();
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("mcsource").warn("Distant terrain failed to build", e);
			return;
		}
		if (stale) {
			// Built for a level that has since gone.
			return;
		}
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		quads = mesh.quads;
		originX = mesh.originX;
		originZ = mesh.originZ;
		builtChanges = mesh.changes;
		if (quads > 0) {
			buffer = RenderSystem.getDevice().createBuffer(() -> "Distant terrain", GpuBuffer.USAGE_VERTEX, mesh.data);
		}
	}
}
