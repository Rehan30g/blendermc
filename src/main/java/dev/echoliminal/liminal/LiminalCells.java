package dev.echoliminal.liminal;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Which block cell owns each triangle of a level. The loader cuts triangles on the block grid, so each lies in one
 * cell; a triangle exactly on a cell face (a floor at y=64) belongs to the cell behind its front face (y=63), the
 * same cell its collision voxels fill. Cells are keyed by {@link BlockPos#asLong}.
 */
public final class LiminalCells implements CellSource {
	private static final double OWNER_BIAS = 1.0E-3;

	private final LiminalMesh mesh;
	private final boolean stoneFill;
	private final long[] cellOfTriangle;
	private final Long2ObjectMap<int[]> triangles;
	private final ConcurrentHashMap<Long, CellGeometry> geometry = new ConcurrentHashMap<>();

	public LiminalCells(LiminalMesh mesh) {
		this(mesh, true);
	}

	/** {@code stoneFill}: whether the level fills its volume with stone (see {@link LevelSky#stoneFill()}). */
	public LiminalCells(LiminalMesh mesh, boolean stoneFill) {
		this.stoneFill = stoneFill;
		this.mesh = mesh;
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		int count = mesh.triangleCount();
		this.cellOfTriangle = new long[count];
		Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
		for (int t = 0; t < count; t++) {
			int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
			double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
			double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1.0E-12) {
				nx /= len;
				ny /= len;
				nz /= len;
			}
			double cx = (p[a] + p[b] + p[c]) / 3.0 - nx * OWNER_BIAS;
			double cy = (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0 - ny * OWNER_BIAS;
			double cz = (p[a + 2] + p[b + 2] + p[c + 2]) / 3.0 - nz * OWNER_BIAS;
			long cell = BlockPos.asLong(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
			if (!fits(p, a, b, c, cell)) {
				// The bias only decides faces lying on a cell boundary; a thin sliver next to one keeps its own cell.
				cell = BlockPos.asLong(Mth.floor((p[a] + p[b] + p[c]) / 3.0), Mth.floor((p[a + 1] + p[b + 1] + p[c + 1]) / 3.0),
					Mth.floor((p[a + 2] + p[b + 2] + p[c + 2]) / 3.0));
			}
			this.cellOfTriangle[t] = cell;
			buckets.computeIfAbsent(cell, k -> new IntArrayList()).add(t);
		}
		this.triangles = new Long2ObjectOpenHashMap<>(buckets.size());
		buckets.long2ObjectEntrySet().fastForEach(e -> this.triangles.put(e.getLongKey(), e.getValue().toIntArray()));
	}

	private static boolean fits(float[] p, int a, int b, int c, long cell) {
		int[] origin = {BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell)};
		for (int axis = 0; axis < 3; axis++) {
			double min = Math.min(p[a + axis], Math.min(p[b + axis], p[c + axis]));
			double max = Math.max(p[a + axis], Math.max(p[b + axis], p[c + axis]));
			if (min < origin[axis] - 2.0E-4 || max > origin[axis] + 1 + 2.0E-4) {
				return false;
			}
		}
		return true;
	}

	private volatile @Nullable Volume volume;

	/** Which cells of the level are inside it (built on first use, about a second for a big level). */
	public Volume volume() {
		Volume v = this.volume;
		if (v == null) {
			synchronized (this) {
				v = this.volume;
				if (v == null) {
					v = new Volume();
					this.volume = v;
				}
			}
		}
		return v;
	}

	/**
	 * The level as a volume: a point is inside when the first surface straight above it is hit from its back (we are
	 * under the sand, between two rooms' walls). One vertical line per block column (slightly off the centre, away from
	 * the diagonals the grid split makes). Cells without triangles that are inside, from the mesh's lowest point minus
	 * two layers up to its top, are {@link #solidCells()} — the fill puts stone there.
	 */
	public final class Volume {
		private static final double OFF_X = 0.5 + 0.0137, OFF_Z = 0.5 + 0.0291;
		/** Per column (BlockPos.asLong(x, 0, z)): sorted hit heights, and +1/-1 for an up/down-facing surface. */
		private final Long2ObjectOpenHashMap<double[]> heights = new Long2ObjectOpenHashMap<>();
		private final Long2ObjectOpenHashMap<int[]> signs = new Long2ObjectOpenHashMap<>();
		private final LongOpenHashSet solid = new LongOpenHashSet();

		private Volume() {
			float[] p = LiminalCells.this.mesh.positions();
			int[] idx = LiminalCells.this.mesh.indices();
			Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.doubles.DoubleArrayList> hits = new Long2ObjectOpenHashMap<>();
			for (Long2ObjectMap.Entry<int[]> entry : LiminalCells.this.triangles.long2ObjectEntrySet()) {
				long cell = entry.getLongKey();
				double px = BlockPos.getX(cell) + OFF_X, pz = BlockPos.getZ(cell) + OFF_Z;
				for (int t : entry.getValue()) {
					if (LiminalCells.this.mesh.materials().get(LiminalCells.this.mesh.triangleMaterial()[t]).thin()) {
						continue;
					}
					int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
					double det = (p[b] - p[a]) * (p[c + 2] - p[a + 2]) - (p[c] - p[a]) * (p[b + 2] - p[a + 2]);
					if (Math.abs(det) < 1.0E-12) {
						continue;
					}
					double u = ((px - p[a]) * (p[c + 2] - p[a + 2]) - (p[c] - p[a]) * (pz - p[a + 2])) / det;
					double w = ((p[b] - p[a]) * (pz - p[a + 2]) - (px - p[a]) * (p[b + 2] - p[a + 2])) / det;
					if (u < 0 || w < 0 || u + w > 1) {
						continue;
					}
					double y = p[a + 1] + u * (p[b + 1] - p[a + 1]) + w * (p[c + 1] - p[a + 1]);
					// Winding normal's y has the sign of det (x/z cross product).
					double sign = det > 0 ? -1 : 1;
					long column = BlockPos.asLong(BlockPos.getX(cell), 0, BlockPos.getZ(cell));
					it.unimi.dsi.fastutil.doubles.DoubleArrayList list = hits.computeIfAbsent(column, k -> new it.unimi.dsi.fastutil.doubles.DoubleArrayList());
					list.add(y);
					list.add(sign);
				}
			}
			int minY = Mth.floor(LiminalCells.this.mesh.bounds().minY) - 2;
			int maxY = Mth.floor(LiminalCells.this.mesh.bounds().maxY);
			hits.long2ObjectEntrySet().fastForEach(entry -> {
				double[] raw = entry.getValue().toDoubleArray();
				int n = raw.length / 2;
				Integer[] order = new Integer[n];
				for (int i = 0; i < n; i++) {
					order[i] = i;
				}
				java.util.Arrays.sort(order, (i, j) -> Double.compare(raw[i * 2], raw[j * 2]));
				double[] h = new double[n];
				int[] sg = new int[n];
				for (int i = 0; i < n; i++) {
					h[i] = raw[order[i] * 2];
					sg[i] = raw[order[i] * 2 + 1] > 0 ? 1 : -1;
				}
				long column = entry.getLongKey();
				this.heights.put(column, h);
				this.signs.put(column, sg);
				int x = BlockPos.getX(column), z = BlockPos.getZ(column);
				for (int y = minY; y <= maxY; y++) {
					long cell = BlockPos.asLong(x, y, z);
					if (!LiminalCells.this.triangles.containsKey(cell) && this.solidAbove(h, sg, y + 0.5)) {
						this.solid.add(cell);
					}
				}
			});
		}

		private boolean solidAbove(double[] h, int[] sg, double y) {
			for (int i = 0; i < h.length; i++) {
				if (h[i] > y) {
					return sg[i] > 0;
				}
			}
			return false;
		}

		/** Whether the world point is inside the level (see the class comment); false outside every column. */
		public boolean solidAt(double x, double y, double z) {
			long column = BlockPos.asLong(Mth.floor(x), 0, Mth.floor(z));
			double[] h = this.heights.get(column);
			return h != null && this.solidAbove(h, this.signs.get(column), y);
		}

		/** Whether the cell was inside the level originally: it has triangles, or it is a solid cell. */
		public boolean inside(long cell) {
			return this.solid.contains(cell) || LiminalCells.this.triangles.containsKey(cell);
		}

		/** Cells without triangles that are inside the level: the fill makes them stone. Read-only. */
		public LongSet solidCells() {
			return LongSets.unmodifiable(this.solid);
		}
	}

	public LiminalMesh mesh() {
		return this.mesh;
	}

	/** Every cell that owns at least one triangle (read-only). */
	public LongSet cells() {
		return LongSets.unmodifiable(this.triangles.keySet());
	}

	/** Triangle ids owned by the cell, or null. */
	public int @Nullable [] triangles(long cell) {
		return this.triangles.get(cell);
	}

	public int @Nullable [] triangles(BlockPos pos) {
		return this.triangles.get(pos.asLong());
	}

	/** The cell owning triangle {@code t}. */
	public long cellOf(int triangle) {
		return this.cellOfTriangle[triangle];
	}

	/** The cell's exact shapes, support faces and light occlusion (cached); {@link CellGeometry#EMPTY} if it owns nothing. */
	@Override
	public CellGeometry geometry(long cell) {
		int[] tris = this.triangles.get(cell);
		if (tris == null) {
			return CellGeometry.EMPTY;
		}
		int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
		return this.geometry.computeIfAbsent(cell, key -> new CellGeometry(this.localPositions(cell, tris, 0), this.localPositions(cell, tris, 1), this.localPositions(cell, tris, 2),
			(lx, ly, lz) -> this.volume().solidAt(x + lx, y + ly, z + lz), this.stoneFill));
	}

	/** {@code geometry(cell).collision()} or {@code .outline()}: the exact triangle shape, cell-local. */
	public VoxelShape shape(long cell, boolean collidingOnly) {
		CellGeometry g = this.geometry(cell);
		return collidingOnly ? g.collision() : g.outline();
	}

	@Override
	public boolean hasCell(long cell) {
		return this.triangles.containsKey(cell);
	}

	/** The cell's triangles as a {@link MeshPiece} (for the item a broken cell drops). */
	@Override
	public MeshPiece piece(long cell, int facing) {
		int[] tris = this.triangles.get(cell);
		return MeshPiece.fromCell(this.mesh, BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell), tris == null ? new int[0] : tris, facing);
	}

	private float[] localPositions(long cell, int[] tris, int mode) {
		int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
		float[] p = this.mesh.positions();
		int[] idx = this.mesh.indices();
		float[] out = new float[tris.length * 9];
		int n = 0;
		for (int t : tris) {
			LiminalMesh.Material m = this.mesh.materials().get(this.mesh.triangleMaterial()[t]);
			if (mode == 1 ? !m.collides() : mode == 2 && m.thin()) {
				continue;
			}
			for (int v = 0; v < 3; v++) {
				int vi = idx[t * 3 + v] * 3;
				out[n++] = p[vi] - x;
				out[n++] = p[vi + 1] - y;
				out[n++] = p[vi + 2] - z;
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}
}
