package dev.echoliminal.liminal;

import dev.echoliminal.EchoLiminal;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Every broken cell ({@link net.minecraft.core.BlockPos#asLong}) of liminal level {@code level}; replaces the client's set. */
public record LiminalBrokenPayload(String level, long[] cells) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LiminalBrokenPayload> TYPE = new CustomPacketPayload.Type<>(EchoLiminal.id("liminal_broken"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiminalBrokenPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(64), LiminalBrokenPayload::level,
		ByteBufCodecs.LONG_ARRAY, LiminalBrokenPayload::cells,
		LiminalBrokenPayload::new);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
