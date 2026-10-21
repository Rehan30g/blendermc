package dev.echoliminal.endless.gen;

import java.util.Arrays;

/**
 * Cuts triangles on the 1-block cell grid. Each piece belongs to exactly one cell; a face lying exactly in a grid plane
 * belongs to the cell behind it (below a floor, outside a wall's front), so a piece never appears in two cells. The same
 * code serves the whole region (LOD 0) and the single-cell query, so both always agree.
 */
final class Cutter {
	private static final double EPS = 1.0E-6;

	float[] pos = new float[9 * 1024], nrm = new float[9 * 1024], uv = new float[6 * 1024];
	int[] mat = new int[1024];
	long[] cell = new long[1024];
	int count;

	/** Window of cells: x0 <= x < x1, y0 <= y < y1, z0 <= z < z1. */
	private final int wx0, wy0, wz0, wx1, wy1, wz1;

	Cutter(int wx0, int wy0, int wz0, int wx1, int wy1, int wz1) {
		this.wx0 = wx0;
		this.wy0 = wy0;
		this.wz0 = wz0;
		this.wx1 = wx1;
		this.wy1 = wy1;
		this.wz1 = wz1;
	}

	private void grow() {
		if (this.count + 1 > this.mat.length) {
			int cap = this.mat.length * 2;
			this.pos = Arrays.copyOf(this.pos, cap * 9);
			this.nrm = Arrays.copyOf(this.nrm, cap * 9);
			this.uv = Arrays.copyOf(this.uv, cap * 6);
			this.mat = Arrays.copyOf(this.mat, cap);
			this.cell = Arrays.copyOf(this.cell, cap);
		}
	}

	/** Cuts triangle t of the soup into the window. */
	void cut(Soup s, int t) {
		double[][] v = new double[3][8];
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int k = 0; k < 3; k++) {
			for (int c = 0; c < 3; c++) {
				v[k][c] = s.p[t * 9 + k * 3 + c];
				v[k][3 + c] = s.n[t * 9 + k * 3 + c];
			}
			v[k][6] = s.uv[t * 6 + k * 2];
			v[k][7] = s.uv[t * 6 + k * 2 + 1];
			minX = Math.min(minX, v[k][0]);
			maxX = Math.max(maxX, v[k][0]);
			minY = Math.min(minY, v[k][1]);
			maxY = Math.max(maxY, v[k][1]);
			minZ = Math.min(minZ, v[k][2]);
			maxZ = Math.max(maxZ, v[k][2]);
		}
		int[] lo = {(int) Math.floor(minX + EPS), (int) Math.floor(minY + EPS), (int) Math.floor(minZ + EPS)};
		int[] hi = {(int) Math.ceil(maxX - EPS) - 1, (int) Math.ceil(maxY - EPS) - 1, (int) Math.ceil(maxZ - EPS) - 1};
		double[] mins = {minX, minY, minZ}, maxs = {maxX, maxY, maxZ};
		double ux = v[1][0] - v[0][0], uy = v[1][1] - v[0][1], uz = v[1][2] - v[0][2], wx = v[2][0] - v[0][0], wy = v[2][1] - v[0][1], wz = v[2][2] - v[0][2];
		double[] n = {uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx};
		boolean[] flat = new boolean[3];
		for (int a = 0; a < 3; a++) {
			// All three vertices in one grid plane: the cell behind the face owns it.
			double plane = Math.rint(mins[a]);
			if (maxs[a] - mins[a] < EPS && Math.abs(mins[a] - plane) < EPS) {
				int owner = n[a] > 0 ? (int) plane - 1 : (int) plane;
				lo[a] = hi[a] = owner;
				flat[a] = true;
			}
		}
		boolean inOne = lo[0] == hi[0] && lo[1] == hi[1] && lo[2] == hi[2];
		int[] wlo = {this.wx0, this.wy0, this.wz0}, whi = {this.wx1 - 1, this.wy1 - 1, this.wz1 - 1};
		for (int a = 0; a < 3; a++) {
			lo[a] = Math.max(lo[a], wlo[a]);
			hi[a] = Math.min(hi[a], whi[a]);
			if (lo[a] > hi[a]) {
				return;
			}
		}
		int mt = s.mat[t];
		if (inOne) {
			this.emit(new double[][] {v[0], v[1], v[2]}, 3, mt, lo[0], lo[1], lo[2]);
			return;
		}
		double[][] poly = {v[0], v[1], v[2]};
		for (int cx = lo[0]; cx <= hi[0]; cx++) {
			double[][] p1 = flat[0] ? poly : clip(poly, 0, cx, cx + 1);
			if (p1.length < 3) {
				continue;
			}
			for (int cy = lo[1]; cy <= hi[1]; cy++) {
				double[][] p2 = flat[1] ? p1 : clip(p1, 1, cy, cy + 1);
				if (p2.length < 3) {
					continue;
				}
				for (int cz = lo[2]; cz <= hi[2]; cz++) {
					double[][] p3 = flat[2] ? p2 : clip(p2, 2, cz, cz + 1);
					if (p3.length >= 3) {
						this.emit(p3, p3.length, mt, cx, cy, cz);
					}
				}
			}
		}
	}

	/** Keeps lo <= coord <= hi on one axis; new vertices lie exactly on the plane. */
	private static double[][] clip(double[][] poly, int axis, double lo, double hi) {
		double[][] cur = poly;
		for (int pass = 0; pass < 2; pass++) {
			double plane = pass == 0 ? lo : hi;
			double sign = pass == 0 ? 1 : -1;
			int n = cur.length;
			double[][] out = new double[n + 2][];
			int o = 0;
			for (int i = 0; i < n; i++) {
				double[] p = cur[i], q = cur[(i + 1) % n];
				double dp = (p[axis] - plane) * sign, dq = (q[axis] - plane) * sign;
				if (dp >= 0) {
					out[o++] = p;
				}
				if ((dp > 0 && dq < 0) || (dp < 0 && dq > 0)) {
					double u = dp / (dp - dq);
					double[] r = new double[8];
					for (int c = 0; c < 8; c++) {
						r[c] = p[c] + (q[c] - p[c]) * u;
					}
					r[axis] = plane;
					out[o++] = r;
				}
			}
			cur = Arrays.copyOf(out, o);
			if (o < 3) {
				return cur;
			}
		}
		return cur;
	}

	private void emit(double[][] poly, int n, int material, int cx, int cy, int cz) {
		long key = EndlessGenerator.cellKey(cx, cy, cz);
		for (int i = 1; i + 1 < n; i++) {
			double[] a = poly[0], b = poly[i], c = poly[i + 1];
			double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			if (nx * nx + ny * ny + nz * nz < 1.0E-12) {
				continue;
			}
			this.grow();
			int t = this.count++;
			this.mat[t] = material;
			this.cell[t] = key;
			double[][] vs = {a, b, c};
			for (int k = 0; k < 3; k++) {
				this.pos[t * 9 + k * 3] = (float) vs[k][0];
				this.pos[t * 9 + k * 3 + 1] = (float) vs[k][1];
				this.pos[t * 9 + k * 3 + 2] = (float) vs[k][2];
				double l = Math.sqrt(vs[k][3] * vs[k][3] + vs[k][4] * vs[k][4] + vs[k][5] * vs[k][5]);
				if (l < 1.0E-9) {
					l = 1;
				}
				this.nrm[t * 9 + k * 3] = (float) (vs[k][3] / l);
				this.nrm[t * 9 + k * 3 + 1] = (float) (vs[k][4] / l);
				this.nrm[t * 9 + k * 3 + 2] = (float) (vs[k][5] / l);
				this.uv[t * 6 + k * 2] = (float) vs[k][6];
				this.uv[t * 6 + k * 2 + 1] = (float) vs[k][7];
			}
		}
	}
}
