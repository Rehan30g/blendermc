package dev.blendermc.endless.client;

import dev.blendermc.BlenderMc;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Client entry of the endless world: while the player is in {@code blendermc:liminal_endless} a {@link EndlessStreamer}
 * keeps tiles of the generated world around them (the vanilla render distance is untouched) and {@link EndlessRenderer}
 * draws them. Fog, sky and the far plane come from the {@code Endless*} mixins through {@link EndlessZones}.
 */
public final class EndlessClient {
	/** The level name the server's mesh cells carry. */
	public static final String LEVEL_NAME = "endless";

	private static @Nullable EndlessStreamer streamer;

	private EndlessClient() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(EndlessClient::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(EndlessRenderer::render);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> chunkChanged(level, chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> chunkChanged(level, chunk.getPos().x, chunk.getPos().z));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(EndlessClient::dispose));
	}

	static @Nullable EndlessStreamer streamer() {
		return streamer;
	}

	/** The geometry source for this world. */
	private static EndlessSource createSource(ClientLevel level) {
		Long seed = serverSeed;
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (seed == null && server != null) {
			seed = server.getWorldData().worldGenOptions().seed();
		}
		if (seed == null) {
			BlenderMc.LOGGER.warn("Endless world: seed unknown on this client, drawing placeholder geometry");
			return new StubSource();
		}
		return new GeneratorSource(seed);
	}

	private static @Nullable Long serverSeed;

	/** For a network payload that carries the world's endless seed (multiplayer); takes effect when the world is next entered. */
	public static void setSeed(long seed) {
		serverSeed = seed;
	}

	private static void dispose() {
		if (streamer != null) {
			streamer.close();
			streamer = null;
		}
		EndlessRenderer.reset();
		EndlessZones.reset();
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		boolean in = level != null && mc.player != null && EndlessZones.isEndless(level);
		if (streamer != null && (!in || streamer.level() != level)) {
			dispose();
		}
		if (!in) {
			return;
		}
		if (streamer == null) {
			try {
				streamer = new EndlessStreamer(createSource(level), level);
			} catch (RuntimeException e) {
				BlenderMc.LOGGER.error("Could not start the endless world renderer", e);
				return;
			}
		}
		streamer.tick(mc);
	}

	/** From the client level mixin: a block changed (render thread). */
	public static void blockChanged(ClientLevel level, BlockPos pos) {
		if (streamer != null && streamer.level() == level) {
			streamer.blockChanged(pos);
		}
	}

	private static void chunkChanged(ClientLevel level, int x, int z) {
		if (streamer != null && streamer.level() == level) {
			streamer.chunkChanged(x, z);
		}
	}
}
