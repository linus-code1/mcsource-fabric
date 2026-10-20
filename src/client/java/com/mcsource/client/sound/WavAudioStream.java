package com.mcsource.client.sound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.resources.Identifier;

import com.mcsource.model.source.SoundList;
import com.mcsource.model.source.Wav;

/**
 * A Half-Life 2 WAVE sound played by Minecraft's sound engine: decoded whole
 * (they are short) to mono 16-bit samples, then handed out in pieces.
 */
public final class WavAudioStream implements FiniteAudioStream {
	private final AudioFormat format;
	private final short[] samples;
	private int position;

	public WavAudioStream(Wav.Pcm pcm) {
		this.samples = pcm.samples();
		this.format = new AudioFormat(pcm.rate(), 16, 1, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
	}

	/** Reads a whole WAVE file from {@code input} (left open). */
	public WavAudioStream(InputStream input) throws IOException {
		this(Wav.decode(input.readAllBytes()));
	}

	/** Whether this sound is one of the mod's Half-Life 2 sounds, which are WAVE files under {@code .ogg} names. */
	public static boolean ours(Identifier id) {
		return id.getNamespace().equals("mcsource") && id.getPath().startsWith("sounds/" + SoundList.FOLDER);
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) {
		int count = Math.max(0, Math.min(size / 2, samples.length - position));
		ByteBuffer out = buffer(count);
		for (int i = 0; i < count; i++) {
			out.putShort(samples[position++]);
		}
		return out.flip();
	}

	@Override
	public ByteBuffer readAll() {
		return read((samples.length - position) * 2);
	}

	@Override
	public void close() {
	}

	/** OpenAL reads straight from memory, so the samples go in a direct buffer in the machine's byte order. */
	private static ByteBuffer buffer(int samples) {
		return ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder());
	}
}
