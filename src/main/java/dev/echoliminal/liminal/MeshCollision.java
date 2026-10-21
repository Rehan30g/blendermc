package dev.echoliminal.liminal;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Collision and ray data for a {@link LiminalMesh}. Triangles (of colliding materials) are bucketed per block cell.
 * For collision each cell is voxelized at 1/16 block into a {@link VoxelShape} (cached), which Minecraft's own
 * per-axis AABB sweep and step-up then treat like any block shape. Triangles are pushed {@link #SURFACE_BIAS} behind
 * their front face before voxelizing, so floors, walls and ceilings on the 1/16 grid collide exactly at the surface;
 * off-grid or slanted surfaces are accurate to 1/16. Thin geometry is a one-voxel-thick slab and solid from both
 * sides. Rays are exact (double-sided triangle tests).
 */
public final class MeshCollision {
	public static final int RES = 16;
	private static final double SURFACE_BIAS = 1.0E-3;
	private static final double VOXEL = 1.0 / RES;

	/** Triangle vertices, 9 doubles each, already pushed back along the face normal (collision). */
	private final double[] biased;
	/** Triangle vertices, 9 doubles each, exact (rays). */
	private final double[] exact;
	/** Unit face normals, 3 doubles each. */
	private final double[] normal;
	private final Long2ObjectMap<int[]> cells;
	private final ConcurrentHashMap<Long, VoxelShape> shapes = new ConcurrentHashMap<>();

	public MeshCollision(LiminalMesh mesh) {
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		int count = mesh.triangleCount();
		IntArrayList kept = new IntArrayList(count);
		double[] n = new double[3];
		for (int t = 0; t < count; t++) {
			int material = mesh.triangleMaterial()[t];
			if (material >= 0 && material < mesh.materials().size() && !mesh.materials().get(material).collides()) {
				continue;
			}
			if (faceNormal(p, idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2], n)) {
				kept.add(t);
			}
		}
		this.biased = new double[kept.size() * 9];
		this.exact = new double[kept.size() * 9];
		this.normal = new double[kept.size() * 3];
		for (int k = 0; k < kept.size(); k++) {
			int t = kept.getInt(k);
			faceNormal(p, idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2], n);
			System.arraycopy(n, 0, this.normal, k * 3, 3);
			for (int v = 0; v < 3; v++) {
				int vi = idx[t * 3 + v] * 3;
				for (int a = 0; a < 3; a++) {
					this.exact[k * 9 + v * 3 + a] = p[vi + a];
					this.biased[k * 9 + v * 3 + a] = p[vi + a] - n[a] * SURFACE_BIAS;
				}
			}
		}

		Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
		double[] tri = new double[9];
		for (int k = 0; k < kept.size(); k++) {
			System.arraycopy(this.biased, k * 9, tri, 0, 9);
			int x0 = Mth.floor(min3(tri, 0) - 2.0E-3), x1 = Mth.floor(max3(tri, 0) + 2.0E-3);
			int y0 = Mth.floor(min3(tri, 1) - 2.0E-3), y1 = Mth.floor(max3(tri, 1) + 2.0E-3);
			int z0 = Mth.floor(min3(tri, 2) - 2.0E-3), z1 = Mth.floor(max3(tri, 2) + 2.0E-3);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						if (triBoxOverlap(tri, x + 0.5, y + 0.5, z + 0.5, 0.5 + 2.0E-3)) {
							buckets.computeIfAbsent(BlockPos.asLong(x, y, z), key -> new IntArrayList()).add(k);
						}
					}
				}
			}
		}
		this.cells = new Long2ObjectOpenHashMap<>(buckets.size());
		buckets.long2ObjectEntrySet().fastForEach(entry -> this.cells.put(entry.getLongKey(), entry.getValue().toIntArray()));
	}

	/** Number of triangles taking part in collision. */
	public int solidTriangles() {
		return this.normal.length / 3;
	}

	/** Whether any colliding triangle touches the block cell. */
	public boolean occupied(int x, int y, int z) {
		return this.cells.containsKey(BlockPos.asLong(x, y, z));
	}

	/** The mesh's collision inside block cell (x, y, z), in cell-local coordinates (0..1), or {@link Shapes#empty()}. */
	public VoxelShape cellShape(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		int[] tris = this.cells.get(key);
		if (tris == null) {
			return Shapes.empty();
		}
		return this.shapes.computeIfAbsent(key, k -> voxelize(this.biased, x, y, z, tris));
	}

	private static VoxelShape voxelize(double[] source, int cx, int cy, int cz, int[] tris) {
		BitSetDiscreteVoxelShape voxels = new BitSetDiscreteVoxelShape(RES, RES, RES);
		boolean any = false;
		double[] tri = new double[9];
		double half = VOXEL / 2 - 1.0E-6;
		for (int k : tris) {
			System.arraycopy(source, k * 9, tri, 0, 9);
			int x0 = voxelIndex(min3(tri, 0) - cx), x1 = voxelIndex(max3(tri, 0) - cx);
			int y0 = voxelIndex(min3(tri, 1) - cy), y1 = voxelIndex(max3(tri, 1) - cy);
			int z0 = voxelIndex(min3(tri, 2) - cz), z1 = voxelIndex(max3(tri, 2) - cz);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						if (!voxels.isFull(x, y, z)
							&& triBoxOverlap(tri, cx + (x + 0.5) * VOXEL, cy + (y + 0.5) * VOXEL, cz + (z + 0.5) * VOXEL, half)) {
							voxels.fill(x, y, z);
							any = true;
						}
					}
				}
			}
		}
		return any ? new MeshCellShape(voxels) : Shapes.empty();
	}

	/**
	 * Voxelizes triangles given in cell-local coordinates (9 floats each, 0..1) the same way as level cells: each
	 * pushed {@link #SURFACE_BIAS} behind its front face, every 1/16 voxel it touches filled. Empty if nothing is left.
	 */
	public static VoxelShape voxelizeLocal(float[] localTriangles) {
		int count = localTriangles.length / 9;
		double[] biased = new double[count * 9];
		int[] tris = new int[count];
		int kept = 0;
		double[] n = new double[3];
		for (int t = 0; t < count; t++) {
			if (!faceNormal(localTriangles, t * 3, t * 3 + 1, t * 3 + 2, n)) {
				continue;
			}
			for (int i = 0; i < 9; i++) {
				biased[kept * 9 + i] = localTriangles[t * 9 + i] - n[i % 3] * SURFACE_BIAS;
			}
			tris[kept] = kept;
			kept++;
		}
		return voxelize(biased, 0, 0, 0, Arrays.copyOf(tris, kept));
	}

	private static int voxelIndex(double local) {
		return Mth.clamp(Mth.floor(local * RES), 0, RES - 1);
	}

	/** A ray hit: point on the surface, unit normal facing the ray's start, distance from the start, triangle index. */
	public record Hit(Vec3 point, Vec3 normal, double distance, int triangle) {
		/** The block face this hit counts as: the normal's dominant axis. */
		public Direction direction() {
			return Direction.getApproximateNearest(this.normal.x, this.normal.y, this.normal.z);
		}

		/** The block cell just in front of the surface along {@link #direction()} (where a placed block goes). */
		public BlockPos cellInFront() {
			Direction d = this.direction();
			return BlockPos.containing(this.point.x + d.getStepX() * 1.0E-4, this.point.y + d.getStepY() * 1.0E-4,
				this.point.z + d.getStepZ() * 1.0E-4);
		}
	}

	/** First mesh surface on the segment {@code from}..{@code to}, or null. Both triangle sides count. */
	public @Nullable Hit hit(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0E-9) {
			return null;
		}
		// Grid walk (Amanatides & Woo) over block cells; each cell holds the triangles touching it.
		int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
		int endX = Mth.floor(to.x), endY = Mth.floor(to.y), endZ = Mth.floor(to.z);
		int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
		double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - from.x : from.x - x) * tDeltaX);
		double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - from.y : from.y - y) * tDeltaY);
		double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - from.z : from.z - z) * tDeltaZ);
		double bestT = Double.MAX_VALUE;
		int best = -1;
		int guard = 0;
		while (guard++ < 4096) {
			int[] tris = this.cells.get(BlockPos.asLong(x, y, z));
			if (tris != null) {
				for (int k : tris) {
					double t = this.rayTriangle(k, from.x, from.y, from.z, dx, dy, dz);
					if (t >= 0 && t <= 1 && t < bestT) {
						bestT = t;
						best = k;
					}
				}
			}
			double cellExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
			if (best >= 0 && bestT <= cellExit) {
				break;
			}
			if (x == endX && y == endY && z == endZ || cellExit > 1) {
				break;
			}
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				x += stepX;
				tMaxX += tDeltaX;
			} else if (tMaxY <= tMaxZ) {
				y += stepY;
				tMaxY += tDeltaY;
			} else {
				z += stepZ;
				tMaxZ += tDeltaZ;
			}
		}
		if (best < 0) {
			return null;
		}
		double nx = this.normal[best * 3], ny = this.normal[best * 3 + 1], nz = this.normal[best * 3 + 2];
		if (nx * dx + ny * dy + nz * dz > 0) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		Vec3 point = new Vec3(from.x + dx * bestT, from.y + dy * bestT, from.z + dz * bestT);
		return new Hit(point, new Vec3(nx, ny, nz), bestT * length, best);
	}

	/** Möller–Trumbore, double-sided; returns the segment parameter or -1. */
	private double rayTriangle(int k, double ox, double oy, double oz, double dx, double dy, double dz) {
		double[] v = this.exact;
		int i = k * 9;
		double e1x = v[i + 3] - v[i], e1y = v[i + 4] - v[i + 1], e1z = v[i + 5] - v[i + 2];
		double e2x = v[i + 6] - v[i], e2y = v[i + 7] - v[i + 1], e2z = v[i + 8] - v[i + 2];
		double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
		double det = e1x * px + e1y * py + e1z * pz;
		if (Math.abs(det) < 1.0E-12) {
			return -1;
		}
		double inv = 1 / det;
		double tx = ox - v[i], ty = oy - v[i + 1], tz = oz - v[i + 2];
		double u = (tx * px + ty * py + tz * pz) * inv;
		if (u < -1.0E-9 || u > 1 + 1.0E-9) {
			return -1;
		}
		double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
		double w = (dx * qx + dy * qy + dz * qz) * inv;
		if (w < -1.0E-9 || u + w > 1 + 1.0E-9) {
			return -1;
		}
		return (e2x * qx + e2y * qy + e2z * qz) * inv;
	}

	private static boolean faceNormal(float[] p, int a, int b, int c, double[] out) {
		a *= 3;
		b *= 3;
		c *= 3;
		double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
		double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0E-10) {
			return false;
		}
		out[0] = nx / len;
		out[1] = ny / len;
		out[2] = nz / len;
		return true;
	}

	private static double min3(double[] tri, int axis) {
		return Math.min(tri[axis], Math.min(tri[3 + axis], tri[6 + axis]));
	}

	private static double max3(double[] tri, int axis) {
		return Math.max(tri[axis], Math.max(tri[3 + axis], tri[6 + axis]));
	}

	/** Triangle vs axis-aligned cube (centre, half size) separating-axis test (Akenine-Möller). */
	static boolean triBoxOverlap(double[] tri, double cx, double cy, double cz, double h) {
		double v0x = tri[0] - cx, v0y = tri[1] - cy, v0z = tri[2] - cz;
		double v1x = tri[3] - cx, v1y = tri[4] - cy, v1z = tri[5] - cz;
		double v2x = tri[6] - cx, v2y = tri[7] - cy, v2z = tri[8] - cz;
		if (Math.min(v0x, Math.min(v1x, v2x)) > h || Math.max(v0x, Math.max(v1x, v2x)) < -h
			|| Math.min(v0y, Math.min(v1y, v2y)) > h || Math.max(v0y, Math.max(v1y, v2y)) < -h
			|| Math.min(v0z, Math.min(v1z, v2z)) > h || Math.max(v0z, Math.max(v1z, v2z)) < -h) {
			return false;
		}
		double e0x = v1x - v0x, e0y = v1y - v0y, e0z = v1z - v0z;
		double e1x = v2x - v1x, e1y = v2y - v1y, e1z = v2z - v1z;
		double e2x = v0x - v2x, e2y = v0y - v2y, e2z = v0z - v2z;
		// Plane of the triangle vs box.
		double nx = e0y * e1z - e0z * e1y, ny = e0z * e1x - e0x * e1z, nz = e0x * e1y - e0y * e1x;
		double d = nx * v0x + ny * v0y + nz * v0z;
		double r = h * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
		if (Math.abs(d) > r) {
			return false;
		}
		// The nine edge cross-product axes.
		double[][] edges = {{e0x, e0y, e0z}, {e1x, e1y, e1z}, {e2x, e2y, e2z}};
		for (double[] e : edges) {
			// axis = X × e, Y × e, Z × e
			if (separated(0, -e[2], e[1], v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
				|| separated(e[2], 0, -e[0], v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
				|| separated(-e[1], e[0], 0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)) {
				return false;
			}
		}
		return true;
	}

	private static boolean separated(double ax, double ay, double az, double v0x, double v0y, double v0z, double v1x, double v1y,
		double v1z, double v2x, double v2y, double v2z, double h) {
		double p0 = ax * v0x + ay * v0y + az * v0z;
		double p1 = ax * v1x + ay * v1y + az * v1z;
		double p2 = ax * v2x + ay * v2y + az * v2z;
		double r = h * (Math.abs(ax) + Math.abs(ay) + Math.abs(az));
		return Math.min(p0, Math.min(p1, p2)) > r || Math.max(p0, Math.max(p1, p2)) < -r;
	}
}
