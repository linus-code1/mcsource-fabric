package com.mcsource.client.mixin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.audio.SoundBuffer;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Util;

import com.mcsource.client.sound.WavAudioStream;
import com.mcsource.model.source.Wav;

/**
 * Plays the mod's Half-Life 2 sounds, which are WAVE files that Minecraft's
 * Ogg Vorbis reader can't open. Only sounds under {@code mcsource:sounds/hl2/}
 * are read here; every other sound loads as before.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Shadow
	@Final
	private ResourceProvider resourceManager;

	@Shadow
	@Final
	private Map<Identifier, CompletableFuture<SoundBuffer>> cache;

	@Inject(method = "getCompleteBuffer(Lnet/minecraft/resources/Identifier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"),
			cancellable = true)
	private void mcsource$wavBuffer(Identifier id, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (!WavAudioStream.ours(id)) {
			return;
		}
		cir.setReturnValue(cache.computeIfAbsent(id, key -> CompletableFuture.supplyAsync(() -> {
			try (FiniteAudioStream stream = open(key)) {
				return new SoundBuffer(stream.readAll(), stream.getFormat());
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool())));
	}

	@Inject(method = "getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"),
			cancellable = true)
	private void mcsource$wavStream(Identifier id, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (!WavAudioStream.ours(id)) {
			return;
		}
		cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
			try {
				if (looping) {
					return new LoopingAudioStream(input -> decode(input), resourceManager.open(id));
				}
				return open(id);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool()));
	}

	private FiniteAudioStream open(Identifier id) throws IOException {
		try (InputStream input = resourceManager.open(id)) {
			return decode(input);
		}
	}

	/** A WAVE file, or (if someone put a real Ogg file here) Minecraft's own reader. */
	private static FiniteAudioStream decode(InputStream input) throws IOException {
		byte[] data = input.readAllBytes();
		if (Wav.isWav(data)) {
			return new WavAudioStream(Wav.decode(data));
		}
		return new JOrbisAudioStream(new ByteArrayInputStream(data));
	}
}
