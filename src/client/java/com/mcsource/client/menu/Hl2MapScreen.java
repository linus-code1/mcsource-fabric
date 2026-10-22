package com.mcsource.client.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.world.hl2.Hl2MapGenerator;

/**
 * Picks a map from the player's Source games (Half-Life 2 and any others
 * found) to make the new world from. Opened from a button on the Create
 * World screen; the chosen map replaces the world's generator, and the
 * world is named after it.
 */
public class Hl2MapScreen extends Screen {
	private static final int PER_PAGE = 8;
	/** The map each Create World screen has been given, so its button can say so. */
	private static final Map<CreateWorldScreen, String> CHOSEN = new WeakHashMap<>();

	private final CreateWorldScreen parent;
	private final List<String> maps;
	private final List<Button> mapButtons = new ArrayList<>();
	private List<String> shown;
	private int page;
	private EditBox search;
	private Button previous;
	private Button next;

	private Hl2MapScreen(CreateWorldScreen parent, List<String> maps) {
		super(Component.literal("Half-Life 2 Map"));
		this.parent = parent;
		this.maps = maps;
		this.shown = maps;
	}

	/** Adds the button to the Create World screen, and gives the server side the game files to build maps from. */
	public static void register() {
		Hl2MapGenerator.files = GameInstalls::source;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof CreateWorldScreen create)) {
				return;
			}
			String chosen = CHOSEN.get(create);
			Component label = Component.literal(chosen == null ? "HL2 Map..." : "Map: " + chosen);
			int buttonWidth = 100;
			int x = 8;
			int y = height - 28;
			if (width / 2 - 155 - 8 < buttonWidth) {
				// No room beside Create and Cancel: above them instead.
				x = width / 2 - buttonWidth / 2;
				y = height - 52;
			}
			Button button = Button.builder(label, pressed -> client.gui.setScreen(new Hl2MapScreen(create, findMaps())))
					.bounds(x, y, buttonWidth, 20)
					.tooltip(Tooltip.create(Component.literal("Make this world from a Half-Life 2 map (or any Source map) in your games.")))
					.build();
			Screens.getWidgets(screen).add(button);
		});
	}

	/** Every map in the player's Source games, by name. */
	private static List<String> findMaps() {
		TreeSet<String> names = new TreeSet<>();
		try {
			for (String path : GameInstalls.source().list("maps/")) {
				String lower = path.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".bsp") && lower.indexOf('/', "maps/".length()) < 0) {
					names.add(lower.substring("maps/".length(), lower.length() - ".bsp".length()));
				}
			}
		} catch (java.io.IOException | RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("mcsource").warn("Could not list maps", e);
		}
		return new ArrayList<>(names);
	}

	@Override
	protected void init() {
		search = new EditBox(font, width / 2 - 100, 36, 200, 20, Component.literal("Search"));
		search.setHint(Component.literal("Search maps"));
		search.setResponder(text -> {
			String query = text.trim().toLowerCase(Locale.ROOT);
			shown = query.isEmpty() ? maps : maps.stream().filter(map -> map.contains(query)).toList();
			page = 0;
			layoutMaps();
		});
		addRenderableWidget(search);
		int bottom = height - 28;
		previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			layoutMaps();
		}).bounds(width / 2 - 100, bottom - 24, 40, 20).build());
		next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			layoutMaps();
		}).bounds(width / 2 + 60, bottom - 24, 40, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 75, bottom, 150, 20).build());
		layoutMaps();
		setInitialFocus(search);
	}

	private void layoutMaps() {
		for (Button button : mapButtons) {
			removeWidget(button);
		}
		mapButtons.clear();
		int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, pages - 1));
		int rowHeight = Math.max(20, Math.min(24, (height - 36 - 24 - 60) / PER_PAGE));
		for (int i = 0; i < PER_PAGE; i++) {
			int index = page * PER_PAGE + i;
			if (index >= shown.size()) {
				break;
			}
			String map = shown.get(index);
			mapButtons.add(addRenderableWidget(Button.builder(Component.literal(map), b -> choose(map))
					.bounds(width / 2 - 100, 62 + i * rowHeight, 200, 20).build()));
		}
		previous.active = page > 0;
		next.active = page < pages - 1;
	}

	private void choose(String map) {
		var state = parent.getUiState();
		state.setName(map);
		state.setGenerateStructures(false);
		state.updateDimensions((registries, dimensions) -> {
			Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
			return dimensions.replaceOverworldGenerator(registries, new Hl2MapGenerator(new FixedBiomeSource(plains), map));
		});
		CHOSEN.put(parent, map);
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		if (maps.isEmpty()) {
			graphics.centeredText(font, "No Source maps found in your games.", width / 2, 80, 0xFFFFA0A0);
			graphics.centeredText(font, "Set halflife2_path in config/mcsource.properties.", width / 2, 94, 0xFFA0A0A0);
		} else {
			int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
			graphics.centeredText(font, (page + 1) + " / " + pages, width / 2, height - 46, 0xFFA0A0A0);
		}
	}
}
