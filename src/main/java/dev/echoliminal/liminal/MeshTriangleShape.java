package dev.echoliminal.liminal;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A mesh_cell's exact geometry as a VoxelShape. To vanilla's voxel code it looks like the whole cell (so bounds,
 * joins and face checks are a conservative superset), but the two places that matter are exact: {@link #collide}
 * (vanilla's per-axis movement clamp, used by every entity, step-up, the server's move check and client prediction)
 * sweeps the box against the triangles, and {@link #clip} hits the triangles themselves. {@link #intersects} is the
 * exact box overlap test the collision iterator uses to decide whether this cell touches a box at all.
 *
 * <p>Triangles are double-sided, 9 floats each in cell-local coordinates, placed at offset (ox, oy, oz).
 */
public final class MeshTriangleShape extends VoxelShape {
	private static final double EPS = 1.0E-7;
	private static final DiscreteVoxelShape FULL;

	static {
		BitSetDiscreteVoxelShape full = new BitSetDiscreteVoxelShape(1, 1, 1);
		full.fill(0, 0, 0);
		FULL = full;
	}

	/** Depths behind each triangle's front face that count as solid for overlap tests (see {@link #intersects}). */
	private static final double[] BACK_DEPTHS = {0.0, 1.0 / 16.0, 1.0 / 8.0};

	private static final double[] NO_DEPTH = {0.0};
	/** Down-facing surfaces (stood on from their back side) also count solid in front of them for ground probes, see {@link #overlap}. */
	private static final double[] BOTH_SIDES = {-1.0 / 8.0, -1.0 / 16.0, 0.0, 1.0 / 16.0, 1.0 / 8.0};

	private final float[] triangles;
	/** Unit front-face normals (winding), 3 per triangle. */
	private final float[] normals;
	private final double ox, oy, oz;
	private final DoubleList xs, ys, zs;
	/** The solid region behind the surface (cell-local voxels, see {@link CellInterior}), or empty. */
	private final VoxelShape interior;
	private @Nullable VoxelShape movedInterior;
	/** Cell-local triangles that have a solid behind them (not thin): what {@link #pushOut} resolves against, or null. */
	float @Nullable [] pushable;
	/** Extra solid for rays (cell-local), or null; see {@link CellGeometry#rayVolume()}. */
	private final java.util.function.@Nullable Supplier<VoxelShape> rayVolume;

	public MeshTriangleShape(float[] triangles) {
		this(triangles, net.minecraft.world.phys.shapes.Shapes.empty());
	}

	public MeshTriangleShape(float[] triangles, VoxelShape interior) {
		this(triangles, interior, null);
	}

	public MeshTriangleShape(float[] triangles, VoxelShape interior, java.util.function.@Nullable Supplier<VoxelShape> rayVolume) {
		this(triangles, normals(triangles), interior, rayVolume, 0, 0, 0);
	}

	private MeshTriangleShape(float[] triangles, float[] normals, VoxelShape interior, java.util.function.@Nullable Supplier<VoxelShape> rayVolume,
		double ox, double oy, double oz) {
		super(FULL);
		this.rayVolume = rayVolume;
		this.triangles = triangles;
		this.normals = normals;
		this.interior = interior;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.xs = DoubleArrayList.of(ox, ox + 1);
		this.ys = DoubleArrayList.of(oy, oy + 1);
		this.zs = DoubleArrayList.of(oz, oz + 1);
	}

	/** Cell-local triangles (9 floats each); add {@link #offset()} for the shape's position. */
	public float[] triangles() {
		return this.triangles;
	}

	public Vec3 offset() {
		return new Vec3(this.ox, this.oy, this.oz);
	}

	@Override
	public DoubleList getCoords(Direction.Axis axis) {
		return switch (axis) {
			case X -> this.xs;
			case Y -> this.ys;
			case Z -> this.zs;
		};
	}

	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public VoxelShape move(double dx, double dy, double dz) {
		MeshTriangleShape moved = new MeshTriangleShape(this.triangles, this.normals, this.interior, this.rayVolume, this.ox + dx, this.oy + dy, this.oz + dz);
		moved.pushable = this.pushable;
		return moved;
	}

	/**
	 * How far the box can move along {@code axis} (wanted {@code d}) before touching a triangle. Each triangle is clipped
	 * to the box's cross-section on the other two axes (inset by 1e-7 like vanilla); the clipped polygon's extent along
	 * the axis against the box's leading face gives the distance. Triangles the box already cuts into are ignored.
	 */
	/** The interior voxels at this shape's position (empty if none). */
	private VoxelShape interior() {
		if (this.movedInterior == null) {
			this.movedInterior = this.interior.isEmpty() ? this.interior : this.interior.move(this.ox, this.oy, this.oz);
		}
		return this.movedInterior;
	}

	/** Set by {@link LiminalPhysics#collide} while the moving box already sits in some cell's interior: interiors then only hold up. */
	private static final ThreadLocal<Boolean> ESCAPING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	static boolean setEscaping(boolean escaping) {
		boolean before = ESCAPING.get();
		ESCAPING.set(escaping);
		return before;
	}

	/** Whether the inside of {@code box} overlaps this cell's interior volume (touching does not count). */
	boolean overlapsInterior(AABB box) {
		VoxelShape inside = this.interior();
		return !inside.isEmpty() && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(inside,
			net.minecraft.world.phys.shapes.Shapes.create(box.deflate(EPS)), net.minecraft.world.phys.shapes.BooleanOp.AND);
	}

	/**
	 * The interior only exists for what got sunk into it. A box that already overlaps an interior (standing on the back
	 * side of a surface, or inside a restored cell) ignores it while moving, like vanilla ignores shapes it starts in,
	 * so it can always walk, jump and climb out; only a box whose bottom centre is inside the volume is held up from below.
	 */
	@Override
	public double collide(Direction.Axis axis, AABB box, double d) {
		if (Math.abs(d) < EPS) {
			return 0.0;
		}
		VoxelShape inside = this.interior();
		if (!inside.isEmpty()) {
			boolean hold = axis == Direction.Axis.Y && d < 0 && this.overlapsInterior(new AABB((box.minX + box.maxX) / 2 - 1.0E-4, box.minY,
				(box.minZ + box.maxZ) / 2 - 1.0E-4, (box.minX + box.maxX) / 2 + 1.0E-4, box.minY + 3.0E-4, (box.minZ + box.maxZ) / 2 + 1.0E-4));
			if (hold || !ESCAPING.get() && !this.overlapsInterior(box)) {
				d = inside.collide(axis, box, d);
			}
		}
		return this.collideTriangles(axis, box, d);
	}

	private double collideTriangles(Direction.Axis axis, AABB box, double d) {
		if (Math.abs(d) < EPS) {
			return d;
		}
		int a = axis.ordinal(), b = (a + 1) % 3, c = (a + 2) % 3;
		double[] lo = {box.minX, box.minY, box.minZ};
		double[] hi = {box.maxX, box.maxY, box.maxZ};
		double[] off = {this.ox, this.oy, this.oz};
		// Whole-cell reject: the cell must overlap the box's cross-section and lie on the moving side.
		if (off[b] + 1 <= lo[b] + EPS || off[b] >= hi[b] - EPS || off[c] + 1 <= lo[c] + EPS || off[c] >= hi[c] - EPS) {
			return d;
		}
		if (d > 0 ? off[a] + 1 < hi[a] - EPS : off[a] > lo[a] + EPS) {
			return d;
		}
		double[] poly = new double[3 * 8];
		double[] tmp = new double[3 * 8];
		for (int t = 0; t < this.triangles.length; t += 9) {
			int n = 3;
			for (int v = 0; v < 3; v++) {
				poly[v * 3] = this.triangles[t + v * 3] + this.ox;
				poly[v * 3 + 1] = this.triangles[t + v * 3 + 1] + this.oy;
				poly[v * 3 + 2] = this.triangles[t + v * 3 + 2] + this.oz;
			}
			n = clip(poly, tmp, n, b, lo[b] + EPS, true);
			n = clip(tmp, poly, n, b, hi[b] - EPS, false);
			n = clip(poly, tmp, n, c, lo[c] + EPS, true);
			n = clip(tmp, poly, n, c, hi[c] - EPS, false);
			if (n == 0) {
				continue;
			}
			double pmin = Double.MAX_VALUE, pmax = -Double.MAX_VALUE;
			for (int v = 0; v < n; v++) {
				pmin = Math.min(pmin, poly[v * 3 + a]);
				pmax = Math.max(pmax, poly[v * 3 + a]);
			}
			if (d > 0) {
				if (pmin >= hi[a] - EPS) {
					d = Math.min(d, pmin - hi[a]);
				}
			} else if (pmax <= lo[a] + EPS) {
				d = Math.max(d, pmax - lo[a]);
			}
		}
		return d;
	}

	/** Sutherland–Hodgman against one axis plane: keeps the side {@code >= plane} (keepAbove) or {@code <= plane}. */
	private static int clip(double[] in, double[] out, int n, int axis, double plane, boolean keepAbove) {
		if (n == 0) {
			return 0;
		}
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			double di = keepAbove ? in[i * 3 + axis] - plane : plane - in[i * 3 + axis];
			double dj = keepAbove ? in[j * 3 + axis] - plane : plane - in[j * 3 + axis];
			if (di >= 0) {
				out[m * 3] = in[i * 3];
				out[m * 3 + 1] = in[i * 3 + 1];
				out[m * 3 + 2] = in[i * 3 + 2];
				m++;
			}
			if (di >= 0 != dj >= 0 && m < 8) {
				double t = di / (di - dj);
				for (int k = 0; k < 3; k++) {
					out[m * 3 + k] = in[i * 3 + k] + (in[j * 3 + k] - in[i * 3 + k]) * t;
				}
				out[m * 3 + axis] = plane;
				m++;
			}
		}
		return m;
	}

	private static float[] normals(float[] t) {
		float[] out = new float[t.length / 3];
		for (int i = 0, n = 0; i < t.length; i += 9, n += 3) {
			double ux = t[i + 3] - t[i], uy = t[i + 4] - t[i + 1], uz = t[i + 5] - t[i + 2];
			double vx = t[i + 6] - t[i], vy = t[i + 7] - t[i + 1], vz = t[i + 8] - t[i + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1.0E-12) {
				out[n] = (float) (nx / len);
				out[n + 1] = (float) (ny / len);
				out[n + 2] = (float) (nz / len);
			}
		}
		return out;
	}

	/**
	 * Whether the inside of {@code box} (touching does not count) overlaps a triangle or the thin solid behind its front
	 * face (sampled at a few depths up to 2/16 block). A surface has no volume, yet vanilla asks "is there ground under
	 * this box" with a box whose top only touches the floor (sneak edge back-off, fall checks); the solid behind the
	 * face answers that like a block would, while a box resting on the front side still does not collide.
	 */
	public boolean intersects(AABB box) {
		return this.overlap(box, true);
	}

	/**
	 * Whether the inside of {@code box} cuts a triangle itself (no solid behind it, no interior): the contact the server's
	 * move check must refuse. The thin solid behind a face would make a box standing on a surface's back side "collide"
	 * with every cell it walks into, and the server would pull the player back (and reset the camera) each tick.
	 */
	public boolean touchesSurface(AABB box) {
		return this.overlap(box, false);
	}

	private boolean overlap(AABB box, boolean volumes) {
		double hx = (box.maxX - box.minX) / 2 - EPS, hy = (box.maxY - box.minY) / 2 - EPS, hz = (box.maxZ - box.minZ) / 2 - EPS;
		if (hx <= 0 || hy <= 0 || hz <= 0) {
			return false;
		}
		double cx = (box.minX + box.maxX) / 2 - this.ox, cy = (box.minY + box.maxY) / 2 - this.oy, cz = (box.minZ + box.maxZ) / 2 - this.oz;
		if (volumes && this.overlapsInterior(box)) {
			return true;
		}
		double margin = volumes ? 0.125 : 0.0;
		if (cx + hx <= -margin || cx - hx >= 1 + margin || cy + hy <= -margin || cy - hy >= 1 + margin || cz + hz <= -margin
			|| cz - hz >= 1 + margin) {
			return false;
		}
		// Sneaking asks "is there ground" with a player-wide, step-high (0.6) box under the feet. On the back side of a floor
		// (front normal down) that box lies in front of the face, so such probes get the solid on that side too.
		boolean probe = volumes && Math.abs((box.maxY - box.minY) - 0.6) < 0.02;
		for (int t = 0, n = 0; t < this.triangles.length; t += 9, n += 3) {
			for (double depth : !volumes ? NO_DEPTH : probe && this.normals[n + 1] < -0.5 ? BOTH_SIDES : BACK_DEPTHS) {
				// Move the box forward along the normal instead of the triangle back.
				double fx = this.normals[n] * depth, fy = this.normals[n + 1] * depth, fz = this.normals[n + 2] * depth;
				if (triBoxOverlap(this.triangles, t, cx + fx, cy + fy, cz + fz, hx, hy, hz)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * How to get a box out that sank BEHIND a surface: it cuts a non-thin triangle while its feet point lies behind that
	 * triangle's plane (up to a block). Returns the offset that puts the box on the front side (straight up for floors,
	 * along the normal otherwise), or null. A box held by a deep interior volume (stone-backed ground) is left to that
	 * volume, and a box merely standing on a back face does not cut the triangle, so it is never touched.
	 */
	public @Nullable Vec3 pushOut(AABB box) {
		float[] tris = this.pushable;
		if (tris == null || tris.length == 0) {
			return null;
		}
		double hx = (box.maxX - box.minX) / 2 - EPS, hy = (box.maxY - box.minY) / 2 - EPS, hz = (box.maxZ - box.minZ) / 2 - EPS;
		if (hx <= 0 || hy <= 0 || hz <= 0) {
			return null;
		}
		double cx = (box.minX + box.maxX) / 2 - this.ox, cy = (box.minY + box.maxY) / 2 - this.oy, cz = (box.minZ + box.maxZ) / 2 - this.oz;
		double fx = cx, fy = cy - hy - EPS, fz = cz;
		double best = 0;
		double[] bestN = null;
		for (int t = 0; t < tris.length; t += 9) {
			if (!triBoxOverlap(tris, t, cx, cy, cz, hx, hy, hz)) {
				continue;
			}
			double ux = tris[t + 3] - tris[t], uy = tris[t + 4] - tris[t + 1], uz = tris[t + 5] - tris[t + 2];
			double vx = tris[t + 6] - tris[t], vy = tris[t + 7] - tris[t + 1], vz = tris[t + 8] - tris[t + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-9) {
				continue;
			}
			nx /= len;
			ny /= len;
			nz /= len;
			double feet = nx * (fx - tris[t]) + ny * (fy - tris[t + 1]) + nz * (fz - tris[t + 2]);
			if (feet > -0.01 || feet < -1.0) {
				continue;
			}
			// Lowest corner of the box along the normal must reach the plane.
			double lowest = nx * ((nx > 0 ? -hx : hx) + cx - tris[t]) + ny * ((ny > 0 ? -hy : hy) + cy - tris[t + 1])
				+ nz * ((nz > 0 ? -hz : hz) + cz - tris[t + 2]);
			double shift = -lowest + 1.0E-3;
			if (shift > best) {
				best = shift;
				bestN = new double[] {nx, ny, nz};
			}
		}
		if (bestN == null || best > 1.5) {
			return null;
		}
		// Deep inside a stone-backed volume: the interior holds it (shallow slabs do not count).
		if (best > 0.15 && this.overlapsInterior(new AABB(box.getCenter().x - 1.0E-4, box.minY, box.getCenter().z - 1.0E-4,
			box.getCenter().x + 1.0E-4, box.minY + 3.0E-4, box.getCenter().z + 1.0E-4))) {
			return null;
		}
		if (Math.abs(bestN[1]) > 0.5) {
			return new Vec3(0, Math.signum(bestN[1]) * best / Math.abs(bestN[1]), 0);
		}
		return new Vec3(bestN[0] * best, bestN[1] * best, bestN[2] * best);
	}

	/** Separating-axis test of triangle {@code t} against the box (centre, half extents), strict. */
	static boolean triBoxOverlap(float[] tris, int t, double cx, double cy, double cz, double hx, double hy, double hz) {
		double v0x = tris[t] - cx, v0y = tris[t + 1] - cy, v0z = tris[t + 2] - cz;
		double v1x = tris[t + 3] - cx, v1y = tris[t + 4] - cy, v1z = tris[t + 5] - cz;
		double v2x = tris[t + 6] - cx, v2y = tris[t + 7] - cy, v2z = tris[t + 8] - cz;
		if (Math.min(v0x, Math.min(v1x, v2x)) >= hx || Math.max(v0x, Math.max(v1x, v2x)) <= -hx
			|| Math.min(v0y, Math.min(v1y, v2y)) >= hy || Math.max(v0y, Math.max(v1y, v2y)) <= -hy
			|| Math.min(v0z, Math.min(v1z, v2z)) >= hz || Math.max(v0z, Math.max(v1z, v2z)) <= -hz) {
			return false;
		}
		double e0x = v1x - v0x, e0y = v1y - v0y, e0z = v1z - v0z;
		double e1x = v2x - v1x, e1y = v2y - v1y, e1z = v2z - v1z;
		double e2x = v0x - v2x, e2y = v0y - v2y, e2z = v0z - v2z;
		double nx = e0y * e1z - e0z * e1y, ny = e0z * e1x - e0x * e1z, nz = e0x * e1y - e0y * e1x;
		if (separated(nx, ny, nz, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) {
			return false;
		}
		double[][] edges = {{e0x, e0y, e0z}, {e1x, e1y, e1z}, {e2x, e2y, e2z}};
		for (double[] e : edges) {
			if (separated(0, -e[2], e[1], v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)
				|| separated(e[2], 0, -e[0], v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)
				|| separated(-e[1], e[0], 0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) {
				return false;
			}
		}
		return true;
	}

	private static boolean separated(double ax, double ay, double az, double v0x, double v0y, double v0z, double v1x, double v1y,
		double v1z, double v2x, double v2y, double v2z, double hx, double hy, double hz) {
		if (Math.abs(ax) + Math.abs(ay) + Math.abs(az) < 1.0E-12) {
			return false;
		}
		double p0 = ax * v0x + ay * v0y + az * v0z;
		double p1 = ax * v1x + ay * v1y + az * v1z;
		double p2 = ax * v2x + ay * v2y + az * v2z;
		double r = hx * Math.abs(ax) + hy * Math.abs(ay) + hz * Math.abs(az);
		return Math.min(p0, Math.min(p1, p2)) >= r || Math.max(p0, Math.max(p1, p2)) <= -r;
	}

	/**
	 * Exact ray hit (both triangle sides): location on the triangle, face = the normal's main axis turned towards the ray,
	 * position = {@code pos} (this cell). Coordinates are relative to {@code pos} like vanilla's shape clip.
	 */
	@Override
	public @Nullable BlockHitResult clip(Vec3 from, Vec3 to, BlockPos pos) {
		double bx = pos.getX() + this.ox, by = pos.getY() + this.oy, bz = pos.getZ() + this.oz;
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		if (dx * dx + dy * dy + dz * dz < 1.0E-7) {
			return null;
		}
		double best = Double.MAX_VALUE;
		int bestT = -1;
		float[] v = this.triangles;
		for (int t = 0; t < v.length; t += 9) {
			double e1x = v[t + 3] - v[t], e1y = v[t + 4] - v[t + 1], e1z = v[t + 5] - v[t + 2];
			double e2x = v[t + 6] - v[t], e2y = v[t + 7] - v[t + 1], e2z = v[t + 8] - v[t + 2];
			double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
			double det = e1x * px + e1y * py + e1z * pz;
			if (Math.abs(det) < 1.0E-14) {
				continue;
			}
			double inv = 1 / det;
			double tx = from.x - (bx + v[t]), ty = from.y - (by + v[t + 1]), tz = from.z - (bz + v[t + 2]);
			double u = (tx * px + ty * py + tz * pz) * inv;
			if (u < -1.0E-7 || u > 1 + 1.0E-7) {
				continue;
			}
			double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
			double w = (dx * qx + dy * qy + dz * qz) * inv;
			if (w < -1.0E-7 || u + w > 1 + 1.0E-7) {
				continue;
			}
			double s = (e2x * qx + e2y * qy + e2z * qz) * inv;
			if (s >= 0 && s <= 1 && s < best) {
				best = s;
				bestT = t;
			}
		}
		BlockHitResult solid = null;
		if (this.rayVolume != null) {
			VoxelShape volume = this.rayVolume.get();
			if (!volume.isEmpty()) {
				solid = AABB.clip(volume.move(this.ox, this.oy, this.oz).toAabbs(), from, to, pos);
			}
		}
		if (bestT < 0) {
			return solid;
		}
		if (solid != null && solid.getLocation().distanceToSqr(from) < (dx * dx + dy * dy + dz * dz) * best * best - 1.0E-12) {
			// The solid behind the surface (or a thin wall's side) is nearer: its face is what was clicked.
			return solid;
		}
		int t = bestT;
		double e1x = v[t + 3] - v[t], e1y = v[t + 4] - v[t + 1], e1z = v[t + 5] - v[t + 2];
		double e2x = v[t + 6] - v[t], e2y = v[t + 7] - v[t + 1], e2z = v[t + 8] - v[t + 2];
		double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
		if (nx * dx + ny * dy + nz * dz > 0) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		Vec3 point = new Vec3(from.x + dx * best, from.y + dy * best, from.z + dz * best);
		return new BlockHitResult(point, Direction.getApproximateNearest(nx, ny, nz), pos, false);
	}
}
