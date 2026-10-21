package dev.blendermc.liminal.light;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import net.minecraft.core.BlockPos;

/**
 * Immutable snapshot of what the placed world adds to a level's lighting: block light sources (torches, lanterns,
 * glowstone, light blocks, ...) and vanilla blocks that cast shadows (full blocks and partial collision boxes).
 * Built from per-chunk {@link ChunkData}; handed to background bakes and to the light field.
 */
public final class LightSources {
	public static final LightSources EMPTY = new LightSources(java.util.List.of());
	/** Cell size of the light lookup grid; must be at least the largest light range (15). */
	private static final int GRID = 16;
	private static final int MAX_STEPS = 700;

	/** One chunk's share. Mutable; owned by the thread that builds snapshots. */
	public static final class ChunkData {
		/** Block position to light level 1..15. */
		public final Long2ByteOpenHashMap lights = new Long2ByteOpenHashMap();
		/** Positions of full-cube occluders. */
		public final LongOpenHashSet full = new LongOpenHashSet();
		/** Partial occluders: absolute AABB (minX, minY, minZ, maxX, maxY, maxZ). */
		public final Long2ObjectOpenHashMap<float[]> partial = new Long2ObjectOpenHashMap<>();

		public boolean isEmpty() {
			return this.lights.isEmpty() && this.full.isEmpty() && this.partial.isEmpty();
		}
	}

	private final float[] lx, ly, lz;
	private final byte[] level;
	private final Long2ObjectOpenHashMap<int[]> grid = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet full = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<float[]> partial = new Long2ObjectOpenHashMap<>();
	private final boolean hasOccluders;
	private double minX = 1.0E30, minY = 1.0E30, minZ = 1.0E30, maxX = -1.0E30, maxY = -1.0E30, maxZ = -1.0E30;

	public LightSources(Collection<ChunkData> chunks) {
		int lights = 0;
		for (ChunkData c : chunks) {
			lights += c.lights.size();
		}
		this.lx = new float[lights];
		this.ly = new float[lights];
		this.lz = new float[lights];
		this.level = new byte[lights];
		Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
		int i = 0;
		for (ChunkData c : chunks) {
			for (Long2ByteMap.Entry e : c.lights.long2ByteEntrySet()) {
				long pos = e.getLongKey();
				int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
				this.lx[i] = x + 0.5F;
				this.ly[i] = y + 0.5F;
				this.lz[i] = z + 0.5F;
				this.level[i] = e.getByteValue();
				cells.computeIfAbsent(BlockPos.asLong(Math.floorDiv(x, GRID), Math.floorDiv(y, GRID), Math.floorDiv(z, GRID)), k -> new IntArrayList()).add(i);
				i++;
			}
			this.full.addAll(c.full);
			this.partial.putAll(c.partial);
		}
		for (Long2ObjectOpenHashMap.Entry<IntArrayList> e : cells.long2ObjectEntrySet()) {
			this.grid.put(e.getLongKey(), e.getValue().toIntArray());
		}
		LongIterator it = this.full.iterator();
		while (it.hasNext()) {
			long p = it.nextLong();
			this.include(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), BlockPos.getX(p) + 1, BlockPos.getY(p) + 1, BlockPos.getZ(p) + 1);
		}
		for (float[] b : this.partial.values()) {
			this.include(b[0], b[1], b[2], b[3], b[4], b[5]);
		}
		this.hasOccluders = !this.full.isEmpty() || !this.partial.isEmpty();
	}

	private void include(double x0, double y0, double z0, double x1, double y1, double z1) {
		this.minX = Math.min(this.minX, x0);
		this.minY = Math.min(this.minY, y0);
		this.minZ = Math.min(this.minZ, z0);
		this.maxX = Math.max(this.maxX, x1);
		this.maxY = Math.max(this.maxY, y1);
		this.maxZ = Math.max(this.maxZ, z1);
	}

	public int lightCount() {
		return this.level.length;
	}

	public boolean hasOccluders() {
		return this.hasOccluders;
	}

	/** Indices of lights in the grid cells around a point (3x3x3), appended to {@code out}; query with {@link #lightX} etc. */
	void nearby(double x, double y, double z, IntArrayList out) {
		out.clear();
		if (this.level.length == 0) {
			return;
		}
		int gx = Math.floorDiv((int) Math.floor(x), GRID), gy = Math.floorDiv((int) Math.floor(y), GRID), gz = Math.floorDiv((int) Math.floor(z), GRID);
		for (int ix = gx - 1; ix <= gx + 1; ix++) {
			for (int iy = gy - 1; iy <= gy + 1; iy++) {
				for (int iz = gz - 1; iz <= gz + 1; iz++) {
					int[] list = this.grid.get(BlockPos.asLong(ix, iy, iz));
					if (list != null) {
						out.addElements(out.size(), list);
					}
				}
			}
		}
	}

	/** Strongest placed light at a point with vanilla's falloff (level minus distance), ignoring shadows; 0 when none reaches it. */
	public float placedLevel(double x, double y, double z) {
		IntArrayList near = new IntArrayList();
		this.nearby(x, y, z, near);
		float best = 0.0F;
		for (int k = 0; k < near.size(); k++) {
			int i = near.getInt(k);
			double dx = this.lx[i] - x, dy = this.ly[i] - y, dz = this.lz[i] - z;
			best = Math.max(best, (float) (this.level[i] - Math.sqrt(dx * dx + dy * dy + dz * dz)));
		}
		return best;
	}

	float lightX(int i) {
		return this.lx[i];
	}

	float lightY(int i) {
		return this.ly[i];
	}

	float lightZ(int i) {
		return this.lz[i];
	}

	int lightLevel(int i) {
		return this.level[i];
	}

	/**
	 * Whether a placed block blocks the ray {@code o + d·t}, t in (tmin, tmax). The block the ray starts in is
	 * ignored (a vertex inside a block's cell), and with {@code skipEnd} so is the block of the end point.
	 */
	boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, double tmin, double tmax, boolean skipEnd) {
		if (!this.hasOccluders) {
			return false;
		}
		double t0 = tmin, t1 = tmax;
		double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {this.minX, this.minY, this.minZ}, hi = {this.maxX, this.maxY, this.maxZ};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1.0E-12) {
				if (o[a] < lo[a] || o[a] > hi[a]) {
					return false;
				}
			} else {
				double p = (lo[a] - o[a]) / d[a], q = (hi[a] - o[a]) / d[a];
				t0 = Math.max(t0, Math.min(p, q));
				t1 = Math.min(t1, Math.max(p, q));
				if (t0 > t1) {
					return false;
				}
			}
		}
		int sx = (int) Math.floor(ox), sy = (int) Math.floor(oy), sz = (int) Math.floor(oz);
		long startKey = BlockPos.asLong(sx, sy, sz);
		long endKey = skipEnd ? BlockPos.asLong((int) Math.floor(ox + dx * tmax), (int) Math.floor(oy + dy * tmax), (int) Math.floor(oz + dz * tmax)) : Long.MIN_VALUE;
		double px = ox + dx * t0, py = oy + dy * t0, pz = oz + dz * t0;
		int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double inf = Double.POSITIVE_INFINITY;
		double deltaX = Math.abs(dx) < 1.0E-12 ? inf : Math.abs(1.0 / dx), deltaY = Math.abs(dy) < 1.0E-12 ? inf : Math.abs(1.0 / dy),
			deltaZ = Math.abs(dz) < 1.0E-12 ? inf : Math.abs(1.0 / dz);
		double nextX = Math.abs(dx) < 1.0E-12 ? inf : ((dx > 0 ? x + 1 : x) - ox) / dx;
		double nextY = Math.abs(dy) < 1.0E-12 ? inf : ((dy > 0 ? y + 1 : y) - oy) / dy;
		double nextZ = Math.abs(dz) < 1.0E-12 ? inf : ((dz > 0 ? z + 1 : z) - oz) / dz;
		for (int step = 0; step < MAX_STEPS; step++) {
			long key = BlockPos.asLong(x, y, z);
			if (key != startKey && key != endKey) {
				if (this.full.contains(key)) {
					return true;
				}
				float[] b = this.partial.get(key);
				if (b != null && boxHit(b, ox, oy, oz, dx, dy, dz, tmin, tmax)) {
					return true;
				}
			}
			if (nextX <= nextY && nextX <= nextZ) {
				if (nextX > t1) {
					return false;
				}
				x += stepX;
				nextX += deltaX;
			} else if (nextY <= nextZ) {
				if (nextY > t1) {
					return false;
				}
				y += stepY;
				nextY += deltaY;
			} else {
				if (nextZ > t1) {
					return false;
				}
				z += stepZ;
				nextZ += deltaZ;
			}
		}
		return false;
	}

	private static boolean boxHit(float[] b, double ox, double oy, double oz, double dx, double dy, double dz, double tmin, double tmax) {
		double t0 = tmin, t1 = tmax;
		double[] o = {ox, oy, oz}, d = {dx, dy, dz};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1.0E-12) {
				if (o[a] < b[a] || o[a] > b[3 + a]) {
					return false;
				}
			} else {
				double p = (b[a] - o[a]) / d[a], q = (b[3 + a] - o[a]) / d[a];
				t0 = Math.max(t0, Math.min(p, q));
				t1 = Math.min(t1, Math.max(p, q));
				if (t0 > t1) {
					return false;
				}
			}
		}
		return true;
	}
}
