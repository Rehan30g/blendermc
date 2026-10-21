package dev.echoliminal.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.echoliminal.liminal.GlbLoader;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.LevelSky;
import dev.echoliminal.liminal.LiminalMesh;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A level that lives in a running Blender instead of a .glb file: one parsed piece per Blender object (each sent as
 * its own small .glb, so every naming rule of a level file still holds), plus the scene's settings and spawn. The
 * pieces are grid-split one by one, which gives the same triangles as splitting the whole level at once.
 *
 * <p>What Blender sent is also kept in {@code config/echoliminal/live/<level>/}, so the level is still there when the
 * game starts again without Blender.
 */
public final class LiveScene {
	private static final Map<String, LiveScene> SCENES = new ConcurrentHashMap<>();
	private static final String INDEX = "objects.json";
	private static final String SCENE = "scene.glb";

	public final String level;
	private final BlockPos origin;
	private final Map<String, GlbLoader.Level> pieces = new LinkedHashMap<>();
	private GlbLoader.@Nullable Level scene;
	/** Union of the old and new bounds of everything changed since the last {@link #build()}; null when nothing moved. */
	private @Nullable AABB dirty;
	private boolean settingsChanged = true;
	/** Blender object name to its file in {@link #dir}. */
	private final Map<String, String> files = new LinkedHashMap<>();

	private LiveScene(String level, BlockPos origin) {
		this.level = level;
		this.origin = origin;
	}

	/** Where the live levels are kept between runs. */
	public static Path root() {
		return LiminalLevels.configDir().getParent().resolve("live");
	}

	private Path dir() {
		return root().resolve(this.level);
	}

	/** Names of the live levels saved on disk. */
	public static List<String> saved() {
		if (!Files.isDirectory(root())) {
			return List.of();
		}
		try (Stream<Path> dirs = Files.list(root())) {
			return dirs.filter(dir -> Files.isRegularFile(dir.resolve(INDEX))).map(dir -> dir.getFileName().toString())
				.filter(name -> name.matches("[a-z0-9_\\-]+")).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** The saved live scene of {@code level} at {@code origin} (what Blender last sent), or null if there is none. */
	public static @Nullable LiveScene restore(String level, BlockPos origin) {
		Path dir = root().resolve(level);
		if (!Files.isRegularFile(dir.resolve(INDEX))) {
			return null;
		}
		LiveScene scene = open(level, origin);
		synchronized (scene) {
			if (!scene.pieces.isEmpty()) {
				return scene;
			}
			try {
				JsonObject index = JsonParser.parseString(Files.readString(dir.resolve(INDEX), StandardCharsets.UTF_8)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : index.entrySet()) {
					Path file = dir.resolve(entry.getValue().getAsString());
					if (Files.isRegularFile(file)) {
						scene.pieces.put(entry.getKey(), GlbLoader.loadLevel(level, Files.readAllBytes(file), origin));
						scene.files.put(entry.getKey(), entry.getValue().getAsString());
					}
				}
				if (Files.isRegularFile(dir.resolve(SCENE))) {
					scene.scene = GlbLoader.loadLevel(level, Files.readAllBytes(dir.resolve(SCENE)), origin);
				}
				LiminalLevels.LOGGER.info("live level '{}' restored from {} ({} objects)", level, dir, scene.pieces.size());
			} catch (IOException | RuntimeException e) {
				LiminalLevels.LOGGER.warn("could not restore live level '{}': {}", level, e.toString());
			}
		}
		return scene;
	}

	private void save(@Nullable String name, byte @Nullable [] glb) {
		try {
			Path dir = this.dir();
			Files.createDirectories(dir);
			if (name != null) {
				String old = glb == null ? this.files.remove(name) : null;
				if (old != null) {
					Files.deleteIfExists(dir.resolve(old));
				}
				if (glb != null) {
					String file = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(name.getBytes(StandardCharsets.UTF_8))) + ".glb";
					Files.write(dir.resolve(file), glb);
					this.files.put(name, file);
				}
				JsonObject index = new JsonObject();
				this.files.forEach(index::addProperty);
				Files.writeString(dir.resolve(INDEX), index.toString(), StandardCharsets.UTF_8);
			} else if (glb != null) {
				Files.write(dir.resolve(SCENE), glb);
			}
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			LiminalLevels.LOGGER.warn("could not save live level '{}': {}", this.level, e.toString());
		}
	}

	/** The live scene of {@code level}, or null if Blender never sent it. */
	public static @Nullable LiveScene get(String level) {
		return SCENES.get(level);
	}

	/** Starts (or restarts, when the level moved to another origin) the live scene of {@code level}. */
	public static LiveScene open(String level, BlockPos origin) {
		return SCENES.compute(level, (name, old) -> old != null && old.origin.equals(origin) ? old : new LiveScene(name, origin));
	}

	public static java.util.Set<String> names() {
		return SCENES.keySet();
	}

	public static void closeAll() {
		SCENES.clear();
	}

	public BlockPos origin() {
		return this.origin;
	}

	public synchronized int objectCount() {
		return this.pieces.size();
	}

	/** Replaces the piece of Blender object {@code name} with {@code glb} (one object, world transform already applied). */
	public void putObject(String name, byte[] glb) throws IOException {
		GlbLoader.Level parsed = GlbLoader.loadLevel(this.level, glb, this.origin);
		synchronized (this) {
			GlbLoader.Level old = this.pieces.put(name, parsed);
			this.touch(old);
			this.touch(parsed);
			this.save(name, glb);
		}
	}

	public synchronized void removeObject(String name) {
		this.touch(this.pieces.remove(name));
		this.save(name, null);
	}

	/** Keeps only the objects named in {@code names} (Blender sends its full list after a reconnect or a delete). */
	public synchronized void retain(java.util.Collection<String> names) {
		for (String name : List.copyOf(this.pieces.keySet())) {
			if (!names.contains(name)) {
				this.removeObject(name);
			}
		}
	}

	/** Scene settings and the spawn/sun empties, as a .glb without meshes (scene extras = Blender scene custom properties). */
	public void putScene(byte[] glb) throws IOException {
		GlbLoader.Level parsed = GlbLoader.loadLevel(this.level, glb, this.origin);
		synchronized (this) {
			this.scene = parsed;
			this.settingsChanged = true;
			this.save(null, glb);
		}
	}

	private void touch(GlbLoader.@Nullable Level piece) {
		if (piece == null || piece.mesh().triangleCount() == 0) {
			return;
		}
		AABB box = piece.mesh().bounds();
		this.dirty = this.dirty == null ? box : this.dirty.minmax(box);
	}

	/** What {@link #build()} made: the level, its skybox, settings, and the area whose cells may have changed (null = everything). */
	public record Built(LiminalMesh mesh, @Nullable LiminalMesh sky, LevelSky settings, @Nullable AABB dirty) {
	}

	/** Merges every piece into one level and forgets the dirty area. */
	public synchronized Built build() {
		List<LiminalMesh> meshes = new ArrayList<>();
		List<LiminalMesh> skies = new ArrayList<>();
		for (GlbLoader.Level piece : this.pieces.values()) {
			meshes.add(piece.mesh());
			if (piece.sky() != null) {
				skies.add(piece.sky());
			}
		}
		Vec3 spawn = this.scene != null ? this.scene.mesh().spawn() : Vec3.atBottomCenterOf(this.origin);
		float yaw = this.scene != null ? this.scene.mesh().spawnYaw() : 0.0F;
		LevelSky settings = hollow(this.scene != null ? this.scene.settings() : LevelSky.NONE);
		LiminalMesh mesh = merge(this.level, meshes, spawn, yaw, Vec3.atBottomCenterOf(this.origin));
		LiminalMesh sky = skies.isEmpty() ? null : merge(this.level + "_sky", skies, spawn, yaw, Vec3.ZERO);
		AABB dirty = this.settingsChanged ? null : this.dirty;
		this.dirty = null;
		this.settingsChanged = false;
		return new Built(mesh, sky, settings, dirty);
	}

	/** The lamps Blender placed in {@code level} (Blender lights), empty for other levels. */
	public static List<GlbLoader.LevelLight> lights(String level) {
		LiveScene scene = SCENES.get(level);
		if (scene == null) {
			return List.of();
		}
		synchronized (scene) {
			return scene.scene == null ? List.of() : scene.scene.lights();
		}
	}

	/** Whether {@code level} is a live (Blender) level, in memory or saved: those are always hollow. */
	public static boolean isLive(String level) {
		return SCENES.containsKey(level) || Files.isRegularFile(root().resolve(level).resolve(INDEX));
	}

	/**
	 * Live levels never fill their volume with stone: a broken cell is simply a hole (no stone behind it, no cut faces),
	 * whatever the scene's stone_fill says.
	 */
	private static LevelSky hollow(LevelSky s) {
		return new LevelSky(s.skyColor(), s.fogColor(), s.fogStart(), s.fogEnd(), s.timeOfDay(), s.sunVisible(), s.skylight(), s.sunDirection(),
			false, s.unlit(), s.dayCycle(), s.dayLength());
	}

	/** Concatenates meshes; materials with the same name are shared (Blender material names are unique). */
	static LiminalMesh merge(String name, List<LiminalMesh> parts, Vec3 spawn, float yaw, Vec3 empty) {
		int vertices = 0;
		int triangles = 0;
		for (LiminalMesh part : parts) {
			vertices += part.positions().length / 3;
			triangles += part.triangleCount();
		}
		float[] positions = new float[vertices * 3];
		float[] normals = new float[vertices * 3];
		float[] uvs = new float[vertices * 2];
		int[] indices = new int[triangles * 3];
		int[] triangleMaterial = new int[triangles];
		List<LiminalMesh.Material> materials = new ArrayList<>();
		Map<String, Integer> materialIndex = new HashMap<>();
		AABB bounds = null;
		int v = 0;
		int t = 0;
		for (LiminalMesh part : parts) {
			int[] remap = new int[part.materials().size()];
			for (int m = 0; m < remap.length; m++) {
				LiminalMesh.Material material = part.materials().get(m);
				remap[m] = materialIndex.computeIfAbsent(material.name(), key -> {
					materials.add(material);
					return materials.size() - 1;
				});
			}
			int count = part.positions().length / 3;
			System.arraycopy(part.positions(), 0, positions, v * 3, count * 3);
			System.arraycopy(part.normals(), 0, normals, v * 3, count * 3);
			System.arraycopy(part.uvs(), 0, uvs, v * 2, count * 2);
			int[] partIndices = part.indices();
			for (int i = 0; i < partIndices.length; i++) {
				indices[t * 3 + i] = partIndices[i] + v;
			}
			int[] partMaterials = part.triangleMaterial();
			for (int i = 0; i < partMaterials.length; i++) {
				triangleMaterial[t + i] = remap[partMaterials[i]];
			}
			if (part.triangleCount() > 0) {
				bounds = bounds == null ? part.bounds() : bounds.minmax(part.bounds());
			}
			v += count;
			t += part.triangleCount();
		}
		if (bounds == null) {
			bounds = new AABB(empty, empty);
		}
		return new LiminalMesh(name, positions, normals, uvs, indices, triangleMaterial, List.copyOf(materials), spawn, yaw, bounds);
	}
}
