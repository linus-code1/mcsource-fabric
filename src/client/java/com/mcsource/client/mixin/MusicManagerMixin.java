package com.mcsource.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

import com.mcsource.client.sound.Ambience;

/**
 * Keeps Minecraft's music quiet while the Half-Life 2 ambience plays in its
 * place, as Half-Life 2 has no music between its scripted moments. A song
 * already playing stops; jukeboxes are not music and keep playing.
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Shadow
	private SoundInstance currentMusic;

	@Shadow
	public abstract void stopPlaying();

	@Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
	private void mcsource$ambienceInstead(CallbackInfo ci) {
		if (!Ambience.replacesMusic()) {
			return;
		}
		if (currentMusic != null) {
			stopPlaying();
		}
		ci.cancel();
	}
}
