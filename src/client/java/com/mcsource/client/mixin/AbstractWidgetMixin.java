package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;

import com.mcsource.client.sound.Ambience;

/**
 * Plays Half-Life 2's rollover tick when the pointer moves onto a button or
 * slider, as its menus do. A widget already under the pointer the first time
 * it is drawn (a screen just opened under it) stays quiet.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {
	@Shadow
	protected boolean isHovered;

	@Shadow
	public boolean active;

	@Unique
	private boolean mcsource$drawn;

	@Unique
	private boolean mcsource$wasHovered;

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
	private void mcsource$rollover(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		boolean hovered = isHovered && active;
		Object self = this;
		if (hovered && !mcsource$wasHovered && mcsource$drawn && (self instanceof AbstractButton || self instanceof AbstractSliderButton)) {
			Ambience.rollover();
		}
		mcsource$wasHovered = hovered;
		mcsource$drawn = true;
	}
}
