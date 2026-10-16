package com.mcsource.client.mob;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * Which mobs are drawn with a Source-style rigged model instead of
 * Minecraft's, and which of the model's animations stand in for each of the
 * mob's actions. Adding a mob here is all it takes to replace it; mobs whose
 * model file is not found keep their normal look.
 */
public final class SourceRigs {
	/**
	 * A mob's model. Sequence names are matched by prefix, first match wins.
	 * {@code scale} turns model units into blocks.
	 */
	public record Rig(String file, float scale, String[] idle, String[] walk, String[] attack, String[] death) {
	}

	/** Source maps use about 40 units per block, the same scale as the movement code. */
	private static final float UNITS = 1.0F / 40.0F;
	private static final Map<EntityType<?>, Rig> RIGS = new HashMap<>();

	static {
		RIGS.put(EntityTypes.ZOMBIE, new Rig("zombie.mdl", UNITS,
				new String[] {"idle1", "idle"},
				new String[] {"walk"},
				new String[] {"attack1", "attack"},
				new String[] {"dieforward", "diesimple", "dieheadshot", "die"}));
	}

	private SourceRigs() {
	}

	public static Rig get(EntityType<?> type) {
		return type == null ? null : RIGS.get(type);
	}
}
