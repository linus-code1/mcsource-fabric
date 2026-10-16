package com.mcsource.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.shapes.Shapes;

import com.mcsource.terrain.SmoothTerrain;

public class McSourceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Every block model is wrapped; whether a block is smoothed is decided per
		// position at mesh time from the block tags, which arrive with the world.
		ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake()
				.register((model, modelContext) -> new SmoothBlockModel(model)));
		SourceHud.register();
		// Smoothed ground has no block outline, as in Source; its stepped
		// collision columns would otherwise show as little stairs.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> {
			Minecraft minecraft = Minecraft.getInstance();
			return minecraft.level == null || !minecraft.level.getBlockState(outline.pos()).is(SmoothTerrain.SMOOTH)
					|| outline.shape() == Shapes.block();
		});
	}
}
