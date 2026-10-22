package com.mcsource.terrain;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mcsource.McSource;

/**
 * Whether the server has this mod too. Smooth collision only works when the
 * server moves players over the same shapes; on a server without the mod the
 * player would walk on slopes the server doesn't know about and get pulled
 * back, so there the ground is drawn smoothed but collides as plain blocks,
 * and the player moves as in plain Minecraft.
 */
public final class ServerSupport {
	private ServerSupport() {
	}

	/**
	 * True while smoothed ground collides by its smoothed shape. Always true
	 * on a server; on a client, false while connected to a server without the
	 * mod.
	 */
	private static volatile boolean smoothCollision = true;

	public static boolean smoothCollision() {
		return smoothCollision;
	}

	public static void setSmoothCollision(boolean value) {
		smoothCollision = value;
	}

	/**
	 * A channel the server listens on only when it has the mod. Clients never
	 * send on it: they only ask whether the server listens.
	 */
	public record Present() implements CustomPacketPayload {
		public static final Type<Present> TYPE = new Type<>(McSource.id("present"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Present> CODEC = StreamCodec.unit(new Present());

		@Override
		public Type<Present> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Present.TYPE, Present.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Present.TYPE, (payload, context) -> {
		});
	}
}
