package com.mcsource.kart;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Places a kart on the block you use it on, facing the way you look. */
public class KartItem extends Item {
	public KartItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		KartEntity kart = Karts.KART.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (kart == null) {
			return InteractionResult.FAIL;
		}
		Player player = context.getPlayer();
		float yaw = player != null ? player.getYRot() : 0.0F;
		kart.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
		kart.yBodyRot = yaw;
		kart.yHeadRot = yaw;
		if (!level.noCollision(kart, kart.getBoundingBox())) {
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(kart);
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
