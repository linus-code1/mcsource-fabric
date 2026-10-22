package com.mcsource.world.hl2;

import java.util.Map;
import java.util.Set;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mcsource.model.source.GameFiles;

/**
 * The solid of a Source map in blocks, one block to every 40 units (a
 * Half-Life 2 player, 72 units tall, comes out at Minecraft's 1.8 blocks).
 * The map itself is drawn over it exactly as the game draws it (see
 * {@link MapScene}); these blocks are unseen, and only say where you can
 * walk.
 *
 * <p>Each block is filled from the bottom in eighths, so floors, steps and
 * ramps sit within five units of where the map has them. Walls are grown
 * sideways a little so the thin walls Source is full of still stop you.
 * Player clips count as solid, as they do in the game. Displacements (the
 * bent terrain) fill the ground up to their surface. Water brushes become
 * water, light entities leave invisible light blocks, and the player starts
 * at the map's player start.
 */
public final class MapVoxels {
	public static final float UNITS_PER_BLOCK = 40.0F;
	/** How far walls are grown sideways, in units: a 16-unit wall grows to 41, more than a block. */
	private static final float GROW = 12.5F;
	/** The lowest block of the map sits this high in the world. */
	public static final int BASE_Y = -60;
	public static final int MAX_HEIGHT = 375;
	private static final int SOLID_CONTENTS = Bsp.CONTENTS_SOLID | Bsp.CONTENTS_WINDOW | Bsp.CONTENTS_GRATE | Bsp.CONTENTS_PLAYERCLIP;
	/** Brush entities that stay where they are and stop you; doors and the like are left open. */
	private static final Set<String> KEPT_ENTITIES = Set.of("func_brush", "func_wall", "func_detail", "func_lod", "func_wall_toggle",
			"func_breakable", "func_breakable_surf", "func_monitor", "func_reflective_glass",
			// Trains, lifts and platforms stand where they start, so you can stand on them.
			"func_tracktrain", "func_train", "func_plat", "func_platrot", "func_conveyor", "func_pushable", "func_physbox", "func_movelinear");
	private static final byte WATER = 1;
	private static final byte LIGHT = 2;

	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	/** Where the map's block grid starts, in grid cells of 40 units. */
	public final int minX;
	public final int minY;
	public final int minZ;
	/** Filled eighths of each block, a bit each from the bottom. */
	private final byte[] solid;
	private final byte[] extra;
	private final BlockState[] layered = new BlockState[9];
	/** Where the player starts, in blocks from the map's corner, and which way they face. */
	public int spawnX;
	public int spawnY;
	public int spawnZ;
	public double spawnExactX;
	public double spawnExactY;
	public double spawnExactZ;
	public float spawnYaw;

	/** The map file, kept for drawing it. */
	public final Bsp map;
	public final GameFiles files;

	public MapVoxels(Bsp map, GameFiles files) {
		this.map = map;
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
		solid = new byte[sizeX * sizeY * sizeZ];
		extra = new byte[solid.length];

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
			if ((brush.contents() & SOLID_CONTENTS) != 0) {
				fill(brush, GROW, false);
			}
		}
		for (Bsp.Displacement displacement : map.displacements) {
			displacement(displacement);
		}
		for (int i = 0; i < map.brushes.size(); i++) {
			Bsp.Brush brush = map.brushes.get(i);
			if ((brush.contents() & (Bsp.CONTENTS_WATER | Bsp.CONTENTS_SLIME)) != 0 && map.brushModel[i] <= 0) {
				fill(brush, 0.0F, true);
			}
		}
		entities(map);
	}

	private static int floor(float value) {
		return (int) Math.floor(value);
	}

	private MapCollision collision;
	private boolean collisionBuilt;

	/** The map's exact solid, built the first time it is asked for; null if it couldn't be. */
	public synchronized MapCollision collision() {
		if (!collisionBuilt) {
			collisionBuilt = true;
			collision = MapCollision.build(this);
		}
		MapCollision.setActive(collision);
		return collision;
	}

	/** The block at a place in the world, given relative to the map's corner; null for air. */
	public BlockState get(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return null;
		}
		int index = (y * sizeZ + z) * sizeX + x;
		int bits = solid[index] & 0xFF;
		if (bits != 0) {
			int layers = layers(bits);
			BlockState state = layered[layers];
			if (state == null) {
				state = Hl2Worlds.MAP_SOLID.defaultBlockState().setValue(MapSolidBlock.LAYERS, layers);
				layered[layers] = state;
			}
			return state;
		}
		return switch (extra[index]) {
			case WATER -> Blocks.WATER.defaultBlockState();
			case LIGHT -> Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15);
			default -> null;
		};
	}

	/**
	 * How many eighths high a block stands: up to its highest filled eighth.
	 * One filled only near its top (the underside of a ceiling) is filled
	 * whole, so nothing can be walked into from below.
	 */
	private static int layers(int bits) {
		return 32 - Integer.numberOfLeadingZeros(bits);
	}

	private int index(int gx, int gy, int gz) {
		int x = gx - minX;
		int y = gy - minY;
		int z = gz - minZ;
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return -1;
		}
		return (y * sizeZ + z) * sizeX + x;
	}

	/**
	 * Fills the eighths of blocks whose centres lie inside a brush, grown
	 * {@code grow} units sideways (floors and ceilings stay exactly where
	 * they are), or marks water there.
	 */
	private void fill(Bsp.Brush brush, float grow, boolean water) {
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
		if (bounds[3] - bounds[0] > 40000 || bounds[4] - bounds[1] > 40000 || bounds[5] - bounds[2] > 40000) {
			return;
		}
		Bsp.Side[] sides = brush.sides();
		float[] slack = new float[sides.length];
		for (int i = 0; i < sides.length; i++) {
			Bsp.Plane p = sides[i].plane();
			slack[i] = grow * (float) Math.sqrt(p.nx() * p.nx() + p.ny() * p.ny());
		}
		int x0 = floor((bounds[0] - grow) / UNITS_PER_BLOCK);
		int x1 = floor((bounds[3] + grow) / UNITS_PER_BLOCK);
		int z0 = floor(-(bounds[4] + grow) / UNITS_PER_BLOCK);
		int z1 = floor(-(bounds[1] - grow) / UNITS_PER_BLOCK);
		int y0 = floor(bounds[2] / UNITS_PER_BLOCK);
		int y1 = floor(bounds[5] / UNITS_PER_BLOCK);
		for (int gy = y0; gy <= y1; gy++) {
			for (int gz = z0; gz <= z1; gz++) {
				float sy = -(gz + 0.5F) * UNITS_PER_BLOCK;
				for (int gx = x0; gx <= x1; gx++) {
					int index = index(gx, gy, gz);
					if (index < 0) {
						continue;
					}
					float sx = (gx + 0.5F) * UNITS_PER_BLOCK;
					int bits = 0;
					for (int k = 0; k < 8; k++) {
						float sz = (gy + (k + 0.5F) / 8.0F) * UNITS_PER_BLOCK;
						boolean inside = true;
						for (int i = 0; i < sides.length; i++) {
							Bsp.Plane p = sides[i].plane();
							if (p.nx() * sx + p.ny() * sy + p.nz() * sz - p.dist() > slack[i]) {
								inside = false;
								break;
							}
						}
						if (inside) {
							bits |= 1 << k;
						}
					}
					if (bits == 0) {
						continue;
					}
					if (water) {
						extra[index] = WATER;
					} else {
						solid[index] |= (byte) bits;
					}
				}
			}
		}
	}

	private void displacement(Bsp.Displacement d) {
		int n = d.size();
		float[] p = d.points();
		for (int y = 0; y < n - 1; y++) {
			for (int x = 0; x < n - 1; x++) {
				int a = (y * n + x) * 3;
				int b = (y * n + x + 1) * 3;
				int c = ((y + 1) * n + x + 1) * 3;
				int e = ((y + 1) * n + x) * 3;
				triangle(p, a, b, c);
				triangle(p, a, c, e);
			}
		}
	}

	/** Fills ground up to a displacement's surface, sampled every few units across the triangle. */
	private void triangle(float[] p, int a, int b, int c) {
		float ux = p[b] - p[a];
		float uy = p[b + 1] - p[a + 1];
		float uz = p[b + 2] - p[a + 2];
		float vx = p[c] - p[a];
		float vy = p[c + 1] - p[a + 1];
		float vz = p[c + 2] - p[a + 2];
		float longest = (float) Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz), Math.sqrt(vx * vx + vy * vy + vz * vz));
		int steps = Math.max(1, (int) Math.ceil(longest / (UNITS_PER_BLOCK / 4.0F)));
		for (int i = 0; i <= steps; i++) {
			for (int j = 0; j <= steps - i; j++) {
				float s = i / (float) steps;
				float t = j / (float) steps;
				float sx = p[a] + ux * s + vx * t;
				float sy = p[a + 1] + uy * s + vy * t;
				float sz = p[a + 2] + uz * s + vz * t;
				int gx = floor(sx / UNITS_PER_BLOCK);
				int gz = floor(-sy / UNITS_PER_BLOCK);
				float height = sz / UNITS_PER_BLOCK;
				int gy = floor(height);
				int eighths = Math.round((height - gy) * 8.0F);
				int index = index(gx, gy, gz);
				if (index >= 0 && eighths > 0) {
					solid[index] |= (byte) ((1 << eighths) - 1);
				}
				// Ground under the surface, so it isn't a shell.
				for (int down = 1; down <= 3; down++) {
					int below = index(gx, gy - down, gz);
					if (below >= 0) {
						solid[below] = (byte) 0xFF;
					}
				}
			}
		}
	}

	private boolean empty(int gx, int gy, int gz) {
		int index = index(gx, gy, gz);
		return index >= 0 && solid[index] == 0 && extra[index] == 0;
	}

	/** How high the solid of a block reaches, in eighths, 0 for none. */
	private int height(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return 0;
		}
		int bits = solid[(y * sizeZ + z) * sizeX + x] & 0xFF;
		return bits == 0 ? 0 : layers(bits);
	}

	private void entities(Bsp map) {
		boolean spawnFound = false;
		float[] start = null;
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
					extra[index(gx, gy, gz)] = LIGHT;
				}
			} else if (!spawnFound && (type.equals("info_player_start") || type.equals("info_player_deathmatch"))) {
				spawnFound = type.equals("info_player_start");
				start = origin;
				float[] angles = Bsp.vector(entity.get("angles"));
				// Source's yaw 0 looks along +x and turns towards +y; Minecraft's 0 looks along +z (south).
				spawnYaw = angles == null ? 0.0F : -angles[1] - 90.0F;
			}
		}
		if (start != null) {
			spawnExactX = start[0] / UNITS_PER_BLOCK - minX;
			spawnExactY = start[2] / UNITS_PER_BLOCK - minY;
			spawnExactZ = -start[1] / UNITS_PER_BLOCK - minZ;
		} else {
			spawnExactX = sizeX / 2 + 0.5;
			spawnExactZ = sizeZ / 2 + 0.5;
			spawnExactY = sizeY - 2;
		}
		spawnX = floor((float) spawnExactX);
		spawnZ = floor((float) spawnExactZ);
		// Stand on the floor under the start, clear of anything above.
		int y = Math.max(0, Math.min(floor((float) spawnExactY), sizeY - 3));
		while (y < sizeY - 2 && (height(spawnX, y, spawnZ) == 8 || height(spawnX, y + 1, spawnZ) > 0 || height(spawnX, y + 2, spawnZ) > 0)) {
			y++;
		}
		spawnY = y;
		spawnExactY = y + height(spawnX, y, spawnZ) / 8.0;
	}
}
