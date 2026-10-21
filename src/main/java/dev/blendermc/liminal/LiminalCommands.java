package dev.blendermc.liminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * /blendermc enter [level] | leave | reload | restore, and the per-world state behind it ({@code blendermc.json}
 * in the world folder: the active level, where each player entered from, and each level's broken cells).
 */
public final class LiminalCommands {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable State state;
	private static @Nullable Path stateFile;

	/** Saved per world. */
	private static final class State {
		/** The level last entered (a default for "enter"; on an old save, the level that lived at origin 0). */
		String active = LiminalLevels.DEFAULT_LEVEL;
		/** Region slot per level (origin = slot * spacing), assigned on first use and never reused. */
		Map<String, Integer> slots = new LinkedHashMap<>();
		Map<String, Return> returns = new LinkedHashMap<>();
		/** Broken cells (BlockPos.asLong) per level. */
		Map<String, long[]> broken = new LinkedHashMap<>();
		/** Level cells a player's block took over (geometry still shown, back when the block goes). */
		Map<String, long[]> replaced = new LinkedHashMap<>();
		/** Per level, the geometry signature its stone was last filled for (filled once, so dug-out stone stays dug). */
		Map<String, Long> solidFilled = new LinkedHashMap<>();
	}

	/** Live broken sets, written back into {@link State#broken} on save. */
	private static final Map<String, LongOpenHashSet> BROKEN = new HashMap<>();
	private static final Map<String, LongOpenHashSet> REPLACED = new HashMap<>();

	/** Where a player was before entering. */
	private static final class Return {
		String dimension;
		double x;
		double y;
		double z;
		float yaw;
		float pitch;
	}

	private LiminalCommands() {
	}

	/** Registers the command, the level payload and the world load/join hooks. One call from onInitialize. */
	public static void register() {
		LiminalBlocks.register();
		LiminalLevelPayload.register();
		LiminalBrokenPayload.register();
		LiminalBrokenDeltaPayload.register();
		LiminalReloadPayload.register();
		LiminalTimePayload.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> registerCommand(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(LiminalCommands::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(LiminalWorld::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> flushDue(false));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushDue(true));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			state = null;
			stateFile = null;
			BROKEN.clear();
			REPLACED.clear();
			LiminalLevels.setActive(LiminalLevels.DEFAULT_LEVEL);
			LiminalLevels.setSlots(Map.of());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			state(server);
			sender.sendPacket(levelPayload(server, player));
			for (String level : LiminalLevels.slots().keySet()) {
				LiminalWorld.send(player, level);
			}
			dev.blendermc.endless.Endless.onJoin(server, player);
			// A save from before levels were blocks (or a player who logged out inside): make sure the cells exist.
			String here = player.level().dimension() == LiminalLevels.DIMENSION ? LiminalLevels.levelAt(player.blockPosition()) : null;
			if (here != null && LiminalLevels.get(here) != null) {
				LiminalWorld.fill(player.level(), here, null);
			}
		});
	}

	/** The level {@code player} is in (by position), else the level last entered. */
	static String levelFor(MinecraftServer server, @Nullable ServerPlayer player) {
		if (player != null && player.level().dimension() == LiminalLevels.DIMENSION) {
			String here = LiminalLevels.levelAt(player.getX(), player.getZ());
			if (here != null) {
				return here;
			}
		}
		return state(server).active;
	}

	private static LiminalLevelPayload levelPayload(MinecraftServer server, @Nullable ServerPlayer player) {
		return new LiminalLevelPayload(levelFor(server, player), new HashMap<>(LiminalLevels.slots()));
	}

	/** The slot of {@code name}, assigned (and saved, and sent to every client) on first use. -1 when all slots are taken. */
	public static int assignSlot(MinecraftServer server, String name) {
		State s = state(server);
		Integer slot = s.slots.get(name);
		if (slot != null) {
			return slot;
		}
		int next = 0;
		for (int used : s.slots.values()) {
			next = Math.max(next, used + 1);
		}
		if (next > LiminalLevels.MAX_SLOT) {
			return -1;
		}
		s.slots.put(name, next);
		LiminalLevels.setSlots(s.slots);
		save();
		LiminalLevels.LOGGER.info("liminal level '{}' got slot {} (origin {})", name, next, LiminalLevels.originForSlot(next).toShortString());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, levelPayload(server, player));
		}
		return next;
	}

	private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("blendermc")
			.requires(source -> FabricLoader.getInstance().isDevelopmentEnvironment()
				|| Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))
			.executes(context -> enter(context, null))
			.then(Commands.literal("enter")
				.executes(context -> enter(context, null))
				.then(Commands.argument("level", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(LiminalLevels.available(), builder))
					.executes(context -> enter(context, StringArgumentType.getString(context, "level")))))
			.then(Commands.literal("endless").executes(LiminalCommands::enterEndless))
			.then(Commands.literal("leave").executes(LiminalCommands::leave))
			.then(Commands.literal("reload").executes(LiminalCommands::reload))
			.then(Commands.literal("restore").executes(LiminalCommands::restore))
			.then(Commands.literal("time")
				.then(Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 24000))
					.executes(context -> time(context, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "ticks"))))));
	}

	private static int enter(CommandContext<CommandSourceStack> context, @Nullable String requested) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		String name = requested != null ? requested : levelFor(server, player);
		ServerLevel liminal = server.getLevel(LiminalLevels.DIMENSION);
		if (liminal == null) {
			source.sendFailure(Component.literal("The liminal dimension is not loaded in this world"));
			return 0;
		}
		if (!LiminalLevels.available().contains(name) || assignSlot(server, name) < 0) {
			source.sendFailure(Component.literal("No liminal level '" + name + "' (or no free slot). Available: " + LiminalLevels.available()));
			return 0;
		}
		LiminalMesh mesh = LiminalLevels.get(name);
		if (mesh == null) {
			source.sendFailure(Component.literal("Liminal level '" + name + "' failed to load (see the log)"));
			return 0;
		}
		State s = state(server);
		rememberReturn(server, player);
		setActive(server, name);
		LiminalWorld.fill(liminal, name, null);
		LiminalLights.apply(liminal, mesh, null);
		player.teleportTo(liminal, mesh.spawn().x, mesh.spawn().y, mesh.spawn().z, Set.of(), mesh.spawnYaw(), 0.0F, true);
		player.resetFallDistance();
		source.sendSuccess(() -> Component.literal("Entered liminal level " + name + " (" + mesh.triangleCount() + " triangles)"), false);
		return 1;
	}

	/** Saves where {@code player} stands as the place /blendermc leave returns to, unless they are already in a liminal dimension. */
	private static void rememberReturn(MinecraftServer server, ServerPlayer player) {
		if (player.level().dimension() != LiminalLevels.DIMENSION && player.level().dimension() != dev.blendermc.endless.Endless.DIMENSION) {
			Return back = new Return();
			back.dimension = player.level().dimension().identifier().toString();
			back.x = player.getX();
			back.y = player.getY();
			back.z = player.getZ();
			back.yaw = player.getYRot();
			back.pitch = player.getXRot();
			state(server).returns.put(player.getUUID().toString(), back);
		}
	}

	/** /blendermc endless: into the endless world at the generator's spawn. */
	private static int enterEndless(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		ServerLevel world = server.getLevel(dev.blendermc.endless.Endless.DIMENSION);
		if (world == null) {
			source.sendFailure(Component.literal("The endless dimension is not loaded in this world"));
			return 0;
		}
		dev.blendermc.endless.Endless.ensureSeed(server);
		double[] spawn = dev.blendermc.endless.Endless.generator().spawn();
		rememberReturn(server, player);
		save();
		world.getChunk(net.minecraft.util.Mth.floor(spawn[0]) >> 4, net.minecraft.util.Mth.floor(spawn[2]) >> 4);
		dev.blendermc.endless.Endless.onJoin(server, player);
		player.teleportTo(world, spawn[0], spawn[1], spawn[2], Set.of(), (float) spawn[3], 0.0F, true);
		player.resetFallDistance();
		source.sendSuccess(() -> Component.literal("Entered the endless world (seed " + dev.blendermc.endless.Endless.seed() + ")"), false);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		State s = state(server);
		Return back = s.returns.remove(player.getUUID().toString());
		save();
		ServerLevel target = null;
		if (back != null && back.dimension != null) {
			Identifier id = Identifier.tryParse(back.dimension);
			target = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		}
		if (target != null) {
			player.teleportTo(target, back.x, back.y, back.z, Set.of(), back.yaw, back.pitch, true);
		} else {
			player.teleport(player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING));
		}
		player.resetFallDistance();
		String message = target != null ? "Left the liminal dimension" : "Left the liminal dimension (to spawn)";
		source.sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	/** Dev: jumps the day-cycle clock of the level the player stands in (or the active one) to {@code ticks} (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight). */
	private static int time(CommandContext<CommandSourceStack> context, int ticks) throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayerOrException();
		String name = player.level().dimension() == LiminalLevels.DIMENSION ? LiminalLevels.levelAt(player.blockPosition()) : null;
		if (name == null) {
			name = LiminalLevels.activeName();
		}
		LevelSky sky = LiminalLevels.sky(name);
		if (!sky.dayCycle()) {
			source.sendFailure(Component.literal("Level '" + name + "' has no day_cycle"));
			return 0;
		}
		double offset = LiminalDay.offset(name) + ticks - LiminalDay.time(sky, name, player.level().getGameTime());
		offset -= 24000.0 * Math.floor(offset / 24000.0);
		LiminalDay.setOffset(name, offset);
		for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, new LiminalTimePayload(name, offset));
		}
		String level = name;
		source.sendSuccess(() -> Component.literal("Time of " + level + " set to " + ticks), false);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		state(server);
		Map<String, net.minecraft.world.phys.AABB> old = new LinkedHashMap<>();
		for (String level : LiminalLevels.slots().keySet()) {
			LiminalMesh mesh = LiminalLevels.get(level);
			if (mesh != null) {
				old.put(level, mesh.bounds());
			}
		}
		LiminalLevels.reload();
		ServerLevel liminal = server.getLevel(LiminalLevels.DIMENSION);
		StringBuilder summary = new StringBuilder();
		int failed = 0;
		for (String level : LiminalLevels.slots().keySet()) {
			LiminalMesh mesh = LiminalLevels.get(level);
			if (mesh == null) {
				failed++;
				summary.append(' ').append(level).append("(failed)");
				continue;
			}
			summary.append(' ').append(level).append('=').append(mesh.triangleCount());
			if (liminal != null) {
				LiminalWorld.fill(liminal, level, old.get(level));
				LiminalLights.apply(liminal, mesh, old.get(level));
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, LiminalReloadPayload.INSTANCE);
			ServerPlayNetworking.send(player, levelPayload(server, player));
		}
		String text = "Reloaded liminal levels (triangles):" + summary;
		if (failed > 0) {
			source.sendFailure(Component.literal(text + "; see the log"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int restore(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (source.getPlayer() != null && source.getPlayer().level().dimension() == dev.blendermc.endless.Endless.DIMENSION) {
			int placed = dev.blendermc.endless.EndlessWorld.restore((ServerLevel) source.getPlayer().level());
			source.sendSuccess(() -> Component.literal("Restored " + placed + " cells of the endless world"), false);
			return 1;
		}
		ServerLevel liminal = source.getServer().getLevel(LiminalLevels.DIMENSION);
		String name = levelFor(source.getServer(), source.getPlayer());
		LiminalMesh mesh = LiminalLevels.get(name);
		if (liminal == null || mesh == null) {
			source.sendFailure(Component.literal("No liminal level to restore"));
			return 0;
		}
		int placed = LiminalWorld.restore(liminal, name);
		LiminalLights.apply(liminal, mesh, null);
		source.sendSuccess(() -> Component.literal("Restored " + placed + " cells of " + name), false);
		return 1;
	}

	/** The live broken set of {@code level} (mutable; call {@link #saveBroken} after changing it). */
	public static LongOpenHashSet broken(MinecraftServer server, String level) {
		State s = state(server);
		return BROKEN.computeIfAbsent(level, k -> {
			long[] saved = s.broken.get(k);
			return saved == null ? new LongOpenHashSet() : new LongOpenHashSet(saved);
		});
	}

	/** The live replaced set of {@code level} (mutable; call {@link #saveReplaced} after changing it). */
	static LongOpenHashSet replaced(MinecraftServer server, String level) {
		State s = state(server);
		return REPLACED.computeIfAbsent(level, k -> {
			long[] saved = s.replaced.get(k);
			return saved == null ? new LongOpenHashSet() : new LongOpenHashSet(saved);
		});
	}

	/** Levels whose broken/replaced sets changed since the last write, and when the oldest change happened. */
	private static final java.util.Set<String> PENDING_BROKEN = new java.util.HashSet<>();
	private static final java.util.Set<String> PENDING_REPLACED = new java.util.HashSet<>();
	private static long pendingSince;
	private static long lastChange;

	private static void saveSoon() {
		long now = System.currentTimeMillis();
		if (pendingSince == 0) {
			pendingSince = now;
		}
		lastChange = now;
	}

	/** Writes pending broken/replaced changes once they are 2 s old with no newer change (or 10 s old); {@code force} writes now. */
	static void flushDue(boolean force) {
		if (pendingSince == 0 || state == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastChange < 2000 && now - pendingSince < 10000) {
			return;
		}
		long start = System.nanoTime();
		for (String level : PENDING_BROKEN) {
			LongOpenHashSet set = BROKEN.get(level);
			if (set != null) {
				state.broken.put(level, set.toLongArray());
			}
		}
		for (String level : PENDING_REPLACED) {
			LongOpenHashSet set = REPLACED.get(level);
			if (set != null) {
				state.replaced.put(level, set.toLongArray());
			}
		}
		PENDING_BROKEN.clear();
		PENDING_REPLACED.clear();
		pendingSince = 0;
		save();
		LiminalLevels.LOGGER.debug("liminal state saved in {} ms", (System.nanoTime() - start) / 1_000_000);
	}

	static void saveReplaced(String level) {
		if (state != null && REPLACED.get(level) != null) {
			PENDING_REPLACED.add(level);
			saveSoon();
		}
	}

	/** The signature the stone of {@code level} was filled for, or 0. */
	static long solidFilled(MinecraftServer server, String level) {
		Long value = state(server).solidFilled.get(level);
		return value == null ? 0 : value;
	}

	static void setSolidFilled(MinecraftServer server, String level, long signature) {
		state(server).solidFilled.put(level, signature);
		save();
	}

	public static void saveBroken(String level) {
		if (state != null && BROKEN.get(level) != null) {
			PENDING_BROKEN.add(level);
			saveSoon();
		}
	}

	/** Remembers the level last entered, saves it and tells every client (they follow the camera's region afterwards). */
	public static void setActive(MinecraftServer server, String name) {
		state(server).active = name;
		save();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, levelPayload(server, player));
		}
	}

	/** Whether {@code level} is the liminal dimension (convenience for callers holding a Level). */
	public static boolean isLiminal(Level level) {
		return LiminalLevels.isLiminal(level);
	}

	private static State state(MinecraftServer server) {
		if (state == null) {
			load(server);
		}
		return state;
	}

	private static void load(MinecraftServer server) {
		stateFile = server.getWorldPath(LevelResource.ROOT).resolve("blendermc.json");
		State loaded = null;
		if (Files.isRegularFile(stateFile)) {
			try {
				loaded = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
			} catch (IOException | RuntimeException e) {
				LiminalLevels.LOGGER.warn("could not read {}: {}", stateFile, e.toString());
			}
		}
		if (loaded == null) {
			loaded = new State();
		}
		if (loaded.active == null || loaded.active.isEmpty()) {
			loaded.active = LiminalLevels.DEFAULT_LEVEL;
		}
		if (loaded.returns == null) {
			loaded.returns = new LinkedHashMap<>();
		}
		if (loaded.broken == null) {
			loaded.broken = new LinkedHashMap<>();
		}
		if (loaded.replaced == null) {
			loaded.replaced = new LinkedHashMap<>();
		}
		if (loaded.solidFilled == null) {
			loaded.solidFilled = new LinkedHashMap<>();
		}
		if (loaded.slots == null) {
			loaded.slots = new LinkedHashMap<>();
		}
		if (loaded.slots.isEmpty()) {
			// Save from before regions: everything lived at origin 0. Whatever level was active there keeps slot 0 (its
			// blocks and broken/replaced sets stay valid); the others had been overwritten, their old coordinates mean nothing.
			String keep = loaded.active;
			loaded.slots.put(keep, 0);
			loaded.broken.keySet().removeIf(level -> !level.equals(keep));
			loaded.replaced.keySet().removeIf(level -> !level.equals(keep));
			loaded.solidFilled.keySet().removeIf(level -> !level.equals(keep));
		}
		BROKEN.clear();
		REPLACED.clear();
		state = loaded;
		LiminalLevels.setSlots(loaded.slots);
		LiminalLevels.setActive(loaded.active);
	}

	private static void save() {
		if (state == null || stateFile == null) {
			return;
		}
		try {
			Files.writeString(stateFile, GSON.toJson(state), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LiminalLevels.LOGGER.warn("could not save {}: {}", stateFile, e.toString());
		}
	}
}
