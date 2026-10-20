package com.mcsource.client.sound;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mcsource.client.mob.GameInstalls;

/**
 * Half-Life 2's ambience and menu sounds. A looped ambience plays on the
 * title screen and the other menus before a world is loaded, as Half-Life
 * 2's menu plays its background map's ambience, and fades into a quieter loop
 * fitting where the player is once a world is in: wind outdoors, a low hum
 * underground and in the End. Underwater and in the Nether Minecraft's own
 * ambience loops play, with Half-Life 2 sounds put in by the pack. The loops
 * take the place of Minecraft's music. Moving between menu screens plays a
 * menu click, and moving the pointer onto a button its rollover tick.
 *
 * <p>Every sound here is an event in the pack's {@code sounds.json}; one
 * that wasn't found in the player's game files is left out, so without
 * Half-Life 2 nothing changes.
 */
public final class Ambience {
	/** The loops, how loud each plays and how many ticks it takes to fade in fully. */
	private enum Loop {
		MENU("ambient.menu", 0.7F, 40),
		OUTDOORS("ambient.outdoors", 0.25F, 80),
		CAVES("ambient.caves", 0.45F, 80),
		END("ambient.end", 0.35F, 80);

		final SoundEvent event;
		final float volume;
		final int fadeTicks;

		Loop(String name, float volume, int fadeTicks) {
			this.event = SoundEvent.createVariableRangeEvent(id(name));
			this.volume = volume;
			this.fadeTicks = fadeTicks;
		}
	}

	private static final Identifier ROLLOVER = id("ui.rollover");
	private static final Identifier SCREEN = id("ui.screen");
	/** Ticks a new place must last before the in-game loop changes, so walking past a cave mouth doesn't flip it. */
	private static final int SETTLE_TICKS = 40;

	private static final Map<Loop, LoopSound> PLAYING = new EnumMap<>(Loop.class);
	private static boolean enabled = true;
	private static boolean keepMusic;
	private static boolean available;
	private static Loop wanted;
	private static Loop candidate;
	private static int candidateTicks;
	/** How loud the outdoor loop is for where the player stands, from 0 to 1. */
	private static float openness = 1;
	private static int retryTicks;
	private static Screen lastScreen;
	private static long lastRollover;

	private Ambience() {
	}

	public static void register() {
		enabled = GameInstalls.flag("hl2_ambience", true);
		keepMusic = GameInstalls.flag("minecraft_music", false);
		ClientTickEvents.END_CLIENT_TICK.register(Ambience::tick);
	}

	/** Whether Minecraft's music should stay quiet because the Half-Life 2 ambience plays instead. */
	public static boolean replacesMusic() {
		return enabled && available && !keepMusic;
	}

	/** The pointer moved onto a menu button: Half-Life 2's rollover tick, at most one every 50 ms. */
	public static void rollover() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!menu(minecraft.gui.screen())) {
			return;
		}
		long now = Util.getMillis();
		if (now - lastRollover < 50) {
			return;
		}
		lastRollover = now;
		playUi(minecraft, ROLLOVER);
	}

	private static void tick(Minecraft minecraft) {
		SoundManager sounds = minecraft.getSoundManager();
		available = sounds.getSoundEvent(Loop.MENU.event.location()) != null;

		// One menu screen leading to another sounds like Half-Life 2's menu
		// button being let go (the press already clicked). Opening a menu from
		// the game, closing it and loading screens stay quiet.
		Screen screen = minecraft.gui.screen();
		if (screen != lastScreen) {
			if (menu(lastScreen) && menu(screen)) {
				playUi(minecraft, SCREEN);
			}
			lastScreen = screen;
		}

		Loop next = enabled ? choose(minecraft) : null;
		if (next != candidate) {
			candidate = next;
			candidateTicks = 0;
		} else if (candidateTicks < SETTLE_TICKS) {
			candidateTicks++;
		}
		// Going to or from the menus changes at once; places in the world settle first.
		if (candidate == Loop.MENU || wanted == Loop.MENU || candidateTicks >= SETTLE_TICKS) {
			wanted = candidate;
		}

		boolean audible = minecraft.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
		for (Loop loop : Loop.values()) {
			LoopSound sound = PLAYING.get(loop);
			boolean alive = sound != null && !sound.isStopped() && sounds.isActive(sound);
			if (loop == wanted && audible && sounds.getSoundEvent(loop.event.location()) != null) {
				if (!alive) {
					// Not started yet, or stopped from outside (leaving a world stops every sound).
					if (retryTicks > 0) {
						continue;
					}
					sound = new LoopSound(loop);
					PLAYING.put(loop, sound);
					sounds.play(sound);
					retryTicks = 20;
				}
				sound.target = loop.volume * (loop == Loop.OUTDOORS ? openness : 1);
			} else if (sound != null) {
				if (alive) {
					sound.target = 0;
				} else {
					PLAYING.remove(loop);
				}
			}
		}
		if (retryTicks > 0) {
			retryTicks--;
		}
	}

	/** The loop for where the player is, or null where Minecraft's own ambience plays. */
	private static Loop choose(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null) {
			return Loop.MENU;
		}
		if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
			return null;
		}
		if (minecraft.level.dimension().equals(Level.NETHER)) {
			return null;
		}
		if (minecraft.level.dimension().equals(Level.END)) {
			return Loop.END;
		}
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		int sky = minecraft.level.getBrightness(LightLayer.SKY, eye);
		int surface = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, eye.getX(), eye.getZ());
		if (sky <= 6 && surface - eye.getY() > 6) {
			return Loop.CAVES;
		}
		// Quieter under roofs and trees, a little louder high up.
		float height = Mth.clamp((eye.getY() - 80) / 100.0F, 0, 0.5F);
		openness = Mth.clamp((0.3F + 0.7F * sky / 15.0F) * (1 + height), 0, 1.5F);
		return Loop.OUTDOORS;
	}

	/** Whether a screen is one of the menus, not the game's inventories, chat or a loading screen. */
	private static boolean menu(Screen screen) {
		return screen != null && !(screen instanceof AbstractContainerScreen<?>) && !(screen instanceof ChatScreen)
				&& !(screen instanceof LevelLoadingScreen) && !(screen instanceof ProgressScreen)
				&& !(screen instanceof GenericMessageScreen) && !(screen instanceof GenericWaitingScreen)
				&& !(screen instanceof ConnectScreen);
	}

	private static void playUi(Minecraft minecraft, Identifier sound) {
		SoundManager sounds = minecraft.getSoundManager();
		if (sounds.getSoundEvent(sound) != null) {
			sounds.play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(sound), 1, 1));
		}
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath("mcsource", name);
	}

	/** A loop heard everywhere alike, fading towards {@link #target} and stopping once faded out. */
	private static final class LoopSound extends AbstractTickableSoundInstance {
		private final float rate;
		float target;

		LoopSound(Loop loop) {
			super(loop.event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
			this.volume = 0;
			this.rate = loop.volume / loop.fadeTicks;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			if (volume < target) {
				volume = Math.min(target, volume + rate);
			} else if (volume > target) {
				volume = Math.max(target, volume - rate);
			}
			if (volume <= 0 && target <= 0) {
				stop();
			}
		}
	}
}
