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
 * it (hitting it a few times) fells it into logs and a sapling.
 */
public class TreeEntity extends Entity {
	private static final EntityDataAccessor<String> WOOD = SynchedEntityData.defineId(TreeEntity.class, EntityDataSerializers.STRING);
	/** Hits it takes to fell a tree. */
	private static final int CHOPS = 5;
	private static final double RENDER_DISTANCE = 192.0;

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
	}

	@Override
	public void tick() {
		// Trees stand still; nothing to update.
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return true;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
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
		if (!(source.getEntity() instanceof Player player)) {
			return false;
		}
		chops++;
		if (!player.getAbilities().instabuild && chops < CHOPS) {
			playSound(SoundEvents.WOOD_HIT, 1.0F, 0.8F + random.nextFloat() * 0.3F);
			return true;
		}
		playSound(SoundEvents.WOOD_BREAK, 1.5F, 0.7F);
		if (!player.getAbilities().instabuild) {
			Item log = item(wood());
			if (log != Items.AIR) {
				spawnAtLocation(level, new ItemStack(log, 4 + random.nextInt(4)));
			}
			Item sapling = item(sapling(wood()));
			if (sapling != Items.AIR) {
				spawnAtLocation(level, new ItemStack(sapling, 1 + random.nextInt(2)));
			}
		}
		discard();
		return true;
	}

	@Override
	public ItemStack getPickResult() {
		Item log = item(wood());
		return log == Items.AIR ? ItemStack.EMPTY : new ItemStack(log);
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
