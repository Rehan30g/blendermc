package dev.echoliminal.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class EchoLiminalClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.liminal.LiminalLevelPayload.TYPE,
			(payload, context) -> {
				dev.echoliminal.liminal.LiminalLevels.setSlots(payload.slots());
				dev.echoliminal.liminal.LiminalLevels.setActive(payload.name());
			});
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.liminal.LiminalBrokenPayload.TYPE,
			(payload, context) -> dev.echoliminal.liminal.LiminalBroken.setAll(payload.level(), payload.cells()));
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.liminal.LiminalBrokenDeltaPayload.TYPE,
			(payload, context) -> dev.echoliminal.liminal.LiminalBroken.set(payload.level(), payload.cell(), payload.broken()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
			(handler, client) -> dev.echoliminal.liminal.LiminalBroken.clear());
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.liminal.LiminalReloadPayload.TYPE, (payload, context) -> {
			// LiminalRenderer reloads resources when the level generation changes; in singleplayer the server's reload already did.
			if (!context.client().hasSingleplayerServer()) {
				dev.echoliminal.liminal.LiminalLevels.reload();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.liminal.LiminalTimePayload.TYPE,
			(payload, context) -> dev.echoliminal.liminal.LiminalDay.setOffset(payload.level(), payload.offset()));
		dev.echoliminal.liminal.client.LiminalRenderer.register();
		dev.echoliminal.endless.client.EndlessClient.register();
		LiveLinkClient.register();
		ClientPlayNetworking.registerGlobalReceiver(dev.echoliminal.endless.EndlessSeedPayload.TYPE,
			(payload, context) -> dev.echoliminal.endless.client.EndlessClient.setSeed(payload.seed()));
	}
}
