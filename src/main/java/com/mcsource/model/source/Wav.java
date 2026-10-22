package com.mcsource.model.source;

import java.io.IOException;

/**
 * Reads RIFF WAVE sound files (the format Half-Life 2's sounds come in) into
 * mono 16-bit samples, written from the public RIFF WAVE and Microsoft
 * ADPCM format descriptions. Handles uncompressed PCM (8, 16, 24 and 32
 * bits) and Microsoft ADPCM; anything else (such as MP3 inside a WAVE file)
 * is refused with an exception so a caller can try another file. Stereo
 * and other multi-channel sounds are mixed down to mono, since Minecraft
 * places sounds in the world only when they are mono.
 */
public final class Wav {
	private static final int TAG_PCM = 1;
	private static final int TAG_MS_ADPCM = 2;
	private static final int TAG_EXTENSIBLE = 0xFFFE;

	/** How Microsoft ADPCM scales its step after each sample, by the 4-bit code just read. */
	private static final int[] ADAPTATION = {230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230};
	/** The predictor pairs every Microsoft ADPCM file uses unless it lists its own. */
	private static final int[][] STANDARD_COEFFICIENTS = {{256, 0}, {512, -256}, {0, 0}, {192, 64}, {240, 0}, {460, -208}, {392, -232}};

	/** Decoded sound: mono signed 16-bit samples at {@code rate} samples per second. */
	public record Pcm(short[] samples, int rate) {
	}

	private Wav() {
	}

	/** Whether these bytes start like a RIFF WAVE file. */
	public static boolean isWav(byte[] data) {
		return data != null && data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
	}

	/** Decodes a whole WAVE file; throws if it is not one, or is in a format this can't read. */
	public static Pcm decode(byte[] data) throws IOException {
		if (!isWav(data)) {
			throw new IOException("Not a RIFF WAVE file");
		}
		int pos = 12;
		int tag = -1;
		int channels = 0;
		int rate = 0;
		int blockAlign = 0;
		int bits = 0;
		int[][] coefficients = STANDARD_COEFFICIENTS;
		int dataStart = -1;
		int dataLength = 0;
		long factSamples = -1;
		while (pos + 8 <= data.length) {
			String id = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
			long size = u32(data, pos + 4);
			int body = pos + 8;
			int available = (int) Math.min(size, data.length - body);
			switch (id) {
				case "fmt " -> {
					if (available < 16) {
						throw new IOException("Short fmt chunk");
					}
					tag = u16(data, body);
					channels = u16(data, body + 2);
					rate = (int) u32(data, body + 4);
					blockAlign = u16(data, body + 12);
					bits = u16(data, body + 14);
					int extra = available >= 18 ? u16(data, body + 16) : 0;
					if (tag == TAG_EXTENSIBLE && extra >= 22 && available >= 40) {
						// The real format tag is the first two bytes of the sub-format GUID.
						tag = u16(data, body + 24);
					}
					if (tag == TAG_MS_ADPCM && extra >= 4 && available >= 22) {
						int count = u16(data, body + 20);
						if (count > 0 && available >= 22 + count * 4) {
							coefficients = new int[count][2];
							for (int i = 0; i < count; i++) {
								coefficients[i][0] = s16(data, body + 22 + i * 4);
								coefficients[i][1] = s16(data, body + 24 + i * 4);
							}
						}
					}
				}
				case "fact" -> {
					if (available >= 4) {
						factSamples = u32(data, body);
					}
				}
				case "data" -> {
					dataStart = body;
					dataLength = available;
				}
				default -> {
					// Cue points, loop markers, text and other chunks don't change the sound.
				}
			}
			if (dataStart >= 0 && tag >= 0) {
				break;
			}
			// Chunks are padded to an even length.
			long next = body + size + (size & 1);
			if (next > data.length) {
				break;
			}
			pos = (int) next;
		}
		if (tag < 0 || dataStart < 0) {
			throw new IOException("No fmt or data chunk");
		}
		if (channels < 1 || channels > 8 || rate < 1000 || rate > 192000) {
			throw new IOException("Unusual sound: " + channels + " channels at " + rate + " Hz");
		}
		short[] samples = switch (tag) {
			case TAG_PCM -> pcm(data, dataStart, dataLength, channels, bits);
			case TAG_MS_ADPCM -> msAdpcm(data, dataStart, dataLength, channels, blockAlign, coefficients);
			default -> throw new IOException("Unsupported WAVE format " + tag);
		};
		if (factSamples >= 0 && factSamples < samples.length && tag != TAG_PCM) {
			samples = java.util.Arrays.copyOf(samples, (int) factSamples);
		}
		if (samples.length == 0) {
			throw new IOException("Empty sound");
		}
		return new Pcm(samples, rate);
	}

	private static short[] pcm(byte[] data, int start, int length, int channels, int bits) throws IOException {
		if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
			throw new IOException("Unsupported PCM sample size " + bits);
		}
		int bytes = bits / 8;
		int frames = length / (bytes * channels);
		short[] out = new short[frames];
		int pos = start;
		for (int frame = 0; frame < frames; frame++) {
			int sum = 0;
			for (int channel = 0; channel < channels; channel++) {
				int sample = switch (bytes) {
					// 8-bit WAVE samples are unsigned, centred on 128.
					case 1 -> ((data[pos] & 0xFF) - 128) << 8;
					case 2 -> s16(data, pos);
					case 3 -> s16(data, pos + 1);
					default -> s16(data, pos + 2);
				};
				sum += sample;
				pos += bytes;
			}
			out[frame] = (short) (sum / channels);
		}
		return out;
	}

	/**
	 * Microsoft ADPCM: the data is split into blocks of {@code blockAlign}
	 * bytes. Each block starts, per channel, with a predictor number, a step
	 * size and the last two samples; then come 4-bit codes, high nibble
	 * first, alternating between channels.
	 */
	private static short[] msAdpcm(byte[] data, int start, int length, int channels, int blockAlign, int[][] coefficients)
			throws IOException {
		int header = 7 * channels;
		if (blockAlign <= header) {
			throw new IOException("Bad ADPCM block size " + blockAlign);
		}
		int perBlock = 2 + (blockAlign - header) * 2 / channels;
		int blocks = (length + blockAlign - 1) / blockAlign;
		short[] out = new short[blocks * perBlock];
		int written = 0;
		int[] coef1 = new int[channels];
		int[] coef2 = new int[channels];
		int[] delta = new int[channels];
		int[] sample1 = new int[channels];
		int[] sample2 = new int[channels];
		int[] frame = new int[channels];
		for (int block = 0; block < blocks; block++) {
			int pos = start + block * blockAlign;
			int end = Math.min(pos + blockAlign, start + length);
			if (end - pos < header) {
				break;
			}
			for (int c = 0; c < channels; c++) {
				int predictor = data[pos + c] & 0xFF;
				if (predictor >= coefficients.length) {
					throw new IOException("Bad ADPCM predictor " + predictor);
				}
				coef1[c] = coefficients[predictor][0];
				coef2[c] = coefficients[predictor][1];
			}
			pos += channels;
			for (int c = 0; c < channels; c++) {
				delta[c] = s16(data, pos + c * 2);
			}
			pos += 2 * channels;
			for (int c = 0; c < channels; c++) {
				sample1[c] = s16(data, pos + c * 2);
			}
			pos += 2 * channels;
			for (int c = 0; c < channels; c++) {
				sample2[c] = s16(data, pos + c * 2);
			}
			pos += 2 * channels;
			// The two header samples come out oldest first.
			out[written++] = mix(sample2, channels);
			out[written++] = mix(sample1, channels);
			int channel = 0;
			for (; pos < end; pos++) {
				int b = data[pos] & 0xFF;
				for (int nibble : new int[] {b >> 4, b & 0x0F}) {
					int c = channel;
					int signed = nibble >= 8 ? nibble - 16 : nibble;
					int predicted = (sample1[c] * coef1[c] + sample2[c] * coef2[c]) >> 8;
					int value = Math.max(-32768, Math.min(32767, predicted + signed * delta[c]));
					sample2[c] = sample1[c];
					sample1[c] = value;
					delta[c] = Math.max(16, (ADAPTATION[nibble] * delta[c]) >> 8);
					frame[c] = value;
					channel++;
					if (channel == channels) {
						channel = 0;
						out[written++] = mix(frame, channels);
					}
				}
			}
		}
		return written == out.length ? out : java.util.Arrays.copyOf(out, written);
	}

	private static short mix(int[] values, int channels) {
		int sum = 0;
		for (int c = 0; c < channels; c++) {
			sum += values[c];
		}
		return (short) (sum / channels);
	}

	private static int u16(byte[] data, int pos) {
		return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8;
	}

	private static int s16(byte[] data, int pos) {
		return (short) u16(data, pos);
	}

	private static long u32(byte[] data, int pos) {
		return (u16(data, pos) | (long) u16(data, pos + 2) << 16) & 0xFFFFFFFFL;
	}
}
