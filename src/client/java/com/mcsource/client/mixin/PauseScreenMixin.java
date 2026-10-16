package com.mcsource.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mcsource.client.menu.MenuButton;

/**
 * The pause menu as Half-Life 2's in-game menu: the same column of plain
 * words as the main menu, at the left, over the paused game.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	@Shadow
	@Final
	private boolean showPauseMenu;

	protected PauseScreenMixin(Minecraft minecraft, Font font, Component title) {
		super(minecraft, font, title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void mcsource$halfLifePauseMenu(CallbackInfo ci) {
		if (!showPauseMenu) {
			return;
		}
		List<Button> buttons = new ArrayList<>();
		for (var child : children()) {
			if (child instanceof Button button && !(child instanceof SpriteIconButton)) {
				buttons.add(button);
			}
		}
		clearWidgets();
		int x = Math.max(16, width / 12);
		int y = Math.max(height / 2 - buttons.size() * MenuButton.HEIGHT / 2, 16);
		for (Button button : buttons) {
			MenuButton entry = addRenderableWidget(new MenuButton(x, y, button.getMessage(), input -> {
				button.onPress(input);
			}));
			entry.active = button.active;
			y += MenuButton.HEIGHT;
		}
	}
}
