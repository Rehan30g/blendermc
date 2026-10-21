package dev.echoliminal.liminal;

import dev.echoliminal.EchoLiminal;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Dev time jump ({@code /echoliminal time}): the day-cycle clock of {@code level} is shifted by {@code offset} ticks ({@link LiminalDay#setOffset}). */
public record LiminalTimePayload(String level, double offset) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LiminalTimePayload> TYPE = new CustomPacketPayload.Type<>(EchoLiminal.id("liminal_time"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiminalTimePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(64), LiminalTimePayload::level,
		ByteBufCodecs.DOUBLE, LiminalTimePayload::offset,
		LiminalTimePayload::new);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
