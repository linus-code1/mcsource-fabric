package com.mcsource.client.mixin;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.client.texture.HalfLife2BlockPack;

/**
 * Adds a choice of which installed Source game the block textures are made
 * from to Video Settings. The textures are made again, and reloaded, when
 * the screen is closed after a change.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	@Unique
	private String mcsource$gameWhenOpened;

	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions()V", at = @At("TAIL"))
	private void mcsource$addTextureGame(CallbackInfo ci) {
		mcsource$gameWhenOpened = GameInstalls.textureGame();
		List<String> values = new ArrayList<>();
		values.add("auto");
		for (GameInstalls.Game game : GameInstalls.games()) {
			values.add(game.id());
		}
		String current = mcsource$gameWhenOpened;
		if (!values.contains(current)) {
			values.add(current);
		}
		OptionInstance<String> option = new OptionInstance<>("options.mcsource.texture_game",
				OptionInstance.cachedConstantTooltip(Component.translatable("options.mcsource.texture_game.tooltip")),
				(caption, value) -> Component.translatable("options.generic_value", caption, mcsource$gameName(value)),
				new OptionInstance.Enum<>(values, com.mojang.serialization.Codec.STRING), current,
				value -> GameInstalls.saveSetting("texture_game", value));
		list.addHeader(Component.translatable("options.mcsource.header").withStyle(net.minecraft.ChatFormatting.UNDERLINE,
				net.minecraft.ChatFormatting.BOLD));
		list.addBig(option);
	}

	@Unique
	private static Component mcsource$gameName(String id) {
		if (id.equals("auto")) {
			return Component.translatable("options.mcsource.texture_game.auto");
		}
		for (GameInstalls.Game game : GameInstalls.games()) {
			if (game.id().equals(id)) {
				return Component.literal(game.name());
			}
		}
		return Component.literal(id + " (not found)");
	}

	@Inject(method = "removed()V", at = @At("HEAD"))
	private void mcsource$applyTextureGame(CallbackInfo ci) {
		if (mcsource$gameWhenOpened != null && !mcsource$gameWhenOpened.equals(GameInstalls.textureGame())) {
			mcsource$gameWhenOpened = GameInstalls.textureGame();
			HalfLife2BlockPack.rebuild();
			Minecraft.getInstance().reloadResourcePacks();
		}
	}
}
