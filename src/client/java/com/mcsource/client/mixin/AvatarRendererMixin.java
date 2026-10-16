package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;

/**
 * The first-person arms wear a hazard suit sleeve and black glove, in the
 * spirit of Half-Life's HEV suit, instead of the player's skin. The texture is
 * this mod's own pixel art.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	private static final Identifier MCSOURCE_ARM = Identifier.fromNamespaceAndPath("mcsource", "textures/entity/hev_arm.png");

	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true)
	private Identifier mcsource$suitArm(Identifier skin) {
		return MCSOURCE_ARM;
	}

	@ModifyVariable(method = "renderHand", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean mcsource$noSleeve(boolean sleeve) {
		return false;
	}
}
