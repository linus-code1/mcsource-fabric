package com.mcsource.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

import com.mcsource.McSource;

/**
 * Trees as {@link TreeEntity} models instead of blocks. When on, every tree
 * the world would grow (in new chunks and from saplings) is planted as one
 * entity at its foot.
 */
public final class Trees {
	private static final ResourceKey<EntityType<?>> TREE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, McSource.id("tree"));

	public static final EntityType<TreeEntity> TREE = Registry.register(BuiltInRegistries.ENTITY_TYPE, TREE_KEY,
			EntityType.Builder.<TreeEntity>of(TreeEntity::new, MobCategory.MISC)
					// Only the trunk is solid.
					.sized(0.8F, 4.0F)
					.clientTrackingRange(12)
					.updateInterval(100)
					.fireImmune()
					.build(TREE_KEY));

	/**
	 * Set by the client when it has tree models to draw (Half-Life 2 is
	 * installed); a dedicated server keeps block trees.
	 */
	private static volatile boolean enabled;

	private Trees() {
	}

	public static void register() {
		// Registered by loading this class.
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/**
	 * Plants a tree entity in place of the tree {@code feature} would build at
	 * {@code origin}. Returns whether one was planted.
	 */
	public static boolean plant(WorldGenLevel level, RandomSource random, BlockPos origin, TreeFeature feature) {
		if (!TreeFeature.validTreePos(level, origin)) {
			return false;
		}
		boolean generating = !(level instanceof ServerLevel);
		// Half-Life 2 trees are bigger than Minecraft's, so forests are thinned
		// out to keep their crowns from merging. Saplings always grow.
		if (generating && (origin.asLong() * 0x9E3779B97F4A7C15L >>> 63) == 0) {
			return true;
		}
		BlockState log = feature.trunkProvider().value().getState(level, random, origin);
		TreeEntity tree = new TreeEntity(TREE, level.getLevel());
		tree.setWood(BuiltInRegistries.BLOCK.getKey(log.getBlock()).toString());
		tree.snapTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
		return level.addFreshEntity(tree);
	}
}
