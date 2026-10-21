package dev.echoliminal.liminal;

import dev.echoliminal.EchoLiminal;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Tells the client the liminal level slots (name to region slot) and the level the player is in ({@link LiminalLevels#setSlots}, {@link LiminalLevels#setActive}). */
public record LiminalLevelPayload(String name, java.util.Map<String, Integer> slots) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LiminalLevelPayload> TYPE = new CustomPacketPayload.Type<>(EchoLiminal.id("liminal_level"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiminalLevelPayload> CODEC =
		StreamCodec.composite(ByteBufCodecs.stringUtf8(64), LiminalLevelPayload::name,
			ByteBufCodecs.map(java.util.HashMap::new, ByteBufCodecs.stringUtf8(64), ByteBufCodecs.VAR_INT), LiminalLevelPayload::slots, LiminalLevelPayload::new);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
