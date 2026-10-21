package dev.blendermc.endless.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The endless liminal world as a pure function of a seed. No Minecraft imports: it is used by the server (collision,
 * chunk fill), the client (rendering) and the headless export tool alike, and gives the same answer every time.
 *
 * <p>The world is free-form geometry (see {@link World}, {@link Builder}): irregular rooms with bent and angled walls,
 * arches, columns, multi-storey buildings with spiral stair cores and atria, pools on stepped plinths, and an open field
 * with terraces, pits, ziggurats, monoliths and floating platforms. {@link #generate} cuts it on the 1-block grid for
 * LOD 0 (every triangle belongs to one cell) and simplifies it for LOD 1..4.
 *
 * <p>API: {@link #generate(int, int, int)} (a region at an LOD), {@link #cellTriangles}, {@link #classify},
 * {@link #spawn()}, {@link #zoneAt}.
 */
public final class EndlessGenerator {
	public static final int REGION = 64;
	public static final int MIN_Y = 16, MAX_Y = 176, HEIGHT = MAX_Y - MIN_Y;
	/** Y of the ground floor (feet) in every building; the field meets it there. */
	public static final int FLOOR_Y = 64;
	public static final int MODULE = 8;
	public static final int LODS = 5;
	/** Cell classes. */
	public static final int AIR = 0, SURFACE = 1, SOLID = 2;
	/** Extra bits in {@link Region#cells} bytes: emissive triangles, all three axes covered, and the covered axes. */
	public static final int FLAG_EMISSIVE = 4, FLAG_FULL = 8, FLAG_OCC_X = 16, FLAG_OCC_Y = 32, FLAG_OCC_Z = 64;
	/** Projected area of a cell's faces (both sides together) that counts as covering an axis. */
	private static final float COVER = 1.7F;

	public final long seed;
	final World world;
	final Builder builder;
	private final ConcurrentHashMap<Long, Soup> scenes = new ConcurrentHashMap<>();
	private final AtomicLong sceneTris = new AtomicLong();

	public EndlessGenerator(long seed) {
		this.seed = seed;
		this.world = new World(seed);
		this.builder = new Builder(this.world);
	}

	/** Cell key identical to {@code BlockPos.asLong}. */
	public static long cellKey(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
	}

	public static int keyX(long key) {
		return (int) (key >> 38);
	}

	public static int keyY(long key) {
		return (int) (key << 52 >> 52);
	}

	public static int keyZ(long key) {
		return (int) (key << 26 >> 38);
	}

	/** The triangles of a module (all storeys, own walls, terrain, features), cached. */
	Soup scene(int mx, int mz) {
		long key = (long) mx << 32 ^ (mz & 0xFFFFFFFFL);
		Soup s = this.scenes.get(key);
		if (s == null) {
			s = this.builder.build(mx, mz);
			if (this.sceneTris.get() > 4_000_000L || this.scenes.size() > 6000) {
				this.scenes.clear();
				this.sceneTris.set(0);
			}
			if (this.scenes.putIfAbsent(key, s) == null) {
				this.sceneTris.addAndGet(s.count);
			}
		}
		return s;
	}

	/** The scenes of all modules that can reach the box (xz, with a margin for structures that spill over). */
	List<Soup> scenesNear(double x0, double z0, double x1, double z1) {
		int mx0 = Math.floorDiv((int) Math.floor(x0 - 17), MODULE), mx1 = Math.floorDiv((int) Math.ceil(x1 + 17), MODULE);
		int mz0 = Math.floorDiv((int) Math.floor(z0 - 17), MODULE), mz1 = Math.floorDiv((int) Math.ceil(z1 + 17), MODULE);
		List<Soup> out = new ArrayList<>();
		for (int mz = mz0; mz <= mz1; mz++) {
			for (int mx = mx0; mx <= mx1; mx++) {
				Soup s = this.scene(mx, mz);
				if (s.count > 0 && s.maxX >= x0 && s.minX <= x1 && s.maxZ >= z0 && s.minZ <= z1) {
					out.add(s);
				}
			}
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Public API

	/** Generates region (rx, rz) at {@code lod} 0..4. Thread safe. */
	public Region generate(int rx, int rz, int lod) {
		long start = System.nanoTime();
		return Meshing.region(this, rx, rz, lod, start);
	}

	/** The triangles owned by one cell (full detail, same as LOD 0 would give for it), world coordinates. */
	public CellTris cellTriangles(int x, int y, int z) {
		if (y < MIN_Y || y >= MAX_Y) {
			return CellTris.EMPTY;
		}
		Cutter c = new Cutter(x, y, z, x + 1, y + 1, z + 1);
		for (Soup s : this.scenesNear(x, z, x + 1, z + 1)) {
			if (s.maxY < y || s.minY > y + 1) {
				continue;
			}
			for (int t = 0; t < s.count; t++) {
				if ((s.lod[t] & Soup.L0) != 0) {
					c.cut(s, t);
				}
			}
		}
		if (c.count == 0) {
			return CellTris.EMPTY;
		}
		return new CellTris(java.util.Arrays.copyOf(c.pos, c.count * 9), java.util.Arrays.copyOf(c.nrm, c.count * 9), java.util.Arrays.copyOf(c.uv, c.count * 6),
			java.util.Arrays.copyOf(c.mat, c.count));
	}

	/** AIR, SURFACE (the cell owns triangles) or SOLID (inside the field's ground, no triangles), plus FLAG_* bits. */
	public int classify(int x, int y, int z) {
		if (y < MIN_Y || y >= MAX_Y) {
			return AIR;
		}
		CellTris tris = this.cellTriangles(x, y, z);
		if (!tris.isEmpty()) {
			float[] cover = new float[3];
			int flags = SURFACE;
			for (int t = 0; t < tris.triangleCount(); t++) {
				if (Mats.get(tris.mat()[t]).emissive()) {
					flags |= FLAG_EMISSIVE;
				}
				Meshing.cover(tris.pos(), t * 9, cover);
			}
			return flags | Meshing.occlusion(cover);
		}
		return this.fillAt(x, y, z) ? SOLID : AIR;
	}

	/** Whether the cell (without triangles) lies inside the field's ground and is to be stone-filled. */
	public boolean fillAt(int x, int y, int z) {
		if (y < MIN_Y || y >= MAX_Y) {
			return false;
		}
		double top = this.groundTop(x + 0.5, z + 0.5);
		return !Double.isNaN(top) && y + 0.5 < top - 0.02;
	}

	/** Ground height of a field column, NaN where the column belongs to a building. */
	double groundTop(double x, double z) {
		return this.world.moduleAt(x, z).field() ? this.world.ground(x, z) : Double.NaN;
	}

	/** Whether the point lies inside solid ground (for interior tests of surface cells). */
	public boolean solidAt(double x, double y, double z) {
		double top = this.groundTop(x, z);
		return !Double.isNaN(top) && y < top;
	}

	/** The zone of the macro cell containing the column. */
	public Zone zoneAt(double x, double z) {
		return this.world.macro(Math.floorDiv((int) Math.floor(x), 32), Math.floorDiv((int) Math.floor(z), 32)).zone;
	}

	/** Where the player enters: {x, y, z, yawDegrees} at a street crossing of a backrooms building near the origin. */
	public double[] spawn() {
		for (int r = 0; r < 60; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					World.Macro m = this.world.macro(dx, dz);
					if (m.zone == Zone.BACKROOMS) {
						World.Mod mod = this.world.mod(dx * World.MAC, dz * World.MAC);
						double[] c = this.builder.centre(mod);
						return new double[] {c[0], FLOOR_Y, c[1], 0};
					}
				}
			}
		}
		return new double[] {0.5, FLOOR_Y, 0.5, 0};
	}

	/** Test hook: the world description. */
	World world() {
		return this.world;
	}
}
