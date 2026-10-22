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
 * they are named): the closest Half-Life 2 (or Episode One and Two) model
 * from the player's own install first, then other fits, then the mod's own
 * stand-in where it has one. A mob none of whose models can be found is
 * drawn as in Minecraft.
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

	// Source characters share activity names; these cover most of them.
	private static final String[] IDLE = {"act_idle", "idle01", "idle1", "idle", "act_hover", "act_fly", "fly", "hover"};
	private static final String[] WALK = {"act_walk", "walk", "act_run", "run", "act_fly", "fly", "act_hover", "swim"};
	private static final String[] ATTACK = {"act_melee_attack1", "attacka", "attack1", "attack", "act_range_attack1", "melee",
		"act_gesture_range_attack1"};
	private static final String[] DEATH = {"act_diesimple", "act_dieforward", "dieforward", "diesimple", "die", "death"};

	static {
		String classic = "source:models/zombie/classic.mdl";
		String fast = "source:models/zombie/fast.mdl";
		String poison = "source:models/zombie/poison.mdl";
		String zombine = "source:models/zombie/zombie_soldier.mdl";
		String soldier = "source:models/combine_soldier.mdl";
		String elite = "source:models/combine_super_soldier.mdl";
		String metrocop = "source:models/police.mdl";
		String antlion = "source:models/antlion.mdl";

		mob(EntityTypes.ZOMBIE, classic, "halflife:models/zombie.mdl", "builtin:zombie");
		mob(EntityTypes.ZOMBIE_VILLAGER, classic, "halflife:models/zombie.mdl", "builtin:zombie");
		mob(EntityTypes.DROWNED, classic, "halflife:models/zombie.mdl", "builtin:zombie");
		mob(EntityTypes.HUSK, fast, classic);
		mob(EntityTypes.ZOMBIFIED_PIGLIN, poison, classic);
		mob(EntityTypes.GIANT, poison, classic);
		// Zombines pull a grenade and run at you, the closest thing to a creeper.
		mob(EntityTypes.CREEPER, zombine, classic);
		mob(EntityTypes.SKELETON, soldier, metrocop);
		mob(EntityTypes.STRAY, soldier, metrocop);
		mob(EntityTypes.BOGGED, soldier, metrocop);
		mob(EntityTypes.WITHER_SKELETON, elite, soldier);
		mob(EntityTypes.PILLAGER, metrocop, soldier);
		mob(EntityTypes.VINDICATOR, soldier, metrocop);
		mob(EntityTypes.EVOKER, elite, soldier);
		mob(EntityTypes.ILLUSIONER, elite, soldier);
		mob(EntityTypes.PIGLIN, metrocop, soldier);
		mob(EntityTypes.PIGLIN_BRUTE, elite, soldier);
		// Poison zombies throw headcrabs as witches throw potions.
		mob(EntityTypes.WITCH, poison, classic);
		mob(EntityTypes.ENDERMAN, "source:models/stalker.mdl", fast);
		mob(EntityTypes.SPIDER, antlion);
		mob(EntityTypes.CAVE_SPIDER, "source:models/antlion_worker.mdl", antlion);
		mob(EntityTypes.SILVERFISH, "source:models/headcrabclassic.mdl", "source:models/headcrab.mdl");
		mob(EntityTypes.ENDERMITE, "source:models/headcrab.mdl", "source:models/headcrabclassic.mdl");
		mob(EntityTypes.RAVAGER, "source:models/antlion_guard.mdl");
		mob(EntityTypes.IRON_GOLEM, "source:models/vortigaunt.mdl");
		mob(EntityTypes.VILLAGER, "source:models/humans/group01/male_07.mdl", "source:models/humans/group01/male_01.mdl");
		mob(EntityTypes.WANDERING_TRADER, "source:models/odessa.mdl", "source:models/humans/group02/male_02.mdl",
				"source:models/humans/group01/male_02.mdl");
		mob(EntityTypes.CHICKEN, "source:models/pigeon.mdl", "source:models/crow.mdl");
		mob(EntityTypes.PARROT, "source:models/seagull.mdl", "source:models/pigeon.mdl");
		mob(EntityTypes.BAT, "source:models/crow.mdl", "source:models/pigeon.mdl");
		mob(EntityTypes.PHANTOM, "source:models/combine_scanner.mdl", "source:models/shield_scanner.mdl");
		mob(EntityTypes.VEX, "source:models/manhack.mdl");
		mob(EntityTypes.GUARDIAN, "source:models/ichthyosaur.mdl");
		mob(EntityTypes.ELDER_GUARDIAN, "source:models/ichthyosaur.mdl");
		mob(EntityTypes.SQUID, "source:models/leech.mdl");
		mob(EntityTypes.WARDEN, "source:models/antlion_guard.mdl");

		// The kart is built small; this makes it big enough to sit in.
		RIGS.put(Karts.KART, new Rig(new String[] {"builtin:kart"}, UNITS * 1.7F, false,
				new String[] {"idle"},
				new String[] {"drive"},
				new String[0],
				new String[0]));
	}

	private static void mob(EntityType<?> type, String... models) {
		RIGS.put(type, new Rig(models, UNITS, true, IDLE, WALK, ATTACK, DEATH));
	}

	private SourceRigs() {
	}

	public static Rig get(EntityType<?> type) {
		return type == null ? null : RIGS.get(type);
	}
}
