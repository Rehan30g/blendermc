package dev.blendermc.liminal;

import dev.blendermc.BlenderMc;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by /blendermc reload after the .glb files were re-read: the client drops its level cache and reloads resources (atlas). */
public record LiminalReloadPayload() implements CustomPacketPayload {
	public static final LiminalReloadPayload INSTANCE = new LiminalReloadPayload();
	public static final CustomPacketPayload.Type<LiminalReloadPayload> TYPE = new CustomPacketPayload.Type<>(BlenderMc.id("liminal_reload"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiminalReloadPayload> CODEC = StreamCodec.unit(INSTANCE);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
