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

	private static final int FOREGROUND = 0xE6FFDC00;
	private static final int FOREGROUND_DIM = 0x99FFDC00;
	private static final int DAMAGED = 0xE6C80000;
	private static final int PANEL = 0x55000000;
	private static final int PANEL_SELECTED = 0x88000000;

	private static final int MARGIN = 8;
	private static final int PANEL_HEIGHT = 26;
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
		int height = graphics.guiHeight();
		int bottom = height - MARGIN - PANEL_HEIGHT;

		int x = MARGIN;
		if (minecraft.gameMode != null && minecraft.gameMode.canHurtPlayer()) {
			int health = Mth.ceil((player.getHealth() + player.getAbsorptionAmount()) * 100.0F / player.getMaxHealth());
			x += numberPanel(graphics, font, x, bottom, "HEALTH", health, health <= 25 ? DAMAGED : FOREGROUND) + MARGIN;
			int armor = player.getArmorValue() * 5;
			if (armor > 0) {
				x += numberPanel(graphics, font, x, bottom, "SUIT", armor, FOREGROUND) + MARGIN;
			}
			int food = player.getFoodData().getFoodLevel() * 5;
			x += numberPanel(graphics, font, x, bottom, "FOOD", food, food <= 30 ? DAMAGED : FOREGROUND) + MARGIN;

			int air = player.getAirSupply();
			if (air < player.getMaxAirSupply() || player.isUnderWater()) {
				auxPower(graphics, font, MARGIN, bottom - MARGIN - 18, "OXYGEN", Math.max(air, 0) / (float) player.getMaxAirSupply());
			}
		}

		ItemStack held = player.getInventory().getSelectedItem();
		if (!held.isEmpty()) {
			int value = held.isDamageableItem() ? held.getMaxDamage() - held.getDamageValue() : held.getCount();
			String label = held.isDamageableItem() ? "DURABILITY" : "AMOUNT";
			int panelWidth = panelWidth(font, label, value) + 22;
			int px = width - MARGIN - panelWidth;
			numberPanel(graphics, font, px, bottom, label, value, FOREGROUND, panelWidth);
			graphics.item(held, px + panelWidth - 20, bottom + (PANEL_HEIGHT - 16) / 2);
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

	private static int panelWidth(Font font, String label, int value) {
		return 6 + font.width(label(label)) + 4 + font.width(numbers(Integer.toString(value))) + 6;
	}

	private static int numberPanel(GuiGraphicsExtractor graphics, Font font, int x, int y, String label, int value, int color) {
		int panelWidth = panelWidth(font, label, value);
		numberPanel(graphics, font, x, y, label, value, color, panelWidth);
		return panelWidth;
	}

	private static void numberPanel(GuiGraphicsExtractor graphics, Font font, int x, int y, String label, int value, int color, int panelWidth) {
		panel(graphics, x, y, panelWidth, PANEL_HEIGHT, PANEL);
		Component labelText = label(label);
		graphics.text(font, labelText, x + 6, y + PANEL_HEIGHT - 6 - font.lineHeight + 2, color, false);
		graphics.text(font, numbers(Integer.toString(value)), x + 6 + font.width(labelText) + 4, y + 4, color, false);
	}

	/** A row of segments like the suit's aux power meter. */
	private static void auxPower(GuiGraphicsExtractor graphics, Font font, int x, int y, String label, float fraction) {
		int segments = 10;
		int width = 6 + segments * 5 + 6;
		panel(graphics, x, y, width, 18, PANEL);
		graphics.text(font, label(label), x + 6, y + 2, FOREGROUND, false);
		int lit = Mth.ceil(fraction * segments);
		for (int i = 0; i < segments; i++) {
			int sx = x + 6 + i * 5;
			graphics.fill(sx, y + 11, sx + 4, y + 15, i < lit ? FOREGROUND : FOREGROUND_DIM & 0x40FFFFFF);
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
		int top = MARGIN;
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

	/** A dark translucent panel with its corners cut, like Source's rounded boxes. */
	private static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x + 2, y, x + width - 2, y + 1, color);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, color);
		graphics.fill(x, y + 2, x + width, y + height - 2, color);
		graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, color);
		graphics.fill(x + 2, y + height - 1, x + width - 2, y + height, color);
	}

	private static void crosshair(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.options.getCameraType().isFirstPerson() || minecraft.player == null || minecraft.player.isSpectator()) {
			return;
		}
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		int color = 0xCCFFDC00;
		// Four short ticks around an open centre.
		graphics.fill(cx - 5, cy, cx - 2, cy + 1, color);
		graphics.fill(cx + 2, cy, cx + 5, cy + 1, color);
		graphics.fill(cx, cy - 5, cx + 1, cy - 2, color);
		graphics.fill(cx, cy + 2, cx + 1, cy + 5, color);
	}
}
