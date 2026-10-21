package dev.blendermc.liminal;

import dev.blendermc.BlenderMc;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The liminal levels (.glb meshes) and which one the liminal dimension currently holds. Files in
 * {@code config/blendermc/liminal/<name>.glb} win over the built-in {@code assets/blendermc/liminal/<name>.glb}.
 * Shared by both logical sides; on a client the active name is set by {@link LiminalLevelPayload}.
 */
public final class LiminalLevels {
	/** Origin of slot 0 (also what dev tools use). Slot n sits at x = n * {@link #SPACING}, see {@link #originForSlot}. */
	public static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
	/** Distance between level origins; a level may reach SPACING / 2 from its origin in x and z. A multiple of the 512-block region file. */
	public static final int SPACING = 8192;
	/** Highest slot (keeps coordinates well inside the world border and float precision usable). */
	public static final int MAX_SLOT = 255;
	public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, BlenderMc.id("liminal"));
	public static final String DEFAULT_LEVEL = "hallway";
	public static final Logger LOGGER = LoggerFactory.getLogger("blendermc/liminal");

	private static final Map<String, Optional<Loaded>> CACHE = new ConcurrentHashMap<>();
	/** The level the client is looking at (see {@link #updateView}); the last one told by the server until then. */
	private static volatile String active = DEFAULT_LEVEL;
	private static volatile int generation;
	/** Level name to slot, and back. Persisted by the server, synced to clients. */
	private static volatile Map<String, Integer> slots = Map.of();
	private static volatile Map<Integer, String> bySlot = Map.of();

	private record Loaded(BlockPos origin, LiminalMesh mesh, @Nullable MeshCollision collision, LiminalCells cells, LevelSky sky, @Nullable LiminalMesh skyMesh) {
	}

	private LiminalLevels() {
	}

	/** The level called {@code name}, loaded once and cached; null (and a log line) if it is missing or broken. */
	public static @Nullable LiminalMesh get(String name) {
		Loaded loaded = load(name);
		return loaded == null ? null : loaded.mesh;
	}

	/** Collision/ray data for the level called {@code name}, or null. */
	public static @Nullable MeshCollision collision(String name) {
		Loaded loaded = load(name);
		return loaded == null ? null : loaded.collision;
	}

	/** Which cell owns which triangles of level {@code name}, or null. */
	public static @Nullable LiminalCells cells(String name) {
		Loaded loaded = load(name);
		return loaded == null ? null : loaded.cells;
	}

	/** The geometry source of level {@code name}: the endless generator for "endless", else the loaded .glb's cells; null if missing. */
	public static @Nullable CellSource cellSource(@Nullable String name) {
		if (dev.blendermc.endless.Endless.LEVEL.equals(name)) {
			return dev.blendermc.endless.EndlessCells.get();
		}
		return name == null ? null : cells(name);
	}

	/** Sky settings of level {@code name} ({@link LevelSky#NONE} if missing). */
	public static LevelSky sky(String name) {
		Loaded loaded = load(name);
		return loaded == null ? LevelSky.NONE : loaded.sky;
	}

	public static LevelSky activeSky() {
		return sky(active);
	}

	/** The skybox triangles (materials ending "_sky") of level {@code name}, or null. */
	public static @Nullable LiminalMesh skyMesh(String name) {
		Loaded loaded = load(name);
		return loaded == null ? null : loaded.skyMesh;
	}

	public static @Nullable LiminalMesh activeSkyMesh() {
		return skyMesh(active);
	}

	/** {@link #cells(String)} of the active level. */
	public static @Nullable LiminalCells activeCells() {
		return cells(active);
	}

	private static @Nullable Loaded load(String name) {
		if (name == null || !name.matches("[a-z0-9_\\-]+")) {
			return null;
		}
		BlockPos origin = originOf(name);
		if (origin == null) {
			return null;
		}
		Optional<Loaded> cached = CACHE.get(name);
		if (cached != null && (cached.isEmpty() || cached.get().origin.equals(origin))) {
			return cached.orElse(null);
		}
		Optional<Loaded> read = read(name, origin);
		CACHE.put(name, read);
		return read.orElse(null);
	}

	/** Replaces the slot map (server: loaded/assigned; client: from the payload). Levels whose origin changed are dropped from the cache. */
	public static void setSlots(Map<String, Integer> map) {
		Map<String, Integer> copy = Map.copyOf(map);
		Map<Integer, String> reverse = new java.util.HashMap<>();
		copy.forEach((name, slot) -> reverse.put(slot, name));
		slots = copy;
		bySlot = Map.copyOf(reverse);
		CACHE.entrySet().removeIf(entry -> !copy.containsKey(entry.getKey())
			|| entry.getValue().isPresent() && !entry.getValue().get().origin.equals(originForSlot(copy.get(entry.getKey()))));
	}

	public static Map<String, Integer> slots() {
		return slots;
	}

	/** The slot of level {@code name}, or -1 if it has none (yet). */
	public static int slot(String name) {
		Integer slot = slots.get(name);
		return slot == null ? -1 : slot;
	}

	public static BlockPos originForSlot(int slot) {
		return new BlockPos(slot * SPACING, ORIGIN.getY(), 0);
	}

	/** Where glTF (0,0,0) of level {@code name} sits in the liminal dimension; null if the level has no slot. */
	public static @Nullable BlockPos originOf(String name) {
		int slot = slot(name);
		return slot < 0 ? null : originForSlot(slot);
	}

	/** The level whose region contains column x/z (null for an unassigned region). */
	public static @Nullable String levelAt(double x, double z) {
		if (Math.abs(z) >= SPACING / 2.0) {
			return null;
		}
		int slot = (int) Math.floor((x + SPACING / 2.0) / SPACING);
		return bySlot.get(slot);
	}

	public static @Nullable String levelAt(BlockPos pos) {
		return levelAt(pos.getX() + 0.5, pos.getZ() + 0.5);
	}

	/** Client: the camera is at x/z, so the level of that region is the one to draw (kept as is outside every region). */
	public static void updateView(double x, double z) {
		String name = levelAt(x, z);
		if (name != null) {
			active = name;
		}
	}

	/**
	 * Swaps in a level built by a live Blender link ({@link dev.blendermc.live.LiveScene}). Returns the mesh it replaced
	 * (null if none). Collision is not rebuilt: nothing in the game reads it, only the dev tools.
	 */
	public static @Nullable LiminalMesh putLive(String name, BlockPos origin, dev.blendermc.live.LiveScene.Built built) {
		Optional<Loaded> old = CACHE.put(name, Optional.of(new Loaded(origin, built.mesh(), null,
			new LiminalCells(built.mesh(), built.settings().stoneFill()), built.settings(), built.sky())));
		return old != null && old.isPresent() ? old.get().mesh : null;
	}

	private static Optional<Loaded> read(String name, BlockPos origin) {
		dev.blendermc.live.LiveScene live = dev.blendermc.live.LiveScene.restore(name, origin);
		if (live == null) {
			live = dev.blendermc.live.LiveScene.get(name);
		}
		if (live != null && live.origin().equals(origin)) {
			dev.blendermc.live.LiveScene.Built built = live.build();
			return Optional.of(new Loaded(origin, built.mesh(), null, new LiminalCells(built.mesh(), built.settings().stoneFill()), built.settings(), built.sky()));
		}
		try {
			byte[] data = null;
			String source = null;
			Path file = configDir().resolve(name + ".glb");
			if (Files.isRegularFile(file)) {
				data = Files.readAllBytes(file);
				source = file.toString();
			} else {
				try (InputStream in = LiminalLevels.class.getResourceAsStream("/assets/blendermc/liminal/" + name + ".glb")) {
					if (in != null) {
						data = in.readAllBytes();
						source = "built-in " + name + ".glb";
					}
				}
			}
			if (data == null) {
				LOGGER.warn("liminal level '{}' not found (looked in {} and the mod jar)", name, file);
				return Optional.empty();
			}
			long start = System.nanoTime();
			GlbLoader.Level level = GlbLoader.loadLevel(name, data, origin);
			LiminalMesh mesh = level.mesh();
			MeshCollision collision = new MeshCollision(mesh);
			LOGGER.info("loaded liminal level '{}' from {}: {} triangles, {} materials in {} ms", name, source, mesh.triangleCount(),
				mesh.materials().size(), (System.nanoTime() - start) / 1_000_000);
			return Optional.of(new Loaded(origin, mesh, collision, new LiminalCells(mesh, level.settings().stoneFill()), level.settings(), level.sky()));
		} catch (IOException | RuntimeException e) {
			LOGGER.error("could not load liminal level '{}': {}", name, e.toString());
			return Optional.empty();
		}
	}

	/** Names of every level that exists (config folder and built-in), sorted. */
	public static List<String> available() {
		TreeSet<String> names = new TreeSet<>();
		listGlb(configDir(), names);
		names.addAll(dev.blendermc.live.LiveScene.names());
		names.addAll(dev.blendermc.live.LiveScene.saved());
		FabricLoader.getInstance().getModContainer(BlenderMc.MOD_ID)
			.flatMap(mod -> mod.findPath("assets/blendermc/liminal"))
			.ifPresent(path -> listGlb(path, names));
		return List.copyOf(names);
	}

	private static void listGlb(Path dir, TreeSet<String> names) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.map(path -> path.getFileName().toString())
				.filter(fileName -> fileName.endsWith(".glb"))
				.map(fileName -> fileName.substring(0, fileName.length() - 4))
				.filter(levelName -> levelName.matches("[a-z0-9_\\-]+"))
				.forEach(names::add);
		} catch (IOException e) {
			LOGGER.warn("could not list {}: {}", dir, e.toString());
		}
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("blendermc").resolve("liminal");
	}

	public static String activeName() {
		return active;
	}

	public static void setActive(String name) {
		active = name == null || name.isEmpty() ? DEFAULT_LEVEL : name;
	}

	public static @Nullable LiminalMesh active() {
		return get(active);
	}

	/** Collision of the active level, or null. */
	public static @Nullable MeshCollision activeCollision() {
		return collision(active);
	}


	public static boolean isLiminal(@Nullable Level level) {
		return level != null && level.dimension() == DIMENSION;
	}

	/** Mesh physics and block placement rules apply: the liminal dimension or the endless one. */
	public static boolean hasMeshWorld(@Nullable Level level) {
		return level != null && (level.dimension() == DIMENSION || level.dimension() == dev.blendermc.endless.Endless.DIMENSION);
	}

	/** Drops every cached level so the next access re-reads the .glb files. Bumps {@link #generation()}. */
	public static void reload() {
		CACHE.clear();
		generation++;
	}

	/** Increases on every {@link #reload()}; renderers compare it (or the mesh identity) to rebuild their buffers. */
	public static int generation() {
		return generation;
	}
}
