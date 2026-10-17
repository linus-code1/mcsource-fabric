package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import com.mcsource.client.menu.MenuButton;

/**
 * The title screen laid out like Half-Life 2's main menu: the game's name in
 * large letters at the top left and a column of plain words below it, over
 * the moving background, with no logo, splash text or button boxes.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	private static final FontDescription TITLE_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("mcsource", "menu_title"));
	private static final Component TITLE_TEXT = Component.literal("MC:SOURCE").withStyle(Style.EMPTY.withFont(TITLE_FONT));

	@Shadow
	private SplashRenderer splash;

	@Shadow
	private Component getMultiplayerDisabledReason() {
		return null;
	}

	protected TitleScreenMixin(Minecraft minecraft, net.minecraft.client.gui.Font font, Component title) {
		super(minecraft, font, title);
	}

	/** No Realms news and invitation icons floating over the background. */
	@Inject(method = "realmsNotificationsEnabled", at = @At("HEAD"), cancellable = true)
	private void mcsource$noRealmsIcons(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}

	private int mcsource$left() {
		return Math.max(16, width / 12);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void mcsource$halfLifeMenu(CallbackInfo ci) {
		splash = null;
		clearWidgets();
		Screen self = this;
		int x = mcsource$left();
		int y = Math.max(height / 2, 84);
		addRenderableWidget(new MenuButton(x, y, Component.translatable("menu.singleplayer"),
				() -> minecraft.gui.setScreen(new SelectWorldScreen(self))));
		y += MenuButton.HEIGHT;
		MenuButton multiplayer = addRenderableWidget(new MenuButton(x, y, Component.translatable("menu.multiplayer"),
				() -> minecraft.gui.setScreen(new JoinMultiplayerScreen(self))));
		multiplayer.active = getMultiplayerDisabledReason() == null;
		y += MenuButton.HEIGHT;
		addRenderableWidget(new MenuButton(x, y, Component.translatable("menu.options"),
				() -> minecraft.gui.setScreen(new OptionsScreen(self, minecraft.options))));
		y += MenuButton.HEIGHT;
		addRenderableWidget(new MenuButton(x, y, Component.translatable("options.language"),
				() -> minecraft.gui.setScreen(new LanguageSelectScreen(self, minecraft.options, minecraft.getLanguageManager()))));
		y += MenuButton.HEIGHT;
		addRenderableWidget(new MenuButton(x, y, Component.translatable("menu.quit"), minecraft::stop));
		// Mojang's credits stay reachable, as the title screen requires.
		Component credits = Component.translatable("title.credits");
		int creditsWidth = font.width(credits);
		addRenderableWidget(new PlainTextButton(width - creditsWidth - 2, height - 10, creditsWidth, 10, credits,
				button -> minecraft.gui.setScreen(new CreditsAndAttributionScreen(self)), font));
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
	private void mcsource$halfLifeTitle(LogoRenderer logo, GuiGraphicsExtractor graphics, int screenWidth, float alpha) {
		graphics.text(font, TITLE_TEXT, mcsource$left(), Math.max(height / 2 - 64, 16), 0xFFFFFFFF, true);
		// Which build is running, to tell builds apart when reporting problems.
		graphics.text(font, Component.literal("MC:Source " + com.mcsource.client.McSourceClient.build()), 2, height - 10, 0xFF808080, false);
	}
}
