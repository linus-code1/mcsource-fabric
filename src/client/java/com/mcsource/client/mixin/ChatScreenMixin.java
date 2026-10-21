package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.mcsource.client.SourceHud;

/**
 * The open chat drawn like a Source game's: one translucent dark panel with
 * rounded corners round the message history and the line being typed, as
 * wide as the chat rather than the whole screen, with the typing line set
 * into it behind a "Say :" label.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
	private static final int PANEL = 0x8C141414;
	private static final int ENTRY = 0x70000000;
	private static final int ENTRY_EDGE = 0x30FFFFFF;
	private static final int LABEL = 0xFFE6E6E6;
	private static final Component SAY = Component.literal("Say :");

	@Shadow
	protected EditBox input;

	protected ChatScreenMixin() {
		super(Component.empty());
	}

	/** Right edge of the chat panel, in GUI pixels. */
	private int mcsource$right() {
		Minecraft minecraft = Minecraft.getInstance();
		double scale = minecraft.options.chatScale().get();
		int chat = Mth.ceil(ChatComponent.getWidth(minecraft.options.chatWidth().get()) * scale);
		return Math.min(width - 2, chat + 14);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void mcsource$placeInput(CallbackInfo ci) {
		int x = 8 + font.width(SAY) + 4;
		input.setX(x);
		input.setWidth(Math.max(20, mcsource$right() - 6 - x));
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 0))
	private void mcsource$panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		Minecraft minecraft = Minecraft.getInstance();
		double scale = minecraft.options.chatScale().get();
		int entry = (int) (9.0 * (minecraft.options.chatLineSpacing().get() + 1.0));
		// At least ten lines tall, like Source's chat box, growing with the
		// messages up to as many lines as the chat shows.
		ChatComponent chat = minecraft.gui.hud.getChat();
		int lines = Math.min(chat.getLinesPerPage(), Math.max(10, ((ChatComponentAccessor) chat).mcsource$lines().size()));
		int history = Mth.ceil(lines * entry * scale);
		int right = mcsource$right();
		int top = Math.max(2, height - 40 - history - 6);
		SourceHud.panel(graphics, 2, top, right - 2, height - 1 - top, PANEL, 4);
		// The typing line, set into the panel.
		int entryLeft = 5 + font.width(SAY) + 6;
		SourceHud.panel(graphics, entryLeft - 1, height - 15, right - 4 - entryLeft + 2, 13, ENTRY_EDGE, 2);
		SourceHud.panel(graphics, entryLeft, height - 14, right - 4 - entryLeft, 11, ENTRY, 2);
		graphics.text(font, SAY, 8, height - 12, LABEL, true);
	}
}
