package dev.blendermc.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blendermc.liminal.LiminalBlocks;
import dev.blendermc.liminal.LiminalCommands;
import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalLights;
import dev.blendermc.liminal.LiminalMesh;
import dev.blendermc.liminal.LiminalWorld;
import dev.blendermc.liminal.MeshCellBlockEntity;
import dev.blendermc.liminal.MeshPiece;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live link with a running Blender (add-on in tools/blender/blendermc_live): Blender streams its objects and scene
 * settings into a level that is rebuilt as they change, and the game streams back the player, the blocks and mesh
 * pieces placed in the level and the cells dug out of it, which Blender turns into geometry of its own.
 *
 * <p>Loopback TCP, one Blender at a time. Each frame is a big-endian int length + UTF-8 JSON header, then a big-endian
 * int length + binary payload (may be empty). Coordinates sent to Blender are glTF axes relative to the level origin.
 * On unless started with {@code -Dblendermc.live=false}. Port: {@code -Dblendermc.live.port}.
 */
public final class LiveLink {
	/** 2: block models, entities, lamps. Blender's add-on says its own in hello; a mismatch means it is an old copy. */
	public static final int PROTOCOL = 2;
	public static final int DEFAULT_PORT = 47660;
	private static final Logger LOGGER = LoggerFactory.getLogger("blendermc/live");
	/** Bigger levels are not scanned for placed blocks when Blender connects. */
	private static final long MAX_SCAN = 4_000_000L;

	private static volatile @Nullable MinecraftServer server;
	private static @Nullable ServerSocket listener;
	private static volatile @Nullable Connection connection;
	/** The level the connected Blender edits. */
	private static volatile @Nullable String level;
	private static boolean playerInside;
	private static volatile int connections;
	private static int ticks;
	/**
	 * Client side (same JVM): sends Blender the real model of a block state (its baked quads, textures and tint at that
	 * position), see LiveLinkClient. Called once per state and connection.
	 */
	public static volatile @Nullable BiConsumer<BlockState, BlockPos> blockModels;

	private LiveLink() {
	}

	/** Whether a Blender is connected right now. */
	public static boolean linked() {
		Connection open = connection;
		return open != null && open.greeted;
	}

	public static void register() {
		// On unless -Dblendermc.live=false: listening on 127.0.0.1 only, it is what the mod is for.
		if ("false".equalsIgnoreCase(System.getProperty("blendermc.live"))) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(LiveLink::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
		ServerTickEvents.END_SERVER_TICK.register(LiveLink::tick);
	}

	private static void start(MinecraftServer started) {
		server = started;
		int port = Integer.getInteger("blendermc.live.port", DEFAULT_PORT);
		try {
			ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
			listener = socket;
			Thread accept = new Thread(() -> acceptLoop(socket), "blendermc-live-accept");
			accept.setDaemon(true);
			accept.start();
			LOGGER.info("Blender live link listening on 127.0.0.1:{}", port);
		} catch (IOException e) {
			LOGGER.warn("Blender live link could not listen on port {}: {}", port, e.toString());
		}
	}

	private static void stop() {
		Connection open = connection;
		if (open != null) {
			open.close();
		}
		connection = null;
		level = null;
		if (listener != null) {
			try {
				listener.close();
			} catch (IOException ignored) {
			}
			listener = null;
		}
		LiveScene.closeAll();
		server = null;
	}

	private static void acceptLoop(ServerSocket socket) {
		while (!socket.isClosed()) {
			try {
				Socket client = socket.accept();
				client.setTcpNoDelay(true);
				Connection old = connection;
				if (old != null) {
					old.close();
				}
				Connection open = new Connection(client);
				connection = open;
				connections++;
				open.start();
				LOGGER.info("Blender connected from {}", client.getRemoteSocketAddress());
			} catch (IOException e) {
				if (!socket.isClosed()) {
					LOGGER.warn("Blender live link accept failed: {}", e.toString());
				}
			}
		}
	}

	// ------------------------------------------------------------------ incoming (reader thread)

	private static void handle(Connection from, JsonObject header, byte[] payload) throws IOException {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		String type = header.get("t").getAsString();
		switch (type) {
			case "hello" -> {
				hello(from, s, header.get("level").getAsString());
				int theirs = header.has("protocol") ? header.get("protocol").getAsInt() : 1;
				if (theirs != PROTOCOL) {
					from.status("Blender add-on is outdated (protocol " + theirs + ", game " + PROTOCOL + "): restart Blender to load the new add-on");
				}
			}
			case "object" -> scene().putObject(header.get("name").getAsString(), payload);
			case "remove" -> scene().removeObject(header.get("name").getAsString());
			case "objects" -> {
				List<String> names = new java.util.ArrayList<>();
				header.getAsJsonArray("names").forEach(name -> names.add(name.getAsString()));
				scene().retain(names);
			}
			case "scene" -> scene().putScene(payload);
			case "commit" -> {
				long started = System.nanoTime();
				LiveScene.Built built = scene().build();
				s.execute(() -> apply(s, built, started));
			}
			case "enter" -> s.execute(() -> enter(s));
			case "camera_off" -> s.execute(() -> hideCamera(s));
			case "camera" -> {
				JsonArray p = header.getAsJsonArray("p");
				double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble(), z = p.get(2).getAsDouble();
				float yaw = header.get("yaw").getAsFloat(), pitch = header.get("pitch").getAsFloat();
				s.execute(() -> camera(s, x, y, z, yaw, pitch));
			}
			case "command" -> {
				// Full-permission commands from any local program: only while developing (the tests drive the game this way).
				if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
					from.status("commands from Blender only work in a development environment");
					return;
				}
				String command = header.get("command").getAsString();
				s.execute(() -> command(s, command));
			}
			default -> from.status("unknown message " + type);
		}
	}

	private static void hello(Connection from, MinecraftServer s, String name) {
		if (!name.matches("[a-z0-9_\\-]+")) {
			from.status("level name must be lowercase letters, digits, _ or -: " + name);
			return;
		}
		int slot = s.submit(() -> LiminalCommands.assignSlot(s, name)).join();
		if (slot < 0) {
			from.status("no free liminal slot for " + name);
			return;
		}
		BlockPos origin = LiminalLevels.originForSlot(slot);
		if (LiveScene.restore(name, origin) == null) {
			LiveScene.open(name, origin);
		}
		level = name;
		playerInside = false;
		from.greeted = true;
		JsonObject reply = message("hello");
		reply.addProperty("protocol", PROTOCOL);
		reply.addProperty("level", name);
		reply.add("origin", ints(origin.getX(), origin.getY(), origin.getZ()));
		from.send(reply, null);
		LOGGER.info("Blender edits liminal level '{}' (slot {})", name, slot);
	}

	private static LiveScene scene() throws IOException {
		String name = level;
		LiveScene scene = name == null ? null : LiveScene.get(name);
		if (scene == null) {
			throw new IOException("no hello yet");
		}
		return scene;
	}

	// ------------------------------------------------------------------ applying (server thread)

	private static void apply(MinecraftServer s, LiveScene.Built built, long started) {
		String name = level;
		LiveScene scene = name == null ? null : LiveScene.get(name);
		if (scene == null) {
			return;
		}
		LiminalMesh old = LiminalLevels.putLive(name, scene.origin(), built);
		ServerLevel world = s.getLevel(LiminalLevels.DIMENSION);
		// A broken cell Blender has since cut out of its geometry is just empty space now: forget it, so whatever
		// Blender puts there later (a new object) shows up instead of staying hidden as "broken".
		dev.blendermc.liminal.LiminalCells cells = LiminalLevels.cells(name);
		it.unimi.dsi.fastutil.longs.LongOpenHashSet broken = LiminalCommands.broken(s, name);
		boolean healed = cells != null && broken.removeIf((long key) -> !cells.hasCell(key));
		if (healed) {
			LiminalCommands.saveBroken(name);
		}
		AABB dirty = old == null ? null : built.dirty();
		int placed = 0;
		if (world != null && playerIn(world, name) != null) {
			// Nobody inside: entering fills the level anyway, and filling would load chunks for nothing.
			AABB previous = old == null ? null : old.bounds();
			placed = LiminalWorld.fill(world, name, dirty == null ? previous : null, dirty, false);
			LiminalLights.apply(world, built.mesh(), dirty == null ? previous : null, dirty);
		} else if (healed) {
			LiminalWorld.broadcast(s, name);
		}
		Connection open = connection;
		if (open != null) {
			JsonObject reply = message("applied");
			reply.addProperty("ms", (System.nanoTime() - started) / 1_000_000);
			reply.addProperty("triangles", built.mesh().triangleCount());
			reply.addProperty("objects", scene.objectCount());
			reply.addProperty("cells", placed);
			reply.addProperty("partial", dirty != null);
			open.send(reply, null);
		}
	}

	/** Blender asked to put the player in the level before anyone had joined: done as soon as someone has. */
	private static boolean enterPending;

	private static void enter(MinecraftServer s) {
		String name = level;
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (name == null || players.isEmpty()) {
			enterPending = name != null;
			return;
		}
		enterPending = false;
		// The server's permission, as the player: putting them in the level works in a world without cheats too.
		s.getCommands().performPrefixedCommand(atPlayer(s, s.createCommandSourceStack()), "blendermc enter " + name);
	}

	private static final String CAMERA_TAG = "blendermc_blender_camera";
	private static boolean cameraShown;

	/**
	 * Blender's viewport camera, shown in the game: a glowing observer (its face looks where Blender looks) with a
	 * "Blender" label, moved smoothly (display entity interpolation). Position in level coordinates (glTF axes).
	 */
	private static void camera(MinecraftServer s, double x, double y, double z, float yaw, float pitch) {
		String name = level;
		BlockPos origin = name == null ? null : LiminalLevels.originOf(name);
		ServerLevel world = s.getLevel(LiminalLevels.DIMENSION);
		if (origin == null || world == null) {
			return;
		}
		double wx = origin.getX() + x, wy = origin.getY() + y, wz = origin.getZ() + z;
		String at = String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", wx, wy, wz);
		boolean exists = !world.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Display.class),
			e -> e.getTags().contains(CAMERA_TAG)).isEmpty();
		String dimension = "execute in " + LiminalLevels.DIMENSION.identifier() + " run ";
		if (!exists) {
			quiet(s, dimension + "summon minecraft:block_display " + at + " {Tags:[\"" + CAMERA_TAG + "\"],block_state:{Name:\"minecraft:observer\","
				+ "Properties:{facing:\"south\"}},Glowing:1b,glow_color_override:16747520,teleport_duration:3,brightness:{sky:15,block:15},"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.25f,-0.25f,-0.25f],scale:[0.5f,0.5f,0.5f]}}");
			quiet(s, dimension + "summon minecraft:text_display " + at + " {Tags:[\"" + CAMERA_TAG + "\",\"" + CAMERA_TAG + "_label\"],"
				+ "text:\"Blender\",billboard:\"center\",teleport_duration:3,see_through:1b,background:1073741824}");
		}
		cameraShown = true;
		quiet(s, dimension + "tp @e[tag=" + CAMERA_TAG + ",tag=!" + CAMERA_TAG + "_label] " + at
			+ String.format(java.util.Locale.ROOT, " %.2f %.2f", yaw, pitch));
		quiet(s, dimension + "tp @e[tag=" + CAMERA_TAG + "_label] "
			+ String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", wx, wy + 0.45, wz));
	}

	private static void hideCamera(MinecraftServer s) {
		if (cameraShown) {
			cameraShown = false;
			quiet(s, "execute in " + LiminalLevels.DIMENSION.identifier() + " run kill @e[tag=" + CAMERA_TAG + "]");
		}
	}

	/** A command from Blender, run with full permission at the first player (or the server when nobody is on). */
	private static void command(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(atPlayer(s, s.createCommandSourceStack()), command);
	}

	/** A command the link runs for itself (the camera marker): no feedback in the chat. */
	private static void quiet(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
	}

	/** {@code source} placed at the first player (as that entity, in their level), when someone is on. */
	private static net.minecraft.commands.CommandSourceStack atPlayer(MinecraftServer s, net.minecraft.commands.CommandSourceStack source) {
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (!players.isEmpty()) {
			ServerPlayer player = players.getFirst();
			source = source.withEntity(player).withLevel(player.level()).withPosition(player.position()).withRotation(player.getRotationVector());
		}
		return source;
	}

	private static void tick(MinecraftServer s) {
		Connection open = connection;
		String name = level;
		if (open == null || name == null || !open.greeted) {
			return;
		}
		if (enterPending && !s.getPlayerList().getPlayers().isEmpty()) {
			enter(s);
		}
		ServerLevel world = s.getLevel(LiminalLevels.DIMENSION);
		ServerPlayer player = world == null ? null : playerIn(world, name);
		if (player != null && !playerInside) {
			playerInside = true;
			snapshot(world, name);
		}
		playerInside = player != null;
		if (++ticks % 2 != 0) {
			return;
		}
		BlockPos origin = LiminalLevels.originOf(name);
		JsonObject pose = message("player");
		pose.addProperty("in", player != null);
		if (player != null && origin != null) {
			pose.add("p", doubles(player.getX() - origin.getX(), player.getY() - origin.getY(), player.getZ() - origin.getZ()));
			pose.addProperty("yaw", player.getYRot());
			pose.addProperty("pitch", player.getXRot());
			pose.addProperty("eye", player.getEyeHeight());
		}
		open.send(pose, null);
	}

	private static @Nullable ServerPlayer playerIn(ServerLevel world, String name) {
		for (ServerPlayer player : world.players()) {
			if (name.equals(LiminalLevels.levelAt(player.getX(), player.getZ()))) {
				return player;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ outgoing (game changes, server thread)

	private static @Nullable Connection linked(@Nullable String name) {
		Connection open = connection;
		return open != null && open.greeted && name != null && name.equals(level) ? open : null;
	}

	/** A level cell was broken off (or put back): a hole in Blender's geometry. */
	public static void cellBroken(String name, BlockPos pos, boolean broken) {
		Connection open = linked(name);
		BlockPos origin = LiminalLevels.originOf(name);
		if (open == null || origin == null) {
			return;
		}
		JsonObject hole = message("hole");
		hole.add("p", local(pos, origin));
		hole.addProperty("on", broken);
		open.send(hole, null);
	}

	/** The holes of {@code name} changed wholesale (fill, restore): send them all. Live levels are hollow, so only broken cells count. */
	public static void holesChanged(MinecraftServer s, String name) {
		Connection open = linked(name);
		BlockPos origin = LiminalLevels.originOf(name);
		if (open == null || origin == null) {
			return;
		}
		JsonArray cells = new JsonArray();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long key : LiminalCommands.broken(s, name)) {
			cells.add(local(pos.set(key), origin));
		}
		JsonObject holes = message("holes");
		holes.add("cells", cells);
		open.send(holes, null);
	}

	/** From the chunk mixin: any block change in the liminal dimension. */
	public static void blockChanged(ServerLevel world, BlockPos pos, BlockState old, BlockState now) {
		String name = LiminalLevels.levelAt(pos);
		Connection open = linked(name);
		BlockPos origin = name == null ? null : LiminalLevels.originOf(name);
		if (open == null || origin == null || LiminalWorld.filling() || now.is(LiminalBlocks.MESH_CELL) || now.is(LiminalBlocks.LIMINAL_STONE)) {
			// A placed piece arrives through piecePlaced once its block entity has it; level cells through cellBroken.
			return;
		}
		boolean free = now.isAir() || now.is(Blocks.LIGHT);
		if (free && (old.isAir() || old.is(Blocks.LIGHT))) {
			return;
		}
		if (free) {
			JsonObject removed = message("unblock");
			removed.add("p", local(pos, origin));
			open.send(removed, null);
			return;
		}
		sendBlock(open, world, pos, origin, now);
	}

	private static void sendBlock(Connection open, ServerLevel world, BlockPos pos, BlockPos origin, BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		JsonObject block = message("block");
		block.add("p", local(pos, origin));
		block.addProperty("id", id);
		block.addProperty("color", state.getMapColor(world, pos).col);
		block.addProperty("light", state.getLightEmission());
		block.addProperty("model", state.toString());
		if (state.getBlock() instanceof net.minecraft.world.level.block.SignBlock signBlock
			&& world.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
			block.add("sign", sign(signBlock, state, sign));
		}
		open.send(block, null);
		BiConsumer<BlockState, BlockPos> models = blockModels;
		if (models != null && open.sent.add("model:" + state)) {
			models.accept(state, pos.immutable());
		}
	}

	/** Changes with every new Blender connection (whatever was sent before has to be sent again). */
	public static int connectionId() {
		return connections;
	}

	/** A sign's shape, wood, facing and both texts, so Blender can build it (signs have no block model). */
	private static JsonObject sign(net.minecraft.world.level.block.SignBlock block, BlockState state, net.minecraft.world.level.block.entity.SignBlockEntity entity) {
		JsonObject sign = new JsonObject();
		String kind = block instanceof net.minecraft.world.level.block.WallSignBlock ? "wall"
			: block instanceof net.minecraft.world.level.block.WallHangingSignBlock ? "wall_hanging"
			: block instanceof net.minecraft.world.level.block.CeilingHangingSignBlock ? "hanging" : "standing";
		sign.addProperty("kind", kind);
		sign.addProperty("wood", net.minecraft.world.level.block.SignBlock.getWoodType(state.getBlock()).name());
		sign.addProperty("yaw", block.getYRotationDegrees(state));
		sign.add("front", signText(entity.getFrontText()));
		sign.add("back", signText(entity.getBackText()));
		return sign;
	}

	private static JsonObject signText(net.minecraft.world.level.block.entity.SignText text) {
		JsonObject side = new JsonObject();
		JsonArray lines = new JsonArray();
		for (int i = 0; i < 4; i++) {
			lines.add(text.getMessage(i, false).getString());
		}
		side.add("lines", lines);
		side.addProperty("color", text.getColor().getTextColor());
		side.addProperty("glow", text.hasGlowingText());
		return side;
	}

	/** From the sign mixin: a sign's text changed (edited, dyed, glow ink): Blender gets the block again. */
	public static void signChanged(ServerLevel world, BlockPos pos) {
		String name = world.dimension() == LiminalLevels.DIMENSION ? LiminalLevels.levelAt(pos) : null;
		Connection open = linked(name);
		BlockPos origin = name == null ? null : LiminalLevels.originOf(name);
		if (open != null && origin != null) {
			sendBlock(open, world, pos, origin, world.getBlockState(pos));
		}
	}

	/** The level the connected Blender edits, or null when none is connected. */
	public static @Nullable String linkedLevel() {
		Connection open = connection;
		return open != null && open.greeted ? level : null;
	}

	/** Sends a frame to the connected Blender (any thread); dropped when none is connected. */
	public static void sendToBlender(JsonObject header, byte @Nullable [] payload) {
		Connection open = connection;
		if (open != null && open.greeted) {
			open.send(header, payload);
		}
	}

	/** True the first time {@code key} is asked about on this connection (textures, models: send each once). */
	public static boolean firstTime(String key) {
		Connection open = connection;
		return open != null && open.sent.add(key);
	}

	/** A mesh piece was placed into a cell: Blender gets its triangles, cell-local, with materials and textures. */
	public static void piecePlaced(ServerLevel world, BlockPos pos, MeshPiece piece) {
		String name = world.dimension() == LiminalLevels.DIMENSION ? LiminalLevels.levelAt(pos) : null;
		Connection open = linked(name);
		BlockPos origin = name == null ? null : LiminalLevels.originOf(name);
		if (open == null || origin == null) {
			return;
		}
		JsonArray materials = new JsonArray();
		for (MeshPiece.PieceMaterial material : piece.materials()) {
			JsonObject m = new JsonObject();
			m.addProperty("name", material.name());
			JsonArray color = new JsonArray();
			for (float c : material.baseColor()) {
				color.add(c);
			}
			m.add("color", color);
			m.addProperty("emissive", material.emissive());
			if (material.texturePng() != null) {
				String key = "png:" + sha1(material.texturePng());
				if (open.sent.add(key)) {
					JsonObject texture = message("texture");
					texture.addProperty("key", key);
					open.send(texture, material.texturePng());
				}
				m.addProperty("tex", key);
			}
			materials.add(m);
		}
		// Per triangle: 9 position, 9 normal, 6 uv floats and the material index, little-endian.
		ByteBuffer data = ByteBuffer.allocate(piece.triangles().size() * 25 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (MeshPiece.Tri tri : piece.triangles()) {
			for (float f : tri.positions()) {
				data.putFloat(f);
			}
			for (float f : tri.normals()) {
				data.putFloat(f);
			}
			for (float f : tri.uvs()) {
				data.putFloat(f);
			}
			data.putFloat(tri.material());
		}
		JsonObject message = message("piece");
		message.add("p", local(pos, origin));
		message.add("materials", materials);
		open.send(message, data.array());
	}

	/** Everything Blender should mirror when the player arrives in the level: placed blocks and pieces, and holes. */
	private static void snapshot(ServerLevel world, String name) {
		Connection open = linked(name);
		BlockPos origin = LiminalLevels.originOf(name);
		LiminalMesh mesh = LiminalLevels.get(name);
		if (open == null || origin == null || mesh == null) {
			return;
		}
		open.send(message("clear_blocks"), null);
		AABB box = mesh.bounds().inflate(1.0);
		long volume = (long) (box.getXsize() + 1) * (long) (box.getYsize() + 1) * (long) (box.getZsize() + 1);
		if (volume <= MAX_SCAN) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					for (int y = Math.max(world.getMinY(), Mth.floor(box.minY)); y <= Math.min(world.getMaxY(), Mth.floor(box.maxY)); y++) {
						pos.set(x, y, z);
						if (!world.isLoaded(pos)) {
							continue;
						}
						BlockState state = world.getBlockState(pos);
						if (state.isAir() || state.is(Blocks.LIGHT) || state.is(LiminalBlocks.LIMINAL_STONE)) {
							continue;
						}
						if (state.is(LiminalBlocks.MESH_CELL)) {
							if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell && cell.piece() != null) {
								piecePlaced(world, pos.immutable(), cell.piece());
							}
							continue;
						}
						sendBlock(open, world, pos.immutable(), origin, state);
					}
				}
			}
		} else {
			open.status("level too big to scan for placed blocks; only new ones are mirrored");
		}
		holesChanged(world.getServer(), name);
	}

	// ------------------------------------------------------------------ helpers

	private static JsonObject message(String type) {
		JsonObject message = new JsonObject();
		message.addProperty("t", type);
		return message;
	}

	private static JsonArray local(BlockPos pos, BlockPos origin) {
		return ints(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
	}

	private static JsonArray ints(int... values) {
		JsonArray array = new JsonArray();
		for (int value : values) {
			array.add(value);
		}
		return array;
	}

	private static JsonArray doubles(double... values) {
		JsonArray array = new JsonArray();
		for (double value : values) {
			array.add(Math.round(value * 1000.0) / 1000.0);
		}
		return array;
	}

	private static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** One connected Blender: a reader thread for its frames and a writer thread draining the send queue. */
	private static final class Connection {
		private static final byte[] EMPTY = new byte[0];
		private final Socket socket;
		private final BlockingQueue<byte[][]> outbox = new LinkedBlockingQueue<>();
		/** What this Blender already has (textures, models): per connection, so a new Blender gets everything again. */
		final Set<String> sent = ConcurrentHashMap.newKeySet();
		/** Set by hello: until then the game does not stream to it (no snapshot before Blender says which level). */
		volatile boolean greeted;
		private volatile boolean closed;

		Connection(Socket socket) {
			this.socket = socket;
		}

		void start() {
			Thread reader = new Thread(this::read, "blendermc-live-read");
			reader.setDaemon(true);
			reader.start();
			Thread writer = new Thread(this::write, "blendermc-live-write");
			writer.setDaemon(true);
			writer.start();
		}

		void send(JsonObject header, byte @Nullable [] payload) {
			if (!this.closed) {
				this.outbox.add(new byte[][] {header.toString().getBytes(StandardCharsets.UTF_8), payload == null ? EMPTY : payload});
			}
		}

		void status(String text) {
			JsonObject status = message("status");
			status.addProperty("text", text);
			this.send(status, null);
		}

		private void read() {
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(this.socket.getInputStream(), 1 << 16))) {
				while (!this.closed) {
					byte[] header = new byte[in.readInt()];
					in.readFully(header);
					byte[] payload = new byte[in.readInt()];
					in.readFully(payload);
					JsonObject json = JsonParser.parseString(new String(header, StandardCharsets.UTF_8)).getAsJsonObject();
					try {
						handle(this, json, payload);
					} catch (IOException | RuntimeException e) {
						LOGGER.warn("Blender message {} failed: {}", json.get("t"), e.toString());
						this.status("game could not use " + json.get("t") + (json.has("name") ? " " + json.get("name") : "") + ": " + e.getMessage());
					}
				}
			} catch (EOFException | java.net.SocketException e) {
				LOGGER.info("Blender disconnected");
			} catch (IOException | RuntimeException e) {
				if (!this.closed) {
					LOGGER.warn("Blender live link closed: {}", e.toString());
				}
			} finally {
				this.close();
			}
		}

		private void write() {
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(this.socket.getOutputStream(), 1 << 16))) {
				while (!this.closed) {
					byte[][] frame = this.outbox.take();
					do {
						out.writeInt(frame[0].length);
						out.write(frame[0]);
						out.writeInt(frame[1].length);
						out.write(frame[1]);
						frame = this.outbox.poll();
					} while (frame != null);
					out.flush();
				}
			} catch (IOException | InterruptedException | RuntimeException e) {
				this.close();
			}
		}

		void close() {
			if (this.closed) {
				return;
			}
			this.closed = true;
			try {
				this.socket.close();
			} catch (IOException ignored) {
			}
			if (connection == this) {
				connection = null;
				MinecraftServer s = server;
				if (s != null) {
					s.execute(() -> hideCamera(s));
				}
			}
		}
	}
}
