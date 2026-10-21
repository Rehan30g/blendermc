package dev.blendermc.liminal.light;

import dev.blendermc.liminal.LiminalMesh;
import java.util.List;
import java.util.Locale;

/**
 * Ray-cast acceleration over a level's occluding triangles: a bounding volume hierarchy (binned SAH) with any-hit
 * traversal, double sided. Emissive, non-colliding (decals) and "_sky" triangles are left out. Immutable and
 * thread-safe once built; each thread brings its own {@link Tracer} (traversal stack).
 */
public final class RayScene {
	private static final int LEAF_SIZE = 4;
	private static final int BINS = 16;
	/** Slack on barycentric tests so rays through shared edges do not slip between two triangles. */
	private static final double EDGE = 1.0E-5;

	/** Per-thread traversal scratch. */
	public static final class Tracer {
		final int[] stack = new int[96];
		/** Result of {@link #closest}: BVH slot, distance parameter and barycentric u, v of the hit. */
		int slot;
		double t, u, v;
	}

	private final float[] tris;
	private final float[] nodeBox;
	private final int[] nodeA;
	private final int[] nodeB;
	private int nodeCount;
	private final int triangleCount;
	/** Per BVH slot: nonzero while the triangle's cell is hidden (mined, not drawn); such triangles do not occlude. */
	private final byte[] disabled;
	/** Per BVH slot: the three mesh vertex ids and the material of the triangle (for bounce light). */
	int[] slotVertex = new int[0];
	int[] slotMaterial = new int[0];
	/** Cell to the BVH slots of its triangles (empty when built without cell info). */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> cellSlots = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

	private RayScene(float[] tris, int triangleCount, int maxNodes) {
		this.tris = tris;
		this.triangleCount = triangleCount;
		this.disabled = new byte[triangleCount];
this.nodeBox = new float[maxNodes * 6];
		this.nodeA = new int[maxNodes];
		this.nodeB = new int[maxNodes];
	}

	/** True for triangles of this material that cast shadows. */
	public static boolean occludes(LiminalMesh.Material m) {
		return !m.emissive() && m.collides() && !m.name().toLowerCase(Locale.ROOT).endsWith("_sky");
	}

	public static RayScene build(LiminalMesh mesh) {
		return build(mesh, null);
	}

	/** {@code cellOfTri}: the block cell of every mesh triangle, enabling {@link #setCellEnabled}; may be null. */
	public static RayScene build(LiminalMesh mesh, long[] cellOfTri) {
float[] p = mesh.positions();
		int[] idx = mesh.indices();
		int[] triMat = mesh.triangleMaterial();
		List<LiminalMesh.Material> materials = mesh.materials();
		int total = idx.length / 3;
		int[] keep = new int[total];
		int n = 0;
		for (int t = 0; t < total; t++) {
			int m = t < triMat.length ? triMat[t] : 0;
			if (m >= 0 && m < materials.size() && !occludes(materials.get(m))) {
				continue;
			}
			keep[n++] = t;
		}
		float[] raw = new float[n * 9];
		float[] box = new float[n * 6];
		float[] cen = new float[n * 3];
		for (int i = 0; i < n; i++) {
			int t = keep[i];
			for (int k = 0; k < 3; k++) {
				int v = idx[t * 3 + k] * 3;
				for (int a = 0; a < 3; a++) {
					raw[i * 9 + k * 3 + a] = p[v + a];
				}
			}
			for (int a = 0; a < 3; a++) {
				float lo = Math.min(raw[i * 9 + a], Math.min(raw[i * 9 + 3 + a], raw[i * 9 + 6 + a]));
				float hi = Math.max(raw[i * 9 + a], Math.max(raw[i * 9 + 3 + a], raw[i * 9 + 6 + a]));
				box[i * 6 + a] = lo;
				box[i * 6 + 3 + a] = hi;
				cen[i * 3 + a] = (lo + hi) * 0.5F;
			}
		}
		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		RayScene scene = new RayScene(new float[n * 9], n, Math.max(1, n * 2));
		if (n > 0) {
			scene.buildNode(order, 0, n, box, cen);
		} else {
			scene.nodeCount = 1;
			scene.nodeA[0] = 0;
			scene.nodeB[0] = 0;
			scene.nodeBox[0] = scene.nodeBox[1] = scene.nodeBox[2] = 1.0E30F;
			scene.nodeBox[3] = scene.nodeBox[4] = scene.nodeBox[5] = -1.0E30F;
		}
		for (int i = 0; i < n; i++) {
			System.arraycopy(raw, order[i] * 9, scene.tris, i * 9, 9);
		}
		scene.slotVertex = new int[n * 3];
		scene.slotMaterial = new int[n];
		for (int i = 0; i < n; i++) {
			int t = keep[order[i]];
			for (int k = 0; k < 3; k++) {
				scene.slotVertex[i * 3 + k] = idx[t * 3 + k];
			}
			scene.slotMaterial[i] = t < triMat.length ? triMat[t] : 0;
		}
		if (cellOfTri != null) {
			it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> by = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
			for (int i = 0; i < n; i++) {
				int t = keep[order[i]];
				if (t < cellOfTri.length) {
					by.computeIfAbsent(cellOfTri[t], k -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
				}
			}
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<it.unimi.dsi.fastutil.ints.IntArrayList> e : by.long2ObjectEntrySet()) {
				scene.cellSlots.put(e.getLongKey(), e.getValue().toIntArray());
			}
		}
return scene;
	}

	/** Turns the triangles of a cell on or off as occluders (a mined cell stops blocking light). No BVH rebuild. */
	public void setCellEnabled(long cell, boolean enabled) {
		int[] slots = this.cellSlots.get(cell);
		if (slots != null) {
			for (int s : slots) {
				this.disabled[s] = (byte) (enabled ? 0 : 1);
			}
		}
	}

	/** Sets every known cell from {@code visible}; returns how many cells are disabled. */
	public int applyVisibility(java.util.function.LongPredicate visible) {
		int off = 0;
		for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<int[]> e : this.cellSlots.long2ObjectEntrySet()) {
			boolean on = visible.test(e.getLongKey());
			for (int s : e.getValue()) {
				this.disabled[s] = (byte) (on ? 0 : 1);
			}
			if (!on) {
				off++;
			}
		}
		return off;
	}

	public int triangleCount() {
		return this.triangleCount;
	}

	public int nodeCount() {
		return this.nodeCount;
	}

	private int buildNode(int[] order, int start, int end, float[] box, float[] cen) {
		int node = this.nodeCount++;
		float[] b = {1.0E30F, 1.0E30F, 1.0E30F, -1.0E30F, -1.0E30F, -1.0E30F};
		float[] c = {1.0E30F, 1.0E30F, 1.0E30F, -1.0E30F, -1.0E30F, -1.0E30F};
		for (int i = start; i < end; i++) {
			int t = order[i];
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], box[t * 6 + a]);
				b[3 + a] = Math.max(b[3 + a], box[t * 6 + 3 + a]);
				c[a] = Math.min(c[a], cen[t * 3 + a]);
				c[3 + a] = Math.max(c[3 + a], cen[t * 3 + a]);
			}
		}
		System.arraycopy(b, 0, this.nodeBox, node * 6, 6);
		int count = end - start;
		int axis = 0;
		float best = c[3] - c[0];
		for (int a = 1; a < 3; a++) {
			if (c[3 + a] - c[a] > best) {
				best = c[3 + a] - c[a];
				axis = a;
			}
		}
		if (count <= LEAF_SIZE || best <= 1.0E-6F) {
			this.nodeA[node] = start;
			this.nodeB[node] = count;
			return node;
		}
		// Binned SAH on the longest centroid axis.
		int[] binCount = new int[BINS];
		float[][] binBox = new float[BINS][];
		float lo = c[axis];
		float scale = BINS / best;
		for (int i = start; i < end; i++) {
			int t = order[i];
			int bin = Math.min(BINS - 1, (int) ((cen[t * 3 + axis] - lo) * scale));
			binCount[bin]++;
			if (binBox[bin] == null) {
				binBox[bin] = new float[] {1.0E30F, 1.0E30F, 1.0E30F, -1.0E30F, -1.0E30F, -1.0E30F};
			}
			grow(binBox[bin], box, t);
		}
		float[] rightArea = new float[BINS];
		float[] acc = {1.0E30F, 1.0E30F, 1.0E30F, -1.0E30F, -1.0E30F, -1.0E30F};
		int[] rightCount = new int[BINS];
		int run = 0;
		for (int i = BINS - 1; i > 0; i--) {
			if (binBox[i] != null) {
				growBox(acc, binBox[i]);
			}
			run += binCount[i];
			rightCount[i] = run;
			rightArea[i] = area(acc);
		}
		acc = new float[] {1.0E30F, 1.0E30F, 1.0E30F, -1.0E30F, -1.0E30F, -1.0E30F};
		run = 0;
		float bestCost = Float.MAX_VALUE;
		int bestSplit = -1;
		for (int i = 0; i < BINS - 1; i++) {
			if (binBox[i] != null) {
				growBox(acc, binBox[i]);
			}
			run += binCount[i];
			if (run == 0 || rightCount[i + 1] == 0) {
				continue;
			}
			float cost = run * area(acc) + rightCount[i + 1] * rightArea[i + 1];
			if (cost < bestCost) {
				bestCost = cost;
				bestSplit = i;
			}
		}
		int mid = start;
		if (bestSplit >= 0) {
			int i = start, j = end - 1;
			while (i <= j) {
				int bin = Math.min(BINS - 1, (int) ((cen[order[i] * 3 + axis] - lo) * scale));
				if (bin <= bestSplit) {
					i++;
				} else {
					int tmp = order[i];
					order[i] = order[j];
					order[j] = tmp;
					j--;
				}
			}
			mid = i;
		}
		if (mid == start || mid == end) {
			mid = (start + end) >>> 1;
		}
		this.buildNode(order, start, mid, box, cen);
		int right = this.buildNode(order, mid, end, box, cen);
		this.nodeA[node] = right;
		this.nodeB[node] = 0;
		return node;
	}

	private static void grow(float[] into, float[] box, int t) {
		for (int a = 0; a < 3; a++) {
			into[a] = Math.min(into[a], box[t * 6 + a]);
			into[3 + a] = Math.max(into[3 + a], box[t * 6 + 3 + a]);
		}
	}

	private static void growBox(float[] into, float[] other) {
		for (int a = 0; a < 3; a++) {
			into[a] = Math.min(into[a], other[a]);
			into[3 + a] = Math.max(into[3 + a], other[3 + a]);
		}
	}

	private static float area(float[] b) {
		float dx = b[3] - b[0], dy = b[4] - b[1], dz = b[5] - b[2];
		return dx < 0 ? 0 : 2 * (dx * dy + dy * dz + dz * dx);
	}

	/**
	 * Whether anything blocks the ray {@code o + d·t} for t in (tmin, tmax): level triangles, then (if given) the
	 * placed blocks of {@code dyn}. {@code skipEnd}: the block holding the segment's end point does not count (the
	 * light source's own block).
	 */
	public boolean occluded(Tracer tr, LightSources dyn, double ox, double oy, double oz, double dx, double dy, double dz, double tmin, double tmax,
			boolean skipEnd) {
		if (this.triangleCount > 0 && this.hitTriangles(tr, ox, oy, oz, dx, dy, dz, tmin, tmax)) {
			return true;
		}
		return dyn != null && dyn.blocked(ox, oy, oz, dx, dy, dz, tmin, tmax, skipEnd);
	}

	/**
	 * Nearest triangle hit by {@code o + d·t}, t in (0, tmax) (level triangles only); true with the result in
	 * the tracer ({@link Tracer#slot} etc.).
	 */
	boolean closest(Tracer tr, double ox, double oy, double oz, double dx, double dy, double dz, double tmax) {
		if (this.triangleCount == 0) {
			return false;
		}
		double ix = 1.0 / (Math.abs(dx) < 1.0E-30 ? 1.0E-30 : dx);
		double iy = 1.0 / (Math.abs(dy) < 1.0E-30 ? 1.0E-30 : dy);
		double iz = 1.0 / (Math.abs(dz) < 1.0E-30 ? 1.0E-30 : dz);
		int[] stack = tr.stack;
		int sp = 0;
		stack[sp++] = 0;
		float[] nb = this.nodeBox;
		float[] tr9 = this.tris;
		double best = tmax;
		boolean found = false;
		while (sp > 0) {
			int n = stack[--sp];
			int bi = n * 6;
			double t0 = (nb[bi] - ox) * ix, t1 = (nb[bi + 3] - ox) * ix;
			double lo = Math.min(t0, t1), hi = Math.max(t0, t1);
			t0 = (nb[bi + 1] - oy) * iy;
			t1 = (nb[bi + 4] - oy) * iy;
			lo = Math.max(lo, Math.min(t0, t1));
			hi = Math.min(hi, Math.max(t0, t1));
			t0 = (nb[bi + 2] - oz) * iz;
			t1 = (nb[bi + 5] - oz) * iz;
			lo = Math.max(lo, Math.min(t0, t1));
			hi = Math.min(hi, Math.max(t0, t1));
			if (Math.max(lo, 0.0) > Math.min(hi, best)) {
				continue;
			}
			int count = this.nodeB[n];
			if (count > 0) {
				int first = this.nodeA[n];
				for (int i = first; i < first + count; i++) {
					if (this.disabled[i] != 0) {
						continue;
					}
					int at = i * 9;
					double v0x = tr9[at], v0y = tr9[at + 1], v0z = tr9[at + 2];
					double e1x = tr9[at + 3] - v0x, e1y = tr9[at + 4] - v0y, e1z = tr9[at + 5] - v0z;
					double e2x = tr9[at + 6] - v0x, e2y = tr9[at + 7] - v0y, e2z = tr9[at + 8] - v0z;
					double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
					double det = e1x * px + e1y * py + e1z * pz;
					if (det > -1.0E-12 && det < 1.0E-12) {
						continue;
					}
					double inv = 1.0 / det;
					double sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
					double u = (sx * px + sy * py + sz * pz) * inv;
					if (u < 0 || u > 1) {
						continue;
					}
					double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
					double v = (dx * qx + dy * qy + dz * qz) * inv;
					if (v < 0 || u + v > 1) {
						continue;
					}
					double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
					if (t > 1.0E-6 && t < best) {
						best = t;
						tr.slot = i;
						tr.t = t;
						tr.u = u;
						tr.v = v;
						found = true;
					}
				}
			} else {
				stack[sp++] = this.nodeA[n];
				stack[sp++] = n + 1;
			}
		}
		return found;
	}

	private boolean hitTriangles(Tracer tr, double ox, double oy, double oz, double dx, double dy, double dz, double tmin, double tmax) {
		double ix = 1.0 / (Math.abs(dx) < 1.0E-30 ? 1.0E-30 : dx);
		double iy = 1.0 / (Math.abs(dy) < 1.0E-30 ? 1.0E-30 : dy);
		double iz = 1.0 / (Math.abs(dz) < 1.0E-30 ? 1.0E-30 : dz);
		int[] stack = tr.stack;
		int sp = 0;
		stack[sp++] = 0;
		float[] nb = this.nodeBox;
		float[] tr9 = this.tris;
		while (sp > 0) {
			int n = stack[--sp];
			int bi = n * 6;
			double t0 = (nb[bi] - ox) * ix, t1 = (nb[bi + 3] - ox) * ix;
			double lo = Math.min(t0, t1), hi = Math.max(t0, t1);
			t0 = (nb[bi + 1] - oy) * iy;
			t1 = (nb[bi + 4] - oy) * iy;
			lo = Math.max(lo, Math.min(t0, t1));
			hi = Math.min(hi, Math.max(t0, t1));
			t0 = (nb[bi + 2] - oz) * iz;
			t1 = (nb[bi + 5] - oz) * iz;
			lo = Math.max(lo, Math.min(t0, t1));
			hi = Math.min(hi, Math.max(t0, t1));
			if (Math.max(lo, tmin) > Math.min(hi, tmax)) {
				continue;
			}
			int count = this.nodeB[n];
			if (count > 0) {
				int first = this.nodeA[n];
				for (int i = first; i < first + count; i++) {
					if (this.disabled[i] != 0) {
						continue;
					}
					int at = i * 9;
					double v0x = tr9[at], v0y = tr9[at + 1], v0z = tr9[at + 2];
					double e1x = tr9[at + 3] - v0x, e1y = tr9[at + 4] - v0y, e1z = tr9[at + 5] - v0z;
					double e2x = tr9[at + 6] - v0x, e2y = tr9[at + 7] - v0y, e2z = tr9[at + 8] - v0z;
					double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
					double det = e1x * px + e1y * py + e1z * pz;
					if (det > -1.0E-12 && det < 1.0E-12) {
						continue;
					}
					double inv = 1.0 / det;
					double sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
					double u = (sx * px + sy * py + sz * pz) * inv;
					if (u < -EDGE || u > 1 + EDGE) {
						continue;
					}
					double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
					double v = (dx * qx + dy * qy + dz * qz) * inv;
					if (v < -EDGE || u + v > 1 + EDGE) {
						continue;
					}
					double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
					if (t > tmin && t < tmax) {
						return true;
					}
				}
			} else {
				stack[sp++] = this.nodeA[n];
				stack[sp++] = n + 1;
			}
		}
		return false;
	}
}
