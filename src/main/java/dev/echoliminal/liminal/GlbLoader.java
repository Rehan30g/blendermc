package dev.echoliminal.liminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Reads a glTF 2.0 binary (.glb) into a {@link LiminalMesh}: every TRIANGLES primitive of every node of the default
 * scene, with node transforms applied, 1 m = 1 block, axes kept (glTF Y up = Minecraft Y up), offset by the origin.
 *
 * <p>The node named "spawn" gives the spawn point; the player looks along that node's local +Z (Blender: local -Y).
 */
public final class GlbLoader {
	private static final int MAGIC = 0x46546C67;
	private static final int CHUNK_JSON = 0x4E4F534A;
	private static final int CHUNK_BIN = 0x004E4942;
	/** Longest triangle edge (blocks) kept as is; longer ones are split, see {@link #subdivide()}. */
	private static final double MAX_EDGE = 2.0;

	private final String name;
	private final JsonObject json;
	private final List<ByteBuffer> buffers = new ArrayList<>();
	private final BlockPos origin;

	private final FloatList positions = new FloatList();
	private final FloatList normals = new FloatList();
	private final FloatList uvs = new FloatList();
	private final IntList indices = new IntList();
	private final IntList triangleMaterial = new IntList();
	private final List<LiminalMesh.Material> materials = new ArrayList<>();
	private int defaultMaterial = -1;
	private Vec3 spawn;
	private float spawnYaw;
	private Vec3 sun;
	private LiminalMesh skyMesh;
	private final List<LevelLight> lights = new ArrayList<>();

	private GlbLoader(String name, byte[] data, BlockPos origin) throws IOException {
		this.name = name;
		this.origin = origin;
		ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (data.length < 20 || in.getInt(0) != MAGIC) {
			throw new IOException("not a .glb file");
		}
		if (in.getInt(4) != 2) {
			throw new IOException("unsupported glTF version " + in.getInt(4));
		}
		int length = Math.min(in.getInt(8), data.length);
		JsonObject parsed = null;
		ByteBuffer bin = null;
		int at = 12;
		while (at + 8 <= length) {
			int chunkLength = in.getInt(at);
			int chunkType = in.getInt(at + 4);
			int start = at + 8;
			if (chunkLength < 0 || start + chunkLength > length) {
				throw new IOException("truncated chunk");
			}
			if (chunkType == CHUNK_JSON && parsed == null) {
				parsed = JsonParser.parseString(new String(data, start, chunkLength, StandardCharsets.UTF_8)).getAsJsonObject();
			} else if (chunkType == CHUNK_BIN && bin == null) {
				bin = ByteBuffer.wrap(data, start, chunkLength).slice().order(ByteOrder.LITTLE_ENDIAN);
			}
			at = start + ((chunkLength + 3) & ~3);
		}
		if (parsed == null) {
			throw new IOException("no JSON chunk");
		}
		this.json = parsed;
		for (JsonElement element : array(parsed, "buffers")) {
			JsonObject buffer = element.getAsJsonObject();
			if (buffer.has("uri")) {
				String uri = buffer.get("uri").getAsString();
				int comma = uri.indexOf(',');
				if (!uri.startsWith("data:") || comma < 0) {
					throw new IOException("external buffer uri not supported: " + uri);
				}
				this.buffers.add(ByteBuffer.wrap(Base64.getDecoder().decode(uri.substring(comma + 1))).order(ByteOrder.LITTLE_ENDIAN));
			} else {
				if (bin == null) {
					throw new IOException("buffer without uri but no BIN chunk");
				}
				this.buffers.add(bin);
			}
		}
	}

	/** Parses {@code data} (the whole .glb file) placing glTF (0,0,0) on {@code origin}. */
	public static LiminalMesh load(String name, byte[] data, BlockPos origin) throws IOException {
		GlbLoader loader = new GlbLoader(name, data, origin);
		try {
			return loader.build();
		} catch (RuntimeException e) {
			throw new IOException("malformed glTF: " + e, e);
		}
	}

	/**
	 * A whole level: the mesh, the skybox mesh (materials ending "_sky", or null), the sky settings and its lamps (nodes
	 * named "mc_light*" with an integer {@code mc_light} extra, the block light level 1-15).
	 */
	public record Level(LiminalMesh mesh, LiminalMesh sky, LevelSky settings, List<LevelLight> lights) {
		public Level(LiminalMesh mesh, LiminalMesh sky, LevelSky settings) {
			this(mesh, sky, settings, List.of());
		}
	}

	/** A lamp of the level: world position (origin applied), block light level and colour (display RGB 0..1, extra {@code mc_color}). */
	public record LevelLight(Vec3 position, int level, float red, float green, float blue) {
	}

	/** Parses a level at {@link LiminalLevels#ORIGIN}: mesh, skybox and {@link LevelSky}. */
	public static Level loadLevel(String name, byte[] data) throws IOException {
		return loadLevel(name, data, LiminalLevels.ORIGIN);
	}

	/** Parses a level with glTF (0,0,0) on {@code origin} (the level's own region, see {@link LiminalLevels#originOf}). */
	public static Level loadLevel(String name, byte[] data, BlockPos origin) throws IOException {
		GlbLoader loader = new GlbLoader(name, data, origin);
		try {
			LiminalMesh mesh = loader.build();
			return new Level(mesh, loader.skyMesh, loader.settings(), List.copyOf(loader.lights));
		} catch (RuntimeException e) {
			throw new IOException("malformed glTF: " + e, e);
		}
	}

	/** {@link #load(String, byte[], BlockPos)} at {@link LiminalLevels#ORIGIN}. */
	public static LiminalMesh load(String name, byte[] data) throws IOException {
		return load(name, data, LiminalLevels.ORIGIN);
	}

	private LiminalMesh build() throws IOException {
		JsonArray materialJson = array(this.json, "materials");
		for (int i = 0; i < materialJson.size(); i++) {
			this.materials.add(this.material(materialJson.get(i).getAsJsonObject(), i));
		}
		JsonArray nodes = array(this.json, "nodes");
		JsonArray scenes = array(this.json, "scenes");
		if (scenes.isEmpty()) {
			for (int i = 0; i < nodes.size(); i++) {
				if (!isChild(nodes, i)) {
					this.visit(nodes, i, identity(), 0);
				}
			}
		} else {
			int scene = this.json.has("scene") ? this.json.get("scene").getAsInt() : 0;
			for (JsonElement root : array(scenes.get(scene).getAsJsonObject(), "nodes")) {
				this.visit(nodes, root.getAsInt(), identity(), 0);
			}
		}

		this.skyMesh = this.extractSky();
		this.gridSplit();
		this.subdivide();
		float[] pos = this.positions.toArray();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < pos.length; i += 3) {
			minX = Math.min(minX, pos[i]);
			minY = Math.min(minY, pos[i + 1]);
			minZ = Math.min(minZ, pos[i + 2]);
			maxX = Math.max(maxX, pos[i]);
			maxY = Math.max(maxY, pos[i + 1]);
			maxZ = Math.max(maxZ, pos[i + 2]);
		}
		AABB bounds = pos.length == 0
			? new AABB(this.origin.getX(), this.origin.getY(), this.origin.getZ(), this.origin.getX(), this.origin.getY(), this.origin.getZ())
			: new AABB(minX, minY, minZ, maxX, maxY, maxZ);
		Vec3 spawnPoint = this.spawn != null ? this.spawn : Vec3.atBottomCenterOf(this.origin);
		return new LiminalMesh(this.name, pos, this.normals.toArray(), this.uvs.toArray(), this.indices.toArray(),
			this.triangleMaterial.toArray(), List.copyOf(this.materials), spawnPoint, this.spawnYaw, bounds);
	}

	/** Scene extras (Blender scene custom properties) and the "sun" node as a {@link LevelSky}. */
	private LevelSky settings() {
		JsonObject extras = null;
		JsonArray scenes = array(this.json, "scenes");
		if (!scenes.isEmpty()) {
			int scene = this.json.has("scene") ? this.json.get("scene").getAsInt() : 0;
			JsonElement e = scenes.get(scene).getAsJsonObject().get("extras");
			extras = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		}
		if (extras == null && this.json.get("extras") instanceof JsonObject root) {
			extras = root;
		}
		if (extras == null) {
			extras = new JsonObject();
		}
		float time = number(extras, "time_of_day");
		Vec3 sunDirection = this.sun != null ? this.sun : Float.isNaN(time) ? null : LevelSky.sunFromTime(time);
		JsonArray sunDir = array(extras, "sun_dir");
		if (sunDir.size() == 3) {
			Vec3 d = new Vec3(sunDir.get(0).getAsDouble(), sunDir.get(1).getAsDouble(), sunDir.get(2).getAsDouble());
			if (d.lengthSqr() > 1.0E-12) {
				sunDirection = d.normalize();
			}
		}
		boolean dayCycle = bool(extras, "day_cycle");
		float dayLength = number(extras, "day_length");
		if (!(dayLength > 0.0F)) {
			dayLength = LevelSky.DEFAULT_DAY_LENGTH;
		}
		if (dayCycle && sunDirection == null) {
			sunDirection = LevelSky.sunFromTime(Float.isNaN(time) ? 3500.0F : time);
		}
		return new LevelSky(color(extras, "sky_color"), color(extras, "fog_color"), number(extras, "fog_start"), number(extras, "fog_end"), time,
			bool(extras, "sun_visible"), bool(extras, "skylight"), sunDirection, !extras.has("stone_fill") || bool(extras, "stone_fill"), bool(extras, "unlit"),
			dayCycle, dayLength);
	}

	private static int color(JsonObject extras, String key) {
		try {
			String value = extras.get(key).getAsString().trim();
			return Integer.parseInt(value.startsWith("#") ? value.substring(1) : value, 16) & 0xFFFFFF;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static float number(JsonObject extras, String key) {
		try {
			return extras.get(key).getAsFloat();
		} catch (RuntimeException e) {
			return Float.NaN;
		}
	}

	/** true for JSON true, non-zero numbers (Blender bool properties export as 0/1) and "true". */
	private static boolean bool(JsonObject extras, String key) {
		JsonElement e = extras.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return false;
		}
		if (e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		if (e.getAsJsonPrimitive().isNumber()) {
			return e.getAsDouble() != 0;
		}
		return e.getAsString().equalsIgnoreCase("true");
	}

	/**
	 * Moves the triangles of materials ending in "_sky" out of the level into their own mesh (same material list),
	 * so the skybox never becomes cells, collision or lights. Null if there are none.
	 */
	private LiminalMesh extractSky() {
		int[] tris = this.indices.toArray();
		int[] mats = this.triangleMaterial.toArray();
		IntList keptIndices = new IntList();
		IntList keptMaterials = new IntList();
		FloatList skyPositions = new FloatList();
		FloatList skyNormals = new FloatList();
		FloatList skyUvs = new FloatList();
		IntList skyIndices = new IntList();
		IntList skyMaterials = new IntList();
		java.util.Map<Integer, Integer> remap = new java.util.HashMap<>();
		for (int t = 0; t < tris.length / 3; t++) {
			if (!this.materials.get(mats[t]).name().toLowerCase(Locale.ROOT).endsWith("_sky")) {
				keptIndices.add(tris[t * 3]);
				keptIndices.add(tris[t * 3 + 1]);
				keptIndices.add(tris[t * 3 + 2]);
				keptMaterials.add(mats[t]);
				continue;
			}
			for (int k = 0; k < 3; k++) {
				int v = tris[t * 3 + k];
				Integer mapped = remap.get(v);
				if (mapped == null) {
					mapped = skyPositions.size() / 3;
					remap.put(v, mapped);
					// Raw glTF coordinates (no origin offset): the skybox is modelled around the eye.
					skyPositions.add(this.positions.data[v * 3] - this.origin.getX());
					skyPositions.add(this.positions.data[v * 3 + 1] - this.origin.getY());
					skyPositions.add(this.positions.data[v * 3 + 2] - this.origin.getZ());
					for (int i = 0; i < 3; i++) {
						skyNormals.add(this.normals.data[v * 3 + i]);
					}
					skyUvs.add(this.uvs.data[v * 2]);
					skyUvs.add(this.uvs.data[v * 2 + 1]);
				}
				skyIndices.add(mapped);
			}
			skyMaterials.add(mats[t]);
		}
		if (skyMaterials.size == 0) {
			return null;
		}
		this.indices.data = keptIndices.data;
		this.indices.size = keptIndices.size;
		this.triangleMaterial.data = keptMaterials.data;
		this.triangleMaterial.size = keptMaterials.size;
		float[] pos = skyPositions.toArray();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < pos.length; i += 3) {
			minX = Math.min(minX, pos[i]);
			minY = Math.min(minY, pos[i + 1]);
			minZ = Math.min(minZ, pos[i + 2]);
			maxX = Math.max(maxX, pos[i]);
			maxY = Math.max(maxY, pos[i + 1]);
			maxZ = Math.max(maxZ, pos[i + 2]);
		}
		Vec3 spawnPoint = this.spawn != null ? this.spawn : Vec3.atBottomCenterOf(this.origin);
		return new LiminalMesh(this.name + "_sky", pos, skyNormals.toArray(), skyUvs.toArray(), skyIndices.toArray(), skyMaterials.toArray(),
			List.copyOf(this.materials), spawnPoint, this.spawnYaw, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
	}

	/** Planes closer than this to a triangle's extent do not cut it (no slivers). */
	private static final double GRID_EPS = 1.0E-4;

	private record EdgeCut(int a, int b, int axis, int plane) {
	}

	/**
	 * Cuts every triangle along the integer planes x=k, y=k, z=k so that each resulting triangle lies inside one block
	 * cell (see {@link LiminalCells}). Cut points are shared per edge and plane, so the surface stays watertight.
	 */
	private void gridSplit() {
		int[] tris = this.indices.toArray();
		int[] mats = this.triangleMaterial.toArray();
		IntList outIndices = new IntList();
		IntList outMaterials = new IntList();
		java.util.Map<EdgeCut, Integer> cuts = new java.util.HashMap<>();
		List<int[]> polygons = new ArrayList<>();
		List<int[]> next = new ArrayList<>();
		for (int t = 0; t < tris.length / 3; t++) {
			polygons.clear();
			polygons.add(new int[] {tris[t * 3], tris[t * 3 + 1], tris[t * 3 + 2]});
			for (int axis = 0; axis < 3; axis++) {
				next.clear();
				for (int[] polygon : polygons) {
					double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
					for (int v : polygon) {
						min = Math.min(min, this.positions.data[v * 3 + axis]);
						max = Math.max(max, this.positions.data[v * 3 + axis]);
					}
					int[] rest = polygon;
					for (int k = (int) Math.ceil(min + GRID_EPS); k < max - GRID_EPS && rest.length >= 3; k++) {
						int[] below = this.clip(rest, axis, k, true, cuts);
						if (below.length >= 3) {
							next.add(below);
						}
						rest = this.clip(rest, axis, k, false, cuts);
					}
					if (rest.length >= 3) {
						next.add(rest);
					}
				}
				List<int[]> swap = polygons;
				polygons = next;
				next = swap;
			}
			for (int[] polygon : polygons) {
				for (int i = 1; i + 1 < polygon.length; i++) {
					if (this.area2(polygon[0], polygon[i], polygon[i + 1]) > 1.0E-12) {
						outIndices.add(polygon[0]);
						outIndices.add(polygon[i]);
						outIndices.add(polygon[i + 1]);
						outMaterials.add(mats[t]);
					}
				}
			}
		}
		this.indices.data = outIndices.data;
		this.indices.size = outIndices.size;
		this.triangleMaterial.data = outMaterials.data;
		this.triangleMaterial.size = outMaterials.size;
	}

	/** Sutherland–Hodgman: the part of convex {@code polygon} on one side of plane axis=k (vertices on the plane kept). */
	private int[] clip(int[] polygon, int axis, int k, boolean below, java.util.Map<EdgeCut, Integer> cuts) {
		IntList out = new IntList();
		int n = polygon.length;
		for (int i = 0; i < n; i++) {
			int cur = polygon[i], nxt = polygon[(i + 1) % n];
			double dc = this.side(cur, axis, k), dn = this.side(nxt, axis, k);
			if (below ? dc <= 0 : dc >= 0) {
				out.add(cur);
			}
			if (dc < 0 && dn > 0 || dc > 0 && dn < 0) {
				out.add(this.cut(cur, nxt, axis, k, cuts));
			}
		}
		return out.toArray();
	}

	private double side(int v, int axis, int k) {
		double d = this.positions.data[v * 3 + axis] - k;
		return Math.abs(d) < 1.0E-6 ? 0 : d;
	}

	private int cut(int a, int b, int axis, int k, java.util.Map<EdgeCut, Integer> cuts) {
		int lo = Math.min(a, b), hi = Math.max(a, b);
		return cuts.computeIfAbsent(new EdgeCut(lo, hi, axis, k), key -> {
			float[] p = this.positions.data;
			double t = (k - p[lo * 3 + axis]) / (double) (p[hi * 3 + axis] - p[lo * 3 + axis]);
			int v = this.interpolate(lo, hi, (float) t);
			this.positions.data[v * 3 + axis] = k;
			return v;
		});
	}

	/** Adds the vertex between a and b at {@code t} (normal renormalized, UV interpolated); returns its index. */
	private int interpolate(int a, int b, float t) {
		int v = this.positions.size() / 3;
		for (int i = 0; i < 3; i++) {
			this.positions.add(this.positions.data[a * 3 + i] + (this.positions.data[b * 3 + i] - this.positions.data[a * 3 + i]) * t);
		}
		float nx = this.normals.data[a * 3] + (this.normals.data[b * 3] - this.normals.data[a * 3]) * t;
		float ny = this.normals.data[a * 3 + 1] + (this.normals.data[b * 3 + 1] - this.normals.data[a * 3 + 1]) * t;
		float nz = this.normals.data[a * 3 + 2] + (this.normals.data[b * 3 + 2] - this.normals.data[a * 3 + 2]) * t;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0E-6F) {
			nx = this.normals.data[a * 3];
			ny = this.normals.data[a * 3 + 1];
			nz = this.normals.data[a * 3 + 2];
			len = 1;
		}
		this.normals.add(nx / len);
		this.normals.add(ny / len);
		this.normals.add(nz / len);
		for (int i = 0; i < 2; i++) {
			this.uvs.add(this.uvs.data[a * 2 + i] + (this.uvs.data[b * 2 + i] - this.uvs.data[a * 2 + i]) * t);
		}
		return v;
	}

	private double area2(int a, int b, int c) {
		float[] p = this.positions.data;
		double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
		double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		return nx * nx + ny * ny + nz * nz;
	}

	/**
	 * Splits every triangle whose longest edge exceeds {@link #MAX_EDGE} at that edge's midpoint (normals and UVs
	 * interpolated) until none does, so big hand-made faces still get per-vertex block lighting. Midpoints are shared
	 * per edge, so neighbours that split the same edge stay connected.
	 */
	private void subdivide() {
		int[] tris = this.indices.toArray();
		int[] mats = this.triangleMaterial.toArray();
		IntList outIndices = new IntList();
		IntList outMaterials = new IntList();
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap midpoints = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		midpoints.defaultReturnValue(-1);
		int[] stack = new int[768];
		for (int t = 0; t < tris.length / 3; t++) {
			int top = 0;
			stack[top++] = tris[t * 3];
			stack[top++] = tris[t * 3 + 1];
			stack[top++] = tris[t * 3 + 2];
			while (top > 0) {
				int c = stack[--top], b = stack[--top], a = stack[--top];
				double ab = this.distanceSqr(a, b), bc = this.distanceSqr(b, c), ca = this.distanceSqr(c, a);
				double longest = Math.max(ab, Math.max(bc, ca));
				if (longest <= MAX_EDGE * MAX_EDGE || top + 6 > stack.length) {
					outIndices.add(a);
					outIndices.add(b);
					outIndices.add(c);
					outMaterials.add(mats[t]);
					continue;
				}
				// Rotate so the longest edge is a-b, then split it at m: (a, m, c) and (m, b, c).
				if (longest == bc) {
					int s = a;
					a = b;
					b = c;
					c = s;
				} else if (longest == ca) {
					int s = a;
					a = c;
					c = b;
					b = s;
				}
				int m = this.midpoint(midpoints, a, b);
				stack[top++] = a;
				stack[top++] = m;
				stack[top++] = c;
				stack[top++] = m;
				stack[top++] = b;
				stack[top++] = c;
			}
		}
		this.indices.data = outIndices.data;
		this.indices.size = outIndices.size;
		this.triangleMaterial.data = outMaterials.data;
		this.triangleMaterial.size = outMaterials.size;
	}

	private double distanceSqr(int a, int b) {
		float[] p = this.positions.data;
		double dx = p[a * 3] - p[b * 3], dy = p[a * 3 + 1] - p[b * 3 + 1], dz = p[a * 3 + 2] - p[b * 3 + 2];
		return dx * dx + dy * dy + dz * dz;
	}

	private int midpoint(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap midpoints, int a, int b) {
		long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
		int existing = midpoints.get(key);
		if (existing >= 0) {
			return existing;
		}
		int m = this.positions.size() / 3;
		for (int i = 0; i < 3; i++) {
			this.positions.add((this.positions.data[a * 3 + i] + this.positions.data[b * 3 + i]) * 0.5F);
		}
		float nx = this.normals.data[a * 3] + this.normals.data[b * 3];
		float ny = this.normals.data[a * 3 + 1] + this.normals.data[b * 3 + 1];
		float nz = this.normals.data[a * 3 + 2] + this.normals.data[b * 3 + 2];
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0E-6F) {
			nx = this.normals.data[a * 3];
			ny = this.normals.data[a * 3 + 1];
			nz = this.normals.data[a * 3 + 2];
			len = 1;
		}
		this.normals.add(nx / len);
		this.normals.add(ny / len);
		this.normals.add(nz / len);
		this.uvs.add((this.uvs.data[a * 2] + this.uvs.data[b * 2]) * 0.5F);
		this.uvs.add((this.uvs.data[a * 2 + 1] + this.uvs.data[b * 2 + 1]) * 0.5F);
		midpoints.put(key, m);
		return m;
	}

	private static boolean isChild(JsonArray nodes, int index) {
		for (JsonElement node : nodes) {
			for (JsonElement child : array(node.getAsJsonObject(), "children")) {
				if (child.getAsInt() == index) {
					return true;
				}
			}
		}
		return false;
	}

	private void visit(JsonArray nodes, int index, double[] parent, int depth) throws IOException {
		if (depth > 64) {
			throw new IOException("node hierarchy too deep (cycle?)");
		}
		JsonObject node = nodes.get(index).getAsJsonObject();
		double[] world = mul(parent, localMatrix(node));
		if (node.has("name") && node.get("name").getAsString().equalsIgnoreCase("spawn")) {
			double[] p = transformPoint(world, 0, 0, 0);
			this.spawn = new Vec3(p[0] + this.origin.getX(), p[1] + this.origin.getY(), p[2] + this.origin.getZ());
			double[] forward = transformDir(world, 0, 0, 1);
			this.spawnYaw = Math.abs(forward[0]) + Math.abs(forward[2]) < 1.0E-6 ? 0.0F
				: (float) Math.toDegrees(Math.atan2(-forward[0], forward[2]));
		}
		if (node.has("name") && node.get("name").getAsString().equalsIgnoreCase("sun")) {
			// Blender's local -Z (the way a sun lamp shines) is glTF local -Y on an exported Empty; a glTF light shines along -Z.
			JsonObject extensions = node.getAsJsonObject("extensions");
			boolean light = extensions != null && extensions.has("KHR_lights_punctual");
			double[] d = transformDir(world, 0, light ? 0 : -1, light ? -1 : 0);
			double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
			if (len > 1.0E-9) {
				this.sun = new Vec3(d[0] / len, d[1] / len, d[2] / len);
			}
		}
		if (node.has("name") && node.get("name").getAsString().toLowerCase(Locale.ROOT).startsWith("mc_light")
			&& node.get("extras") instanceof JsonObject extras && extras.has("mc_light")) {
			double[] p = transformPoint(world, 0, 0, 0);
			int level = Math.clamp(Math.round(extras.get("mc_light").getAsFloat()), 1, 15);
			float[] rgb = {1.0F, 1.0F, 1.0F};
			JsonArray color = array(extras, "mc_color");
			for (int i = 0; i < Math.min(3, color.size()); i++) {
				rgb[i] = Math.clamp(color.get(i).getAsFloat(), 0.0F, 1.0F);
			}
			this.lights.add(new LevelLight(new Vec3(p[0] + this.origin.getX(), p[1] + this.origin.getY(), p[2] + this.origin.getZ()), level,
				rgb[0], rgb[1], rgb[2]));
		}
		if (node.has("mesh")) {
			JsonObject mesh = array(this.json, "meshes").get(node.get("mesh").getAsInt()).getAsJsonObject();
			for (JsonElement primitive : array(mesh, "primitives")) {
				this.primitive(primitive.getAsJsonObject(), world);
			}
		}
		for (JsonElement child : array(node, "children")) {
			this.visit(nodes, child.getAsInt(), world, depth + 1);
		}
	}

	private void primitive(JsonObject primitive, double[] matrix) throws IOException {
		int mode = primitive.has("mode") ? primitive.get("mode").getAsInt() : 4;
		if (mode != 4) {
			return;
		}
		JsonObject attributes = primitive.getAsJsonObject("attributes");
		if (attributes == null || !attributes.has("POSITION")) {
			return;
		}
		float[] pos = this.accessor(attributes.get("POSITION").getAsInt(), 3);
		int vertexCount = pos.length / 3;
		float[] nor = attributes.has("NORMAL") ? this.accessor(attributes.get("NORMAL").getAsInt(), 3) : null;
		float[] uv = attributes.has("TEXCOORD_0") ? this.accessor(attributes.get("TEXCOORD_0").getAsInt(), 2) : null;
		int[] idx;
		if (primitive.has("indices")) {
			float[] raw = this.accessor(primitive.get("indices").getAsInt(), 1);
			idx = new int[raw.length - raw.length % 3];
			for (int i = 0; i < idx.length; i++) {
				idx[i] = (int) raw[i];
			}
		} else {
			idx = new int[vertexCount - vertexCount % 3];
			for (int i = 0; i < idx.length; i++) {
				idx[i] = i;
			}
		}
		int material = primitive.has("material") ? primitive.get("material").getAsInt() : this.defaultMaterial();
		if (material < 0 || material >= this.materials.size()) {
			material = this.defaultMaterial();
		}

		double[] normalMatrix = normalMatrix(matrix);
		boolean mirrored = det3(matrix) < 0;
		int base = this.positions.size() / 3;
		if (nor != null) {
			for (int v = 0; v < vertexCount; v++) {
				this.addVertex(matrix, normalMatrix, pos, nor, uv, v);
			}
		}
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = idx[t], b = idx[mirrored ? t + 2 : t + 1], c = idx[mirrored ? t + 1 : t + 2];
			if (a >= vertexCount || b >= vertexCount || c >= vertexCount) {
				throw new IOException("index out of range");
			}
			if (nor != null) {
				this.indices.add(base + a);
				this.indices.add(base + b);
				this.indices.add(base + c);
			} else {
				// No normals: unshare the vertices so each triangle gets its own flat normal.
				int first = this.positions.size() / 3;
				this.addVertex(matrix, normalMatrix, pos, null, uv, a);
				this.addVertex(matrix, normalMatrix, pos, null, uv, b);
				this.addVertex(matrix, normalMatrix, pos, null, uv, c);
				float[] flat = this.faceNormal(first);
				for (int k = 0; k < 3; k++) {
					this.normals.set((first + k) * 3, flat[0]);
					this.normals.set((first + k) * 3 + 1, flat[1]);
					this.normals.set((first + k) * 3 + 2, flat[2]);
				}
				this.indices.add(first);
				this.indices.add(first + 1);
				this.indices.add(first + 2);
			}
			this.triangleMaterial.add(material);
		}
	}

	private void addVertex(double[] m, double[] nm, float[] pos, float[] nor, float[] uv, int v) {
		double[] p = transformPoint(m, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]);
		this.positions.add((float) (p[0] + this.origin.getX()));
		this.positions.add((float) (p[1] + this.origin.getY()));
		this.positions.add((float) (p[2] + this.origin.getZ()));
		if (nor != null) {
			double nx = nm[0] * nor[v * 3] + nm[3] * nor[v * 3 + 1] + nm[6] * nor[v * 3 + 2];
			double ny = nm[1] * nor[v * 3] + nm[4] * nor[v * 3 + 1] + nm[7] * nor[v * 3 + 2];
			double nz = nm[2] * nor[v * 3] + nm[5] * nor[v * 3 + 1] + nm[8] * nor[v * 3 + 2];
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-12) {
				len = 1;
				ny = 1;
			}
			this.normals.add((float) (nx / len));
			this.normals.add((float) (ny / len));
			this.normals.add((float) (nz / len));
		} else {
			this.normals.add(0);
			this.normals.add(1);
			this.normals.add(0);
		}
		if (uv != null && v * 2 + 1 < uv.length) {
			this.uvs.add(uv[v * 2]);
			this.uvs.add(uv[v * 2 + 1]);
		} else {
			this.uvs.add(0);
			this.uvs.add(0);
		}
	}

	private float[] faceNormal(int first) {
		float[] p = this.positions.data;
		int a = first * 3, b = a + 3, c = a + 6;
		double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
		double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return len < 1.0E-12 ? new float[] {0, 1, 0} : new float[] {(float) (nx / len), (float) (ny / len), (float) (nz / len)};
	}

	private int defaultMaterial() {
		if (this.defaultMaterial < 0) {
			this.defaultMaterial = this.materials.size();
			this.materials.add(new LiminalMesh.Material("default", new float[] {1, 1, 1, 1}, null, false, true));
		}
		return this.defaultMaterial;
	}

	private LiminalMesh.Material material(JsonObject m, int index) {
		String materialName = m.has("name") ? m.get("name").getAsString() : "material" + index;
		float[] color = {1, 1, 1, 1};
		byte[] png = null;
		JsonObject pbr = m.getAsJsonObject("pbrMetallicRoughness");
		if (pbr != null) {
			JsonArray factor = array(pbr, "baseColorFactor");
			for (int i = 0; i < Math.min(4, factor.size()); i++) {
				color[i] = factor.get(i).getAsFloat();
			}
			JsonObject texture = pbr.getAsJsonObject("baseColorTexture");
			if (texture != null && texture.has("index")) {
				png = this.texturePng(texture.get("index").getAsInt());
			}
		}
		boolean emissive = materialName.toLowerCase(Locale.ROOT).contains("light");
		JsonArray emissiveFactor = array(m, "emissiveFactor");
		for (JsonElement e : emissiveFactor) {
			emissive |= e.getAsFloat() > 0;
		}
		JsonObject extensions = m.getAsJsonObject("extensions");
		if (extensions != null) {
			JsonObject strength = extensions.getAsJsonObject("KHR_materials_emissive_strength");
			if (strength != null && strength.has("emissiveStrength") && strength.get("emissiveStrength").getAsFloat() > 0) {
				emissive = true;
			}
			emissive |= extensions.has("KHR_materials_unlit");
		}
		boolean collides = !materialName.toLowerCase(Locale.ROOT).endsWith("_nocollide");
		return new LiminalMesh.Material(materialName, color, png, emissive, collides);
	}

	/** The texture's image as PNG bytes (JPEG and other ImageIO formats are re-encoded), or null. */
	private byte[] texturePng(int textureIndex) {
		try {
			JsonObject texture = array(this.json, "textures").get(textureIndex).getAsJsonObject();
			if (!texture.has("source")) {
				return null;
			}
			JsonObject image = array(this.json, "images").get(texture.get("source").getAsInt()).getAsJsonObject();
			byte[] bytes;
			if (image.has("bufferView")) {
				bytes = this.bufferViewBytes(image.get("bufferView").getAsInt());
			} else if (image.has("uri") && image.get("uri").getAsString().startsWith("data:")) {
				String uri = image.get("uri").getAsString();
				bytes = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
			} else {
				return null;
			}
			if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
				return bytes;
			}
			BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
			if (decoded == null) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(decoded, "png", out);
			return out.toByteArray();
		} catch (IOException | RuntimeException e) {
			LiminalLevels.LOGGER.warn("liminal {}: could not read texture {}: {}", this.name, textureIndex, e.toString());
			return null;
		}
	}

	private byte[] bufferViewBytes(int view) {
		JsonObject bufferView = array(this.json, "bufferViews").get(view).getAsJsonObject();
		ByteBuffer buffer = this.buffers.get(bufferView.has("buffer") ? bufferView.get("buffer").getAsInt() : 0);
		int offset = bufferView.has("byteOffset") ? bufferView.get("byteOffset").getAsInt() : 0;
		byte[] bytes = new byte[bufferView.get("byteLength").getAsInt()];
		buffer.get(offset, bytes);
		return bytes;
	}

	/** Reads an accessor as floats ({@code components} per element), applying normalization for integer types. */
	private float[] accessor(int index, int components) throws IOException {
		JsonObject accessor = array(this.json, "accessors").get(index).getAsJsonObject();
		int count = accessor.get("count").getAsInt();
		int componentType = accessor.get("componentType").getAsInt();
		boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
		int typeComponents = switch (accessor.get("type").getAsString()) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4" -> 4;
			default -> throw new IOException("unsupported accessor type " + accessor.get("type").getAsString());
		};
		if (typeComponents < components) {
			throw new IOException("accessor " + index + " has " + typeComponents + " components, need " + components);
		}
		int size = switch (componentType) {
			case 5120, 5121 -> 1;
			case 5122, 5123 -> 2;
			case 5125, 5126 -> 4;
			default -> throw new IOException("unsupported component type " + componentType);
		};
		float[] out = new float[count * components];
		if (!accessor.has("bufferView")) {
			return out;
		}
		JsonObject view = array(this.json, "bufferViews").get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
		ByteBuffer buffer = this.buffers.get(view.has("buffer") ? view.get("buffer").getAsInt() : 0);
		int base = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
			+ (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
		int stride = view.has("byteStride") && view.get("byteStride").getAsInt() > 0 ? view.get("byteStride").getAsInt() : size * typeComponents;
		if (count > 0 && base + (long) stride * (count - 1) + (long) size * typeComponents > buffer.limit()) {
			throw new IOException("accessor " + index + " runs past its buffer");
		}
		for (int i = 0; i < count; i++) {
			int at = base + i * stride;
			for (int c = 0; c < components; c++) {
				int p = at + c * size;
				out[i * components + c] = switch (componentType) {
					case 5126 -> buffer.getFloat(p);
					case 5125 -> (float) Integer.toUnsignedLong(buffer.getInt(p));
					case 5123 -> normalized ? (buffer.getShort(p) & 0xFFFF) / 65535.0F : buffer.getShort(p) & 0xFFFF;
					case 5122 -> normalized ? Math.max(buffer.getShort(p) / 32767.0F, -1.0F) : buffer.getShort(p);
					case 5121 -> normalized ? (buffer.get(p) & 0xFF) / 255.0F : buffer.get(p) & 0xFF;
					default -> normalized ? Math.max(buffer.get(p) / 127.0F, -1.0F) : buffer.get(p);
				};
			}
		}
		return out;
	}

	private static JsonArray array(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	// Column-major 4x4 matrices, as in glTF.

	private static double[] identity() {
		return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	private static double[] localMatrix(JsonObject node) {
		JsonArray matrix = array(node, "matrix");
		if (matrix.size() == 16) {
			double[] m = new double[16];
			for (int i = 0; i < 16; i++) {
				m[i] = matrix.get(i).getAsDouble();
			}
			return m;
		}
		double[] t = vec(array(node, "translation"), new double[] {0, 0, 0});
		double[] r = vec(array(node, "rotation"), new double[] {0, 0, 0, 1});
		double[] s = vec(array(node, "scale"), new double[] {1, 1, 1});
		double x = r[0], y = r[1], z = r[2], w = r[3];
		double len = Math.sqrt(x * x + y * y + z * z + w * w);
		if (len > 1.0E-12) {
			x /= len;
			y /= len;
			z /= len;
			w /= len;
		}
		return new double[] {
			(1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0,
			(2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0,
			(2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
			t[0], t[1], t[2], 1
		};
	}

	private static double[] vec(JsonArray array, double[] fallback) {
		if (array.size() != fallback.length) {
			return fallback;
		}
		double[] out = new double[fallback.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsDouble();
		}
		return out;
	}

	private static double[] mul(double[] a, double[] b) {
		double[] out = new double[16];
		for (int col = 0; col < 4; col++) {
			for (int row = 0; row < 4; row++) {
				double sum = 0;
				for (int k = 0; k < 4; k++) {
					sum += a[k * 4 + row] * b[col * 4 + k];
				}
				out[col * 4 + row] = sum;
			}
		}
		return out;
	}

	private static double[] transformPoint(double[] m, double x, double y, double z) {
		return new double[] {
			m[0] * x + m[4] * y + m[8] * z + m[12],
			m[1] * x + m[5] * y + m[9] * z + m[13],
			m[2] * x + m[6] * y + m[10] * z + m[14]
		};
	}

	private static double[] transformDir(double[] m, double x, double y, double z) {
		return new double[] {m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z};
	}

	private static double det3(double[] m) {
		return m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
	}

	/** Inverse transpose of the upper 3x3, column-major (3x3). */
	private static double[] normalMatrix(double[] m) {
		double a = m[0], b = m[4], c = m[8];
		double d = m[1], e = m[5], f = m[9];
		double g = m[2], h = m[6], i = m[10];
		double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		if (Math.abs(det) < 1.0E-20) {
			return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
		}
		double inv = 1.0 / det;
		// Cofactor matrix / det = inverse transpose; stored column-major.
		return new double[] {
			(e * i - f * h) * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
			-(d * i - f * g) * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
			(d * h - e * g) * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv
		};
	}

	private static final class FloatList {
		float[] data = new float[1024];
		int size;

		void add(float v) {
			if (this.size == this.data.length) {
				this.data = java.util.Arrays.copyOf(this.data, this.size * 2);
			}
			this.data[this.size++] = v;
		}

		void set(int i, float v) {
			this.data[i] = v;
		}

		int size() {
			return this.size;
		}

		float[] toArray() {
			return java.util.Arrays.copyOf(this.data, this.size);
		}
	}

	private static final class IntList {
		int[] data = new int[1024];
		int size;

		void add(int v) {
			if (this.size == this.data.length) {
				this.data = java.util.Arrays.copyOf(this.data, this.size * 2);
			}
			this.data[this.size++] = v;
		}

		int[] toArray() {
			return java.util.Arrays.copyOf(this.data, this.size);
		}
	}
}
