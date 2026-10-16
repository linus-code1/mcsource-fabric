package com.mcsource.client.menu;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A main menu entry as Half-Life 2 draws them: plain capital words with no
 * button around them, dim until the pointer is over them.
 */
public class MenuButton extends AbstractButton {
	public static final FontDescription FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("mcsource", "menu"));
	public static final int HEIGHT = 16;

	private final Consumer<InputWithModifiers> action;

	public MenuButton(int x, int y, Component label, Runnable action) {
		this(x, y, label, input -> action.run());
	}

	public MenuButton(int x, int y, Component label, Consumer<InputWithModifiers> action) {
		super(x, y, Minecraft.getInstance().font.width(styled(label)) + 4, HEIGHT, label);
		this.action = action;
	}

	public static Component styled(Component label) {
		return Component.literal(label.getString().toUpperCase(java.util.Locale.ROOT)).withStyle(Style.EMPTY.withFont(FONT));
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.accept(input);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		int color;
		if (!active) {
			color = 0x707070;
		} else if (isHoveredOrFocused()) {
			color = 0xFFFFFF;
		} else {
			color = 0xC8C8C8;
		}
		graphics.text(Minecraft.getInstance().font, styled(getMessage()), getX() + 2, getY() + 3, ARGB.color(Math.round(alpha * 255.0F), color),
				true);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
