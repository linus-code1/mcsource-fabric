package com.mcsource.tree;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A whole tree, drawn on the client with a Half-Life 2 tree model instead of
 * being built from log and leaves blocks. Only its trunk is solid. Chopping
 * it (hitting it a few times) fells it: it topples over and breaks into
 * logs and a sapling.
 */
public class TreeEntity extends Entity {
	private static final EntityDataAccessor<String> WOOD = SynchedEntityData.defineId(TreeEntity.class, EntityDataSerializers.STRING);
	/** Hits it takes to fell a tree. */
	private static final int CHOPS = 5;
	private static final double RENDER_DISTANCE = 160.0;
	/** Ticks a felled tree takes to fall to the ground. */
	public static final int FALL_TICKS = 30;
	/** Ticks since the tree was felled, or -1 while it stands. */
	private static final EntityDataAccessor<Integer> FALLING = SynchedEntityData.defineId(TreeEntity.class, EntityDataSerializers.INT);
	/** The direction it falls in, in degrees like an entity's yaw. */
	private static final EntityDataAccessor<Float> FALL_DIRECTION = SynchedEntityData.defineId(TreeEntity.class, EntityDataSerializers.FLOAT);

	private int chops;

	public TreeEntity(EntityType<? extends TreeEntity> type, Level level) {
		super(type, level);
		blocksBuilding = true;
	}

	/** The log block the tree would have been built from, such as {@code minecraft:oak_log}. */
	public String wood() {
		return entityData.get(WOOD);
	}

	public void setWood(String wood) {
		entityData.set(WOOD, wood);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(WOOD, "minecraft:oak_log");
		builder.define(FALLING, -1);
		builder.define(FALL_DIRECTION, 0.0F);
	}

	/** Ticks since it was felled, or -1 while it stands. */
	public int falling() {
		return entityData.get(FALLING);
	}

	/** Which way it falls, in degrees like an entity's yaw. */
	public float fallDirection() {
		return entityData.get(FALL_DIRECTION);
	}

	@Override
	public void tick() {
		// A standing tree has nothing to update; a felled one falls over, and
		// when it hits the ground breaks into logs.
		int falling = falling();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (falling < 0) {
			// Tree models switched off, or the chosen game has none: grow the
			// Minecraft tree it stands in for and go.
			if (Trees.blockTrees() && tickCount % 20 == 0) {
				toBlocks(level);
			}
			return;
		}
		entityData.set(FALLING, falling + 1);
		if (falling + 1 >= FALL_TICKS) {
			playSound(SoundEvents.WOOD_BREAK, 2.0F, 0.5F);
			// Logs along the length of the fallen trunk.
			Item log = item(wood());
			if (log != Items.AIR) {
				int count = 4 + random.nextInt(4);
				double angle = Math.toRadians(fallDirection());
				for (int i = 0; i < count; i++) {
					double along = 1.0 + i * 1.2;
					ItemEntity item = new ItemEntity(level, getX() - Math.sin(angle) * along, getY() + 0.5, getZ() + Math.cos(angle) * along,
							new ItemStack(log), 0.0, 0.1, 0.0);
					item.setDefaultPickUpDelay();
					level.addFreshEntity(item);
				}
			}
			Item sapling = item(sapling(wood()));
			if (sapling != Items.AIR) {
				spawnAtLocation(level, new ItemStack(sapling, 1 + random.nextInt(2)));
			}
			discard();
		}
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return falling() < 0;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved() && falling() < 0;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		double range = RENDER_DISTANCE * getViewScale();
		return distance < range * range;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (!(source.getEntity() instanceof Player player) || falling() >= 0) {
			return false;
		}
		chops++;
		if (!player.getAbilities().instabuild && chops < CHOPS) {
			playSound(SoundEvents.WOOD_HIT, 1.0F, 0.8F + random.nextFloat() * 0.3F);
			return true;
		}
		// Felled: it topples away from whoever chopped it, then breaks up.
		playSound(SoundEvents.WOOD_BREAK, 1.5F, 0.7F);
		double dx = getX() - player.getX();
		double dz = getZ() - player.getZ();
		entityData.set(FALL_DIRECTION, (float) Math.toDegrees(Math.atan2(-dx, dz)));
		entityData.set(FALLING, 0);
		return true;
	}

	@Override
	public ItemStack getPickResult() {
		Item log = item(wood());
		return log == Items.AIR ? ItemStack.EMPTY : new ItemStack(log);
	}

	/** Replaces this tree with the Minecraft tree of its wood. */
	private void toBlocks(ServerLevel level) {
		net.minecraft.core.BlockPos pos = blockPosition();
		net.minecraft.world.level.block.grower.TreeGrower grower = switch (wood()) {
			case "minecraft:spruce_log" -> net.minecraft.world.level.block.grower.TreeGrower.SPRUCE;
			case "minecraft:birch_log" -> net.minecraft.world.level.block.grower.TreeGrower.BIRCH;
			case "minecraft:jungle_log" -> net.minecraft.world.level.block.grower.TreeGrower.JUNGLE;
			case "minecraft:acacia_log" -> net.minecraft.world.level.block.grower.TreeGrower.ACACIA;
			case "minecraft:mangrove_log" -> net.minecraft.world.level.block.grower.TreeGrower.MANGROVE;
			case "minecraft:cherry_log" -> net.minecraft.world.level.block.grower.TreeGrower.CHERRY;
			default -> net.minecraft.world.level.block.grower.TreeGrower.OAK;
		};
		net.minecraft.world.level.block.state.BlockState sapling = net.minecraft.world.level.block.Blocks.OAK_SAPLING.defaultBlockState();
		if (!grower.growTree(level, level.getChunkSource().getGenerator(), pos, sapling, random)
				&& grower != net.minecraft.world.level.block.grower.TreeGrower.OAK) {
			net.minecraft.world.level.block.grower.TreeGrower.OAK.growTree(level, level.getChunkSource().getGenerator(), pos, sapling, random);
		}
		discard();
	}

	private static Item item(String id) {
		Identifier location = Identifier.tryParse(id);
		return location == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(location);
	}

	private static String sapling(String wood) {
		return switch (wood) {
			case "minecraft:mangrove_log" -> "minecraft:mangrove_propagule";
			default -> wood.endsWith("_log") ? wood.substring(0, wood.length() - "_log".length()) + "_sapling" : "";
		};
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		setWood(input.getStringOr("wood", "minecraft:oak_log"));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("wood", wood());
	}
}
