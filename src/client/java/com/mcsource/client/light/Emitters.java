package com.mcsource.client.light;

import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Where the light-giving blocks are (torches, lava, glowstone and so on),
 * kept per chunk section as chunks load and blocks change, so the light at a
 * point can be summed straight from the lamps around it instead of from
 * Minecraft's flood of light levels.
 */
public final class Emitters {
	/**
	 * Section position to its lamps, each packed as x | y << 4 | z << 8 |
	 * emission << 12 (positions inside the section). Arrays are never changed
	 * once stored, so chunk-building threads can read them freely.
	 */
	private static final ConcurrentHashMap<Long, int[]> SECTIONS = new ConcurrentHashMap<>();

	private Emitters() {
	}

	public static void register() {
		ClientChunkEvents.CHUNK_LOAD.register(Emitters::load);
		ClientChunkEvents.CHUNK_UNLOAD.register(Emitters::unload);
	}

	public static void clear() {
		SECTIONS.clear();
	}

	private static void load(ClientLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			long key = SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(i), chunk.getPos().z());
			store(key, sections[i]);
		}
	}

	private static void unload(ClientLevel level, LevelChunk chunk) {
		for (int i = 0; i < chunk.getSections().length; i++) {
			SECTIONS.remove(SectionPos.asLong(chunk.getPos().x(), chunk.getSectionYFromSectionIndex(i), chunk.getPos().z()));
		}
	}

	private static void store(long key, LevelChunkSection section) {
		if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) {
			SECTIONS.remove(key);
			return;
		}
		int[] found = new int[16];
		int count = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int emission = section.getBlockState(x, y, z).getLightEmission();
					if (emission > 0) {
						if (count == found.length) {
							found = java.util.Arrays.copyOf(found, count * 2);
						}
						found[count++] = x | y << 4 | z << 8 | emission << 12;
					}
				}
			}
		}
		if (count == 0) {
			SECTIONS.remove(key);
		} else {
			SECTIONS.put(key, java.util.Arrays.copyOf(found, count));
		}
	}

	/**
	 * A block changed on the client. When it starts or stops giving light, its
	 * section's lamps are found again and everything the lamp reached is drawn
	 * again, since the new light reaches round in a ball, further than the
	 * diamond of blocks Minecraft relights.
	 */
	public static void changed(ClientLevel level, BlockPos pos, BlockState before, BlockState after) {
		if (before.getLightEmission() == after.getLightEmission()) {
			return;
		}
		LevelChunk chunk = level.getChunkAt(pos);
		int index = chunk.getSectionIndex(pos.getY());
		if (index < 0 || index >= chunk.getSections().length) {
			return;
		}
		store(SectionPos.asLong(pos), chunk.getSection(index));
		int reach = Math.max(before.getLightEmission(), after.getLightEmission()) + 1;
		level.setSectionRangeDirty(SectionPos.blockToSectionCoord(pos.getX() - reach), SectionPos.blockToSectionCoord(pos.getY() - reach),
				SectionPos.blockToSectionCoord(pos.getZ() - reach), SectionPos.blockToSectionCoord(pos.getX() + reach),
				SectionPos.blockToSectionCoord(pos.getY() + reach), SectionPos.blockToSectionCoord(pos.getZ() + reach));
		SourceLight.forget();
	}

	/** Receives one lamp: the middle of its block, and how much light it gives (1 to 15). */
	public interface Visitor {
		void lamp(int x, int y, int z, int emission);
	}

	/** Every lamp within {@code reach} blocks (as a cube) of a block. */
	public static void around(int x, int y, int z, int reach, Visitor visitor) {
		int sx0 = SectionPos.blockToSectionCoord(x - reach);
		int sy0 = SectionPos.blockToSectionCoord(y - reach);
		int sz0 = SectionPos.blockToSectionCoord(z - reach);
		int sx1 = SectionPos.blockToSectionCoord(x + reach);
		int sy1 = SectionPos.blockToSectionCoord(y + reach);
		int sz1 = SectionPos.blockToSectionCoord(z + reach);
		for (int sy = sy0; sy <= sy1; sy++) {
			for (int sz = sz0; sz <= sz1; sz++) {
				for (int sx = sx0; sx <= sx1; sx++) {
					int[] lamps = SECTIONS.get(SectionPos.asLong(sx, sy, sz));
					if (lamps == null) {
						continue;
					}
					for (int lamp : lamps) {
						int lx = (sx << 4) + (lamp & 15);
						int ly = (sy << 4) + (lamp >> 4 & 15);
						int lz = (sz << 4) + (lamp >> 8 & 15);
						if (Math.abs(lx - x) <= reach && Math.abs(ly - y) <= reach && Math.abs(lz - z) <= reach) {
							visitor.lamp(lx, ly, lz, lamp >> 12 & 15);
						}
					}
				}
			}
		}
	}
}
