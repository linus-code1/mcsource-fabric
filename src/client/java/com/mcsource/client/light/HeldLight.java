package com.mcsource.client.light;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Light from something bright held in the player's hand, such as a torch.
 * Minecraft only lights blocks from placed light sources; here the light
 * round the player is raised while they hold one, and the blocks near them
 * are rebuilt as they move so the light goes with them.
 */
public final class HeldLight {
	/** Where the light is (the middle of the block at the player's hands) and how bright, or null when there is none. */
	private record Source(int x, int y, int z, int level, boolean soul) {
	}

	private static volatile Source current;

	private HeldLight() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HeldLight::tick);
	}

	private static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		Player player = minecraft.player;
		Source next = null;
		if (level != null && player != null && !player.isSpectator()) {
			int main = emission(player.getMainHandItem());
			int off = emission(player.getOffhandItem());
			int bright = Math.max(main, off);
			if (bright > 0 && !player.isUnderWater()) {
				boolean soul = soul(main >= off ? player.getMainHandItem() : player.getOffhandItem());
				BlockPos at = BlockPos.containing(player.getX(), player.getY() + player.getEyeHeight() * 0.7, player.getZ());
				// On smoothed ground the hands can sit inside a block that is drawn
				// lower than it is; light from inside it was cast in long false
				// shadows across the ground. The light is moved up into the open.
				for (int up = 0; up < 2 && level.getBlockState(at).canOcclude(); up++) {
					at = at.above();
				}
				next = new Source(at.getX(), at.getY(), at.getZ(), bright, soul);
			}
		}
		Source last = current;
		if (java.util.Objects.equals(last, next)) {
			return;
		}
		current = next;
		SourceLight.forget();
		if (level != null) {
			// The blocks round where the light was and where it is now are drawn again.
			dirty(level, last);
			dirty(level, next);
		}
	}

	private static void dirty(ClientLevel level, Source source) {
		if (source == null) {
			return;
		}
		int reach = source.level();
		level.setSectionRangeDirty(SectionPos.blockToSectionCoord(source.x() - reach), SectionPos.blockToSectionCoord(source.y() - reach),
				SectionPos.blockToSectionCoord(source.z() - reach), SectionPos.blockToSectionCoord(source.x() + reach),
				SectionPos.blockToSectionCoord(source.y() + reach), SectionPos.blockToSectionCoord(source.z() + reach));
	}

	/** How much light an item gives off when held: what it gives off when placed. */
	private static int emission(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0;
		}
		if (stack.is(Items.LAVA_BUCKET)) {
			return 15;
		}
		if (stack.getItem() instanceof BlockItem block) {
			return block.getBlock().defaultBlockState().getLightEmission();
		}
		return 0;
	}

	private static boolean soul(ItemStack stack) {
		return stack.getItem() instanceof BlockItem block && Emitters.isSoul(block.getBlock().defaultBlockState());
	}

	/**
	 * The held light as x, y, z, brightness (1 to 15) and 1 for soul fire
	 * (else 0), or null when nothing bright is held.
	 */
	public static int[] source() {
		Source source = current;
		return source == null ? null : new int[] {source.x(), source.y(), source.z(), source.level(), source.soul() ? 1 : 0};
	}

	/**
	 * {@code packed} light at {@code pos} with the held light added: it falls
	 * off by one level a block, as placed light does.
	 */
	public static int apply(int packed, BlockPos pos) {
		Source source = current;
		if (source == null) {
			return packed;
		}
		int dx = pos.getX() - source.x();
		int dy = pos.getY() - source.y();
		int dz = pos.getZ() - source.z();
		int reach = source.level();
		if (Math.abs(dx) > reach || Math.abs(dy) > reach || Math.abs(dz) > reach) {
			return packed;
		}
		int light = source.level() - (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
		if (light <= LightCoordsUtil.block(packed)) {
			return packed;
		}
		return LightCoordsUtil.withBlock(packed, light);
	}
}
