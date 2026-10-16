package com.mcsource.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

public class McSourceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Every block model is wrapped; whether a block is smoothed is decided per
		// position at mesh time from the block tags, which arrive with the world.
		ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake()
				.register((model, modelContext) -> new SmoothBlockModel(model)));
	}
}
