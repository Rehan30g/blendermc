package dev.blendermc.liminal;

import dev.blendermc.BlenderMc;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One cell of liminal level {@code level} became broken ({@code broken}) or whole again. */
public record LiminalBrokenDeltaPayload(String level, long cell, boolean broken) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LiminalBrokenDeltaPayload> TYPE =
		new CustomPacketPayload.Type<>(BlenderMc.id("liminal_broken_delta"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiminalBrokenDeltaPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(64), LiminalBrokenDeltaPayload::level,
		ByteBufCodecs.VAR_LONG, LiminalBrokenDeltaPayload::cell,
		ByteBufCodecs.BOOL, LiminalBrokenDeltaPayload::broken,
		LiminalBrokenDeltaPayload::new);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
