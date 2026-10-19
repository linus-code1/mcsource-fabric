package com.mcsource.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.shapes.Shapes;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.client.mob.KartRenderer;
import com.mcsource.client.tree.TreeModels;
import com.mcsource.client.tree.TreeRenderer;
import com.mcsource.kart.Karts;
import com.mcsource.terrain.SmoothTerrain;
import com.mcsource.tree.Trees;

public class McSourceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		org.slf4j.LoggerFactory.getLogger("mcsource").info("MC:Source build {}", build());
		// Every block model is wrapped; whether a block is smoothed is decided per
		// position at mesh time from the block tags, which arrive with the world.
		ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake()
				.register((model, modelContext) -> {
					// Models are being baked again: the props may have changed with the packs.
					com.mcsource.client.prop.PropModels.reset();
					return new SmoothBlockModel(model);
				}));
		SourceHud.register();
		com.mcsource.client.light.HeldLight.register();
		// Smoothed ground collides as smoothed only where the server has the mod too.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			boolean modded = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(com.mcsource.terrain.ServerSupport.Present.TYPE);
			com.mcsource.terrain.ServerSupport.setSmoothCollision(modded);
			com.mcsource.McSource.LOGGER.info(modded ? "Server has MC:Source: smooth collision on"
					: "Server lacks MC:Source: ground collides as plain blocks");
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				com.mcsource.terrain.ServerSupport.setSmoothCollision(true));
		EntityRendererRegistry.register(Karts.KART, KartRenderer::new);
		EntityRendererRegistry.register(Trees.TREE, TreeRenderer::new);
		// New trees are tree models when there are tree models to draw them with.
		// Reading the models takes a moment, so it's done while the game loads.
		Thread finder = new Thread(() -> Trees.setEnabled(GameInstalls.flag("hl2_trees", true) && TreeModels.available()),
				"MC:Source tree models");
		finder.setDaemon(true);
		finder.start();
		// Smoothed ground has no block outline, as in Source; its stepped
		// collision columns would otherwise show as little stairs.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> {
			Minecraft minecraft = Minecraft.getInstance();
			return minecraft.level == null || !minecraft.level.getBlockState(outline.pos()).is(SmoothTerrain.SMOOTH)
					|| outline.shape() == Shapes.block();
		});
	}

	/** The mod's version with the commit it was built from, such as 0.1.0+09ecf5c. */
	public static String build() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("mcsource")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
