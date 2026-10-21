package dev.blendermc.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class BlenderMcClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(dev.blendermc.liminal.LiminalLevelPayload.TYPE,
			(payload, context) -> {
				dev.blendermc.liminal.LiminalLevels.setSlots(payload.slots());
				dev.blendermc.liminal.LiminalLevels.setActive(payload.name());
			});
		ClientPlayNetworking.registerGlobalReceiver(dev.blendermc.liminal.LiminalBrokenPayload.TYPE,
			(payload, context) -> dev.blendermc.liminal.LiminalBroken.setAll(payload.level(), payload.cells()));
		ClientPlayNetworking.registerGlobalReceiver(dev.blendermc.liminal.LiminalBrokenDeltaPayload.TYPE,
			(payload, context) -> dev.blendermc.liminal.LiminalBroken.set(payload.level(), payload.cell(), payload.broken()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
			(handler, client) -> dev.blendermc.liminal.LiminalBroken.clear());
		ClientPlayNetworking.registerGlobalReceiver(dev.blendermc.liminal.LiminalReloadPayload.TYPE, (payload, context) -> {
			// LiminalRenderer reloads resources when the level generation changes; in singleplayer the server's reload already did.
			if (!context.client().hasSingleplayerServer()) {
				dev.blendermc.liminal.LiminalLevels.reload();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(dev.blendermc.liminal.LiminalTimePayload.TYPE,
			(payload, context) -> dev.blendermc.liminal.LiminalDay.setOffset(payload.level(), payload.offset()));
		dev.blendermc.liminal.client.LiminalRenderer.register();
		LiveLinkClient.register();
	}
}
