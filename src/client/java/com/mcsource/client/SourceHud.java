package com.mcsource.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * A heads-up display in the style of Half-Life 2: translucent dark panels in
 * the corners with large yellow numbers for health, suit (armour) and the
 * held item's count, an oxygen bar like the suit's aux power, a weapon
 * selection row along the top when changing slots, and a small crosshair.
 * Drawn from scratch to look like the original; no Valve assets are used.
 */
public final class SourceHud {
	private static final FontDescription NUMBERS = new FontDescription.Resource(Identifier.fromNamespaceAndPath("mcsource", "hud_numbers"));
	private static final FontDescription LABELS = new FontDescription.Resource(Identifier.fromNamespaceAndPath("mcsource", "hud_labels"));

	// Colours of Half-Life 2's default HUD scheme: yellow on 30% black.
	private static final int FOREGROUND = 0xF0FFDC00;
	private static final int LABEL = 0xC8FFDC00;
	private static final int FOREGROUND_DIM = 0x80FFDC00;
	private static final int GLOW = 0x30FFDC00;
	private static final int DAMAGED = 0xF0B40000;
	private static final int PANEL = 0x4C000000;
	private static final int PANEL_SELECTED = 0x80000000;

	// Layout on Half-Life 2's 640x480 reference screen, scaled to the window.
	private static final float REFERENCE_HEIGHT = 480.0F;
	private static final float PANEL_Y = 432.0F;
	private static final float PANEL_TALL = 36.0F;
	private static final float HEALTH_X = 16.0F;
	private static final float HEALTH_WIDE = 102.0F;
	private static final float SUIT_X = 140.0F;
	private static final float SUIT_WIDE = 108.0F;
	private static final float AMMO_RIGHT = 150.0F;
	private static final float AMMO_WIDE = 136.0F;
	private static final float LABEL_X = 8.0F;
	private static final float LABEL_Y = 20.0F;
	private static final float DIGIT_X = 50.0F;
	private static final float DIGIT_Y = 2.0F;
	/** Height of the big digits on the reference screen. */
	private static final float DIGIT_TALL = 32.0F;
	/** Size the digit font is made at, in GUI pixels. */
	private static final float DIGIT_FONT = 18.0F;
	private static final long SELECTION_SHOW_MS = 1500;

	private static int lastSlot = -1;
	private static long slotChangedAt;

	private SourceHud() {
	}

	public static void register() {
		HudElementRegistry.removeElement(VanillaHudElements.HEALTH_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.ARMOR_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.FOOD_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.AIR_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.EXPERIENCE_LEVEL);
		HudElementRegistry.removeElement(VanillaHudElements.INFO_BAR);
		HudElementRegistry.removeElement(VanillaHudElements.HELD_ITEM_TOOLTIP);
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, vanilla -> (graphics, delta) -> {
			if (!draw(graphics, delta)) {
				vanilla.extractRenderState(graphics, delta);
			}
		});
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> SourceHud::crosshair);
	}

	/** Draws the HUD; returns false in spectator mode, where the vanilla bar is kept. */
	private static boolean draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator()) {
			return false;
		}
		Font font = minecraft.font;
		int width = graphics.guiWidth();
		float k = graphics.guiHeight() / REFERENCE_HEIGHT;

		if (minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer()) {
			int health = Mth.ceil((player.getHealth() + player.getAbsorptionAmount()) * 100.0F / player.getMaxHealth());
			numberPanel(graphics, font, k, HEALTH_X, HEALTH_WIDE, "HEALTH", health, health <= 25 ? DAMAGED : FOREGROUND);
			float x = SUIT_X;
			int armor = player.getArmorValue() * 5;
			if (armor > 0) {
				numberPanel(graphics, font, k, x, SUIT_WIDE, "SUIT", armor, FOREGROUND);
				x += SUIT_WIDE + 24.0F;
			}
			int food = player.getFoodData().getFoodLevel() * 5;
			numberPanel(graphics, font, k, x, SUIT_WIDE, "FOOD", food, food <= 30 ? DAMAGED : FOREGROUND);

			int air = player.getAirSupply();
			if (air < player.getMaxAirSupply() || player.isUnderWater()) {
				auxPower(graphics, font, k, "OXYGEN", Math.max(air, 0) / (float) player.getMaxAirSupply());
			}
		}

		ItemStack held = player.getInventory().getSelectedItem();
		if (!held.isEmpty()) {
			int value = held.isDamageableItem() ? held.getMaxDamage() - held.getDamageValue() : held.getCount();
			float x = width / k - AMMO_RIGHT;
			numberPanel(graphics, font, k, x, AMMO_WIDE, "AMMO", value, FOREGROUND);
		}

		weaponSelection(graphics, font, player, width);
		return true;
	}

	private static Component numbers(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withFont(NUMBERS));
	}

	private static Component label(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withFont(LABELS));
	}

	/** A label and a big number in a panel, laid out like Half-Life 2's health panel. */
	private static void numberPanel(GuiGraphicsExtractor graphics, Font font, float k, float x, float wide, String label, int value, int color) {
		int px = Math.round(x * k);
		int py = Math.round(PANEL_Y * k);
		panel(graphics, px, py, Math.round(wide * k), Math.round(PANEL_TALL * k), PANEL);
		graphics.text(font, label(label), px + Math.round(LABEL_X * k), py + Math.round(LABEL_Y * k), (color & 0xFFFFFF) | (LABEL & 0xFF000000), false);

		Component digits = numbers(Integer.toString(value));
		float scale = DIGIT_TALL * k / DIGIT_FONT;
		graphics.pose().pushMatrix();
		graphics.pose().translate(px + DIGIT_X * k, py + DIGIT_Y * k);
		graphics.pose().scale(scale, scale);
		// A soft glow behind the digits, like the HUD's additive glow pass.
		int glow = (color & 0xFFFFFF) | (GLOW & 0xFF000000);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (dx != 0 || dy != 0) {
					graphics.text(font, digits, dx, dy, glow, false);
				}
			}
		}
		graphics.text(font, digits, 0, 0, color, false);
		graphics.pose().popMatrix();
	}

	/** A row of segments like the suit's aux power meter, above the health panel. */
	private static void auxPower(GuiGraphicsExtractor graphics, Font font, float k, String label, float fraction) {
		int x = Math.round(HEALTH_X * k);
		int y = Math.round((PANEL_Y - 30.0F) * k);
		int segments = 10;
		int segment = Math.max(2, Math.round(6.0F * k));
		int width = Math.round(HEALTH_WIDE * k);
		int height = Math.round(24.0F * k);
		panel(graphics, x, y, width, height, PANEL);
		graphics.text(font, label(label), x + Math.round(LABEL_X * k), y + Math.round(3.0F * k), LABEL, false);
		int lit = Mth.ceil(fraction * segments);
		int barY = y + height - Math.round(8.0F * k);
		for (int i = 0; i < segments; i++) {
			int sx = x + Math.round(LABEL_X * k) + i * (segment + Math.max(1, Math.round(2.0F * k)));
			graphics.fill(sx, barY, sx + segment, barY + Math.max(2, Math.round(4.0F * k)), i < lit ? FOREGROUND : FOREGROUND_DIM & 0x40FFFFFF);
		}
	}

	/** Hotbar slots along the top of the screen, shown briefly after changing slot. */
	private static void weaponSelection(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, int width) {
		int selected = player.getInventory().getSelectedSlot();
		long now = Util.getMillis();
		if (selected != lastSlot) {
			if (lastSlot != -1) {
				slotChangedAt = now;
			}
			lastSlot = selected;
		}
		if (now - slotChangedAt > SELECTION_SHOW_MS) {
			return;
		}
		int box = 22;
		int gap = 3;
		int total = 9 * box + 8 * gap;
		int left = (width - total) / 2;
		int top = 8;
		for (int slot = 0; slot < 9; slot++) {
			int bx = left + slot * (box + gap);
			boolean active = slot == selected;
			panel(graphics, bx, top, box, box, active ? PANEL_SELECTED : PANEL);
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty()) {
				graphics.item(stack, bx + 3, top + 3);
			}
			graphics.text(font, label(Integer.toString(slot + 1)), bx + 2, top + 1, active ? FOREGROUND : FOREGROUND_DIM, false);
			if (active) {
				graphics.fill(bx, top + box, bx + box, top + box + 1, FOREGROUND);
			}
		}
		ItemStack current = player.getInventory().getSelectedItem();
		if (!current.isEmpty()) {
			Component name = Component.literal(current.getHoverName().getString().toUpperCase()).withStyle(Style.EMPTY.withFont(LABELS));
			graphics.text(font, name, (width - font.width(name)) / 2, top + box + 4, FOREGROUND, false);
		}
	}

	/** A dark translucent box with rounded corners, like Source's HUD panels. */
	private static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		int radius = Math.max(2, Math.min(height, width) / 6);
		for (int row = 0; row < radius; row++) {
			// Inset of this row of the corner, from the circle through the corner.
			float d = radius - row - 0.5F;
			int inset = Math.round(radius - Mth.sqrt(radius * radius - d * d));
			graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
			graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
		}
		graphics.fill(x, y + radius, x + width, y + height - radius, color);
	}

	/**
	 * Half-Life 2's crosshair: a small ring of four dots round a centre dot,
	 * drawn in screen pixels so it stays fine at any GUI scale, between two
	 * faint arcs like its quick-info brackets, the left one filled with
	 * health and the right with food.
	 */
	private static void crosshair(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (!minecraft.options.getCameraType().isFirstPerson() || player == null || player.isSpectator()) {
			return;
		}
		float guiScale = (float) minecraft.getWindow().getGuiScale();
		// Work in real pixels from here.
		graphics.pose().pushMatrix();
		graphics.pose().scale(1.0F / guiScale, 1.0F / guiScale);
		int cx = Math.round(graphics.guiWidth() * guiScale / 2.0F);
		int cy = Math.round(graphics.guiHeight() * guiScale / 2.0F);
		int unit = Math.max(1, Math.round(guiScale / 2.0F));
		int color = 0xC8FFDC00;
		graphics.fill(cx - unit / 2, cy - unit / 2, cx - unit / 2 + unit, cy - unit / 2 + unit, color);
		int ring = 5 * unit;
		for (int i = 0; i < 4; i++) {
			int dx = i == 0 ? ring : i == 1 ? -ring : 0;
			int dy = i == 2 ? ring : i == 3 ? -ring : 0;
			graphics.fill(cx + dx - unit / 2, cy + dy - unit / 2, cx + dx - unit / 2 + unit, cy + dy - unit / 2 + unit, color);
		}
		if (minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer()) {
			float health = Mth.clamp(player.getHealth() / player.getMaxHealth(), 0.0F, 1.0F);
			float food = player.getFoodData().getFoodLevel() / 20.0F;
			arc(graphics, cx, cy, 16 * unit, unit, true, health, health <= 0.25F ? 0xC8FF3020 : color);
			arc(graphics, cx, cy, 16 * unit, unit, false, food, food <= 0.3F ? 0xC8FF3020 : color);
		}
		graphics.pose().popMatrix();
	}

	/** One quick-info bracket: a thin arc left or right of the centre, lit from the bottom up to {@code fraction}. */
	private static void arc(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int unit, boolean left, float fraction, int lit) {
		int dim = 0x30FFDC00;
		int steps = radius * 2;
		// From 50 degrees below the horizontal to 50 above.
		for (int i = 0; i <= steps; i++) {
			float t = i / (float) steps;
			double angle = Math.toRadians(-50.0 + 100.0 * t);
			int x = cx + (int) Math.round((left ? -1 : 1) * Math.cos(angle) * radius);
			int y = cy - (int) Math.round(Math.sin(angle) * radius);
			graphics.fill(x, y, x + unit, y + unit, t <= fraction ? lit : dim);
		}
	}
}
