package com.mcsource.client.mob;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import com.mcsource.kart.Karts;

/**
 * Which mobs are drawn with a rigged model instead of Minecraft's, and which
 * of the model's animations stand in for each of the mob's actions. Adding a
 * mob here is all it takes to replace it.
 *
 * <p>Each mob lists models to try in order (see {@link SourceModel} for how
 * they are named): the real one from the player's Half-Life 2 or Half-Life
 * install first, then the mod's own stand-in.
 */
public final class SourceRigs {
	/**
	 * A mob's model. Animation names are matched against sequence names (by
	 * prefix) and activity names, first match wins. {@code scale} turns model
	 * units into blocks; with {@code fitHeight} the model is instead scaled so
	 * that standing it is as tall as the mob.
	 */
	public record Rig(String[] models, float scale, boolean fitHeight, String[] idle, String[] walk, String[] attack, String[] death) {
		public SourceModel model() {
			return SourceModel.first(models);
		}
	}

	/** Source and Half-Life use about 40 units per block, the same scale as the movement code. */
	private static final float UNITS = 1.0F / 40.0F;
	private static final Map<EntityType<?>, Rig> RIGS = new HashMap<>();

	static {
		RIGS.put(EntityTypes.ZOMBIE, new Rig(
				new String[] {"source:models/zombie/classic.mdl", "halflife:models/zombie.mdl", "builtin:zombie"},
				UNITS, true,
				new String[] {"idle01", "idle1", "idle", "act_idle"},
				new String[] {"walk", "act_walk"},
				new String[] {"attacka", "attack1", "attack", "act_melee_attack1"},
				new String[] {"dieforward", "diesimple", "dieheadshot", "die", "act_die"}));
		// The kart is built small; this makes it big enough to sit in.
		RIGS.put(Karts.KART, new Rig(new String[] {"builtin:kart"}, UNITS * 1.7F, false,
				new String[] {"idle"},
				new String[] {"drive"},
				new String[0],
				new String[0]));
	}

	private SourceRigs() {
	}

	public static Rig get(EntityType<?> type) {
		return type == null ? null : RIGS.get(type);
	}
}
