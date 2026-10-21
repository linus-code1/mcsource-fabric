package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.components.ChatComponent;

/**
 * Chat in the manner of Source games: with the chat closed, recent messages
 * float over the game on their own, with no dark strip behind each line;
 * with it open, the whole chat sits in one rounded panel (see
 * {@link ChatScreenMixin}).
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@ModifyVariable(method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
			at = @At("STORE"), ordinal = 2)
	private float mcsource$noLineBackground(float opacity) {
		return 0.0F;
	}
}
