package dev.echoliminal.endless;

import dev.echoliminal.EchoLiminal;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The seed of the endless world, so a client can generate the same far geometry. */
public record EndlessSeedPayload(long seed) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EndlessSeedPayload> TYPE = new CustomPacketPayload.Type<>(EchoLiminal.id("endless_seed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EndlessSeedPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_LONG, EndlessSeedPayload::seed,
		EndlessSeedPayload::new);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
