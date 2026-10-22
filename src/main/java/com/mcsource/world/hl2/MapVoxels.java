package com.mcsource.world.hl2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.model.source.GameFiles;

/**
 * A Source map rebuilt in blocks, one block to every 40 units (a Half-Life 2
 * player, 72 units tall, comes out at Minecraft's 1.8 blocks).
 *
 * Each solid brush of the world fills the blocks whose centres lie inside
 * it, grown a little first so the thin walls Source is full of still make
 * at least one block. Each brush becomes the block its main material maps
 * to. Displacements (Source's bent terrain) are laid block by block along
 * their surfaces, with ground filled in under them. Water brushes become
 * water, light entities leave invisible light blocks so rooms aren't pitch
 * dark, and the player starts at the map's player start.
 */
public final class MapVoxels {
	public static final float UNITS_PER_BLOCK = 40.0F;
	/** How far brushes are grown, in units: a 16-unit wall grows to 42, more than a block. */
	private static final float GROW = 13.0F;
	/** The lowest block of the map sits this high in the world. */
	public static final int BASE_Y = -60;
	public static final int MAX_HEIGHT = 375;
	/** Brush entities kept as part of the map; doors and the like are left open. */
	private static final Set<String> KEPT_ENTITIES = Set.of("func_brush", "func_wall", "func_detail", "func_lod", "func_wall_toggle",
			"func_illusionary", "func_breakable_surf");

	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final byte[] cells;
	private final List<BlockState> palette = new ArrayList<>();
	private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
	/** Where the player starts, in world blocks, and which way they face. */
	public int spawnX;
	public int spawnY;
	public int spawnZ;
	public float spawnYaw;

	private final GameFiles files;

	/** Builds the blocks of a map; {@code files} (may be null) lets unknown materials be matched by colour. */
	public MapVoxels(Bsp map, GameFiles files) {
		this.files = files;
		float[] b = map.worldBounds;
		minX = floor(b[0] / UNITS_PER_BLOCK) - 1;
		int maxX = floor(b[3] / UNITS_PER_BLOCK) + 1;
		// Source's y runs the other way to Minecraft's z.
		minZ = floor(-b[4] / UNITS_PER_BLOCK) - 1;
		int maxZ = floor(-b[1] / UNITS_PER_BLOCK) + 1;
		minY = floor(b[2] / UNITS_PER_BLOCK) - 1;
		int maxY = floor(b[5] / UNITS_PER_BLOCK) + 1;
		sizeX = Math.max(1, maxX - minX + 1);
		sizeZ = Math.max(1, maxZ - minZ + 1);
		sizeY = Math.max(1, Math.min(maxY - minY + 1, MAX_HEIGHT));
		cells = new byte[sizeX * sizeY * sizeZ];
		palette.add(Blocks.AIR.defaultBlockState());
		paletteIndex.put(Blocks.AIR.defaultBlockState(), 0);

		Set<Integer> keptModels = new java.util.HashSet<>();
		keptModels.add(0);
		for (Map<String, String> entity : map.entities) {
			String model = entity.get("model");
			if (model != null && model.startsWith("*") && KEPT_ENTITIES.contains(entity.getOrDefault("classname", ""))) {
				try {
					keptModels.add(Integer.parseInt(model.substring(1)));
				} catch (NumberFormatException e) {
					// Not a brush model.
				}
			}
		}
		for (int i = 0; i < map.brushes.size(); i++) {
			Bsp.Brush brush = map.brushes.get(i);
			if (!keptModels.contains(map.brushModel[i]) && map.brushModel[i] != -1) {
				continue;
			}
			int contents = brush.contents();
			if ((contents & (Bsp.CONTENTS_SOLID | Bsp.CONTENTS_WINDOW | Bsp.CONTENTS_GRATE)) != 0) {
				solid(brush);
			}
		}
		for (Bsp.Displacement displacement : map.displacements) {
			displacement(displacement);
		}
		for (int i = 0; i < map.brushes.size(); i++) {
			Bsp.Brush brush = map.brushes.get(i);
			if ((brush.contents() & (Bsp.CONTENTS_WATER | Bsp.CONTENTS_SLIME)) != 0 && map.brushModel[i] <= 0) {
				fill(brush, 0.0F, Blocks.WATER.defaultBlockState(), false);
			}
		}
		entities(map);
	}

	private static int floor(float value) {
		return (int) Math.floor(value);
	}

	/** The block at a place in the world, given relative to the map's corner. */
	public BlockState get(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return null;
		}
		int index = cells[(y * sizeZ + z) * sizeX + x] & 0xFF;
		return index == 0 ? null : palette.get(index);
	}

	private int code(BlockState state) {
		Integer known = paletteIndex.get(state);
		if (known != null) {
			return known;
		}
		if (palette.size() >= 255) {
			return paletteIndex.get(palette.get(1));
		}
		palette.add(state);
		paletteIndex.put(state, palette.size() - 1);
		return palette.size() - 1;
	}

	private void set(int gx, int gy, int gz, BlockState state, boolean replace) {
		int x = gx - minX;
		int y = gy - minY;
		int z = gz - minZ;
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return;
		}
		int index = (y * sizeZ + z) * sizeX + x;
		if (!replace && cells[index] != 0) {
			return;
		}
		cells[index] = (byte) code(state);
	}

	private boolean empty(int gx, int gy, int gz) {
		int x = gx - minX;
		int y = gy - minY;
		int z = gz - minZ;
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return false;
		}
		return cells[(y * sizeZ + z) * sizeX + x] == 0;
	}

	private void solid(Bsp.Brush brush) {
		Map<String, Integer> votes = new HashMap<>();
		for (Bsp.Side side : brush.sides()) {
			String material = side.material();
			if (material == null) {
				continue;
			}
			// Sky, invisible walls and clips are left out of the blocks.
			if (material.contains("toolsskybox") || material.contains("toolsinvisible") || material.contains("clip")
					|| material.contains("toolstrigger") || material.contains("toolsblocklight") || material.contains("toolshint")
					|| material.contains("toolsskip") || material.contains("toolsareaportal") || material.contains("toolsfog")) {
				return;
			}
			if (MaterialBlocks.tool(material) && !material.equals("tools/toolsblack")) {
				continue;
			}
			// The top counts double: floors decide what a brush is made of.
			votes.merge(material, side.plane().nz() > 0.7F ? 3 : 1, Integer::sum);
		}
		String material = null;
		int best = 0;
		for (Map.Entry<String, Integer> vote : votes.entrySet()) {
			if (vote.getValue() > best) {
				best = vote.getValue();
				material = vote.getKey();
			}
		}
		BlockState state = MaterialBlocks.of(material, files);
		if ((brush.contents() & Bsp.CONTENTS_WINDOW) != 0 && (brush.contents() & Bsp.CONTENTS_SOLID) == 0) {
			state = Blocks.GLASS.defaultBlockState();
		} else if ((brush.contents() & Bsp.CONTENTS_GRATE) != 0 && (brush.contents() & Bsp.CONTENTS_SOLID) == 0) {
			state = Blocks.IRON_BARS.defaultBlockState();
		}
		boolean seeThrough = state.is(Blocks.GLASS) || state.is(Blocks.IRON_BARS);
		fill(brush, GROW, state, !seeThrough);
	}

	/** Fills the blocks whose centres lie inside a brush grown by {@code grow} units. */
	private void fill(Bsp.Brush brush, float grow, BlockState state, boolean replaceSeeThrough) {
		float[] bounds = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		for (Bsp.Side side : brush.sides()) {
			Bsp.Plane p = side.plane();
			if (p.nx() > 0.999F) {
				bounds[3] = Math.min(bounds[3], p.dist());
			} else if (p.nx() < -0.999F) {
				bounds[0] = Math.max(bounds[0], -p.dist());
			} else if (p.ny() > 0.999F) {
				bounds[4] = Math.min(bounds[4], p.dist());
			} else if (p.ny() < -0.999F) {
				bounds[1] = Math.max(bounds[1], -p.dist());
			} else if (p.nz() > 0.999F) {
				bounds[5] = Math.min(bounds[5], p.dist());
			} else if (p.nz() < -0.999F) {
				bounds[2] = Math.max(bounds[2], -p.dist());
			}
		}
		for (float bound : bounds) {
			if (Math.abs(bound) == Float.MAX_VALUE) {
				// Every compiled brush has its six axial sides; this one is broken.
				return;
			}
		}
		if (bounds[3] - bounds[0] > 40000 || bounds[4] - bounds[1] > 40000) {
			return;
		}
		int x0 = floor((bounds[0] - grow) / UNITS_PER_BLOCK);
		int x1 = floor((bounds[3] + grow) / UNITS_PER_BLOCK);
		int z0 = floor(-(bounds[4] + grow) / UNITS_PER_BLOCK);
		int z1 = floor(-(bounds[1] - grow) / UNITS_PER_BLOCK);
		int y0 = floor((bounds[2] - grow) / UNITS_PER_BLOCK);
		int y1 = floor((bounds[5] + grow) / UNITS_PER_BLOCK);
		Bsp.Side[] sides = brush.sides();
		for (int gy = y0; gy <= y1; gy++) {
			float sz = (gy + 0.5F) * UNITS_PER_BLOCK;
			for (int gz = z0; gz <= z1; gz++) {
				float sy = -(gz + 0.5F) * UNITS_PER_BLOCK;
				for (int gx = x0; gx <= x1; gx++) {
					float sx = (gx + 0.5F) * UNITS_PER_BLOCK;
					boolean inside = true;
					for (Bsp.Side side : sides) {
						Bsp.Plane p = side.plane();
						if (p.nx() * sx + p.ny() * sy + p.nz() * sz - p.dist() > grow) {
							inside = false;
							break;
						}
					}
					if (inside) {
						boolean replace = replaceSeeThrough && !empty(gx, gy, gz) && seeThroughAt(gx, gy, gz);
						set(gx, gy, gz, state, replace);
					}
				}
			}
		}
	}

	private boolean seeThroughAt(int gx, int gy, int gz) {
		BlockState here = get(gx - minX, gy - minY, gz - minZ);
		return here != null && (here.is(Blocks.GLASS) || here.is(Blocks.IRON_BARS));
	}

	private void displacement(Bsp.Displacement d) {
		BlockState state = MaterialBlocks.of(d.material(), files);
		int n = d.size();
		float[] p = d.points();
		for (int y = 0; y < n - 1; y++) {
			for (int x = 0; x < n - 1; x++) {
				int a = (y * n + x) * 3;
				int b = (y * n + x + 1) * 3;
				int c = ((y + 1) * n + x + 1) * 3;
				int e = ((y + 1) * n + x) * 3;
				triangle(p, a, b, c, state);
				triangle(p, a, c, e, state);
			}
		}
	}

	private void triangle(float[] p, int a, int b, int c, BlockState state) {
		float ux = p[b] - p[a];
		float uy = p[b + 1] - p[a + 1];
		float uz = p[b + 2] - p[a + 2];
		float vx = p[c] - p[a];
		float vy = p[c + 1] - p[a + 1];
		float vz = p[c + 2] - p[a + 2];
		float nz = ux * vy - uy * vx;
		float nx = uy * vz - uz * vy;
		float ny = uz * vx - ux * vz;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length < 1.0E-4F) {
			return;
		}
		boolean ground = Math.abs(nz / length) > 0.55F;
		float longest = (float) Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz), Math.sqrt(vx * vx + vy * vy + vz * vz));
		int steps = Math.max(1, (int) Math.ceil(longest / (UNITS_PER_BLOCK / 3.0F)));
		for (int i = 0; i <= steps; i++) {
			for (int j = 0; j <= steps - i; j++) {
				float s = i / (float) steps;
				float t = j / (float) steps;
				float sx = p[a] + ux * s + vx * t;
				float sy = p[a + 1] + uy * s + vy * t;
				float sz = p[a + 2] + uz * s + vz * t;
				int gx = floor(sx / UNITS_PER_BLOCK);
				int gy = floor(sz / UNITS_PER_BLOCK);
				int gz = floor(-sy / UNITS_PER_BLOCK);
				set(gx, gy, gz, state, false);
				if (ground) {
					// Ground under the surface, so it isn't a shell one block thick.
					for (int down = 1; down <= 3; down++) {
						set(gx, gy - down, gz, state, false);
					}
				}
			}
		}
	}

	private void entities(Bsp map) {
		boolean spawnFound = false;
		for (Map<String, String> entity : map.entities) {
			String type = entity.getOrDefault("classname", "");
			float[] origin = Bsp.vector(entity.get("origin"));
			if (origin == null) {
				continue;
			}
			int gx = floor(origin[0] / UNITS_PER_BLOCK);
			int gy = floor(origin[2] / UNITS_PER_BLOCK);
			int gz = floor(-origin[1] / UNITS_PER_BLOCK);
			if (type.equals("light") || type.equals("light_spot")) {
				if (empty(gx, gy, gz)) {
					set(gx, gy, gz, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), false);
				}
			} else if (!spawnFound && (type.equals("info_player_start") || type.equals("info_player_deathmatch"))) {
				spawnFound = type.equals("info_player_start");
				spawnX = gx - minX;
				spawnY = gy - minY;
				spawnZ = gz - minZ;
				float[] angles = Bsp.vector(entity.get("angles"));
				// Source's yaw 0 looks along +x and turns towards +y; Minecraft's 0 looks along +z (south).
				spawnYaw = angles == null ? 0.0F : -angles[1] - 90.0F;
			}
		}
		if (!spawnFound && spawnX == 0 && spawnY == 0 && spawnZ == 0) {
			spawnX = sizeX / 2;
			spawnZ = sizeZ / 2;
			spawnY = sizeY - 1;
		}
		// Stand on the floor, clear of any wall the brushes grew into the start.
		int y = Math.max(0, Math.min(spawnY, sizeY - 3));
		while (y < sizeY - 2 && (blocking(spawnX, y, spawnZ) || blocking(spawnX, y + 1, spawnZ))) {
			y++;
		}
		spawnY = y;
	}

	private boolean blocking(int x, int y, int z) {
		BlockState state = get(x, y, z);
		return state != null && !state.is(Blocks.LIGHT) && !state.is(Blocks.WATER);
	}
}
