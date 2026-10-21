package dev.blendermc.endless;

import dev.blendermc.BlenderMc;
import dev.blendermc.endless.gen.EndlessGenerator;
import dev.blendermc.liminal.LiminalWorld;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The endless liminal world: dimension {@code blendermc:liminal_endless}, filled by {@link EndlessChunkGenerator} from the
 * pure-Java {@link EndlessGenerator}. The seed is the world seed; clients get it by {@link EndlessSeedPayload}.
 */
public final class Endless {
	public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, BlenderMc.id("liminal_endless"));
	/** The level name stored in MeshCellBlockEntity and used for the broken-cell sets. */
	public static final String LEVEL = "endless";

	private static volatile @Nullable EndlessGenerator generator;

	private Endless() {
	}

	/** One call from onInitialize. */
	public static void register() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, BlenderMc.id("endless"), EndlessChunkGenerator.CODEC);
		EndlessSeedPayload.register();
		ServerLifecycleEvents.SERVER_STARTING.register(Endless::ensureSeed);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			generator = null;
			EndlessCells.reset();
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (destination.dimension() == DIMENSION) {
				onJoin(destination.getServer(), player);
			}
		});
	}

	/** Sets the seed from the world seed (server). Read from the world data: at SERVER_STARTING no level exists yet. */
	public static void ensureSeed(MinecraftServer server) {
		setSeed(server.getWorldData().worldGenOptions().seed());
	}

	public static void setSeed(long seed) {
		EndlessGenerator current = generator;
		if (current == null || current.seed != seed) {
			generator = new EndlessGenerator(seed);
			EndlessCells.reset();
		}
	}

	/** The shared generator for the current seed; null until the seed is known. */
	public static @Nullable EndlessGenerator generator() {
		return generator;
	}

	public static long seed() {
		EndlessGenerator g = generator;
		return g == null ? 0L : g.seed;
	}

	public static boolean isEndless(@Nullable Level level) {
		return level != null && level.dimension() == DIMENSION;
	}

	/** Tells a player the seed and which endless cells are broken. */
	public static void onJoin(MinecraftServer server, ServerPlayer player) {
		ensureSeed(server);
		ServerPlayNetworking.send(player, new EndlessSeedPayload(seed()));
		LiminalWorld.send(player, LEVEL);
	}
}
