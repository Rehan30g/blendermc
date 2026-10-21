package dev.echoliminal.endless.gen;

import static dev.echoliminal.endless.gen.EndlessGenerator.FLAG_EMISSIVE;
import static dev.echoliminal.endless.gen.EndlessGenerator.FLAG_FULL;
import static dev.echoliminal.endless.gen.EndlessGenerator.FLAG_OCC_X;
import static dev.echoliminal.endless.gen.EndlessGenerator.FLAG_OCC_Y;
import static dev.echoliminal.endless.gen.EndlessGenerator.FLAG_OCC_Z;
import static dev.echoliminal.endless.gen.EndlessGenerator.HEIGHT;
import static dev.echoliminal.endless.gen.EndlessGenerator.MAX_Y;
import static dev.echoliminal.endless.gen.EndlessGenerator.MIN_Y;
import static dev.echoliminal.endless.gen.EndlessGenerator.REGION;

import java.util.Arrays;

/**
 * Turns the scenes of the modules around a region into a {@link Region}: LOD 0 cuts every triangle on the block grid (and
 * classifies every cell), LOD 1 and 2 keep whole triangles of the matching detail level, LOD 3 and 4 draw a coarse
 * silhouette (smooth ground, flat roofs, tall features) with side walls.
 */
final class Meshing {
	private static final float COVER = 1.7F;

	private Meshing() {
	}

	/** Adds |cross|/2 per axis (the triangle's area projected on the axis planes) of the triangle at {@code pos[off..]}. */
	static void cover(float[] pos, int off, float[] acc) {
		double ux = pos[off + 3] - pos[off], uy = pos[off + 4] - pos[off + 1], uz = pos[off + 5] - pos[off + 2];
		double vx = pos[off + 6] - pos[off], vy = pos[off + 7] - pos[off + 1], vz = pos[off + 8] - pos[off + 2];
		acc[0] += (float) (Math.abs(uy * vz - uz * vy) * 0.5);
		acc[1] += (float) (Math.abs(uz * vx - ux * vz) * 0.5);
		acc[2] += (float) (Math.abs(ux * vy - uy * vx) * 0.5);
	}

	static int occlusion(float[] acc) {
		int f = 0;
		if (acc[0] >= COVER) {
			f |= FLAG_OCC_X;
		}
		if (acc[1] >= COVER) {
			f |= FLAG_OCC_Y;
		}
		if (acc[2] >= COVER) {
			f |= FLAG_OCC_Z;
		}
		if ((f & (FLAG_OCC_X | FLAG_OCC_Y | FLAG_OCC_Z)) == (FLAG_OCC_X | FLAG_OCC_Y | FLAG_OCC_Z)) {
			f |= FLAG_FULL;
		}
		return f;
	}

	static Region region(EndlessGenerator g, int rx, int rz, int lod, long start) {
		return switch (lod) {
			case 0 -> lod0(g, rx, rz, start);
			case 1 -> whole(g, rx, rz, lod, start);
			case 2 -> far(g, rx, rz, lod, start, 4);
			case 3 -> far(g, rx, rz, lod, start, 8);
			default -> far(g, rx, rz, lod, start, 16);
		};
	}

	private static Region lod0(EndlessGenerator g, int rx, int rz, long start) {
		int x0 = rx * REGION, z0 = rz * REGION;
		Cutter c = new Cutter(x0, MIN_Y, z0, x0 + REGION, MAX_Y, z0 + REGION);
		for (Soup s : g.scenesNear(x0, z0, x0 + REGION, z0 + REGION)) {
			for (int t = 0; t < s.count; t++) {
				if ((s.lod[t] & Soup.L0) != 0) {
					c.cut(s, t);
				}
			}
		}
		byte[] cells = new byte[REGION * REGION * HEIGHT];
		float[] acc = new float[cells.length * 3];
		float[] one = new float[3];
		for (int t = 0; t < c.count; t++) {
			long key = c.cell[t];
			int x = EndlessGenerator.keyX(key), y = EndlessGenerator.keyY(key), z = EndlessGenerator.keyZ(key);
			int idx = ((y - MIN_Y) * REGION + (z - z0)) * REGION + (x - x0);
			cells[idx] |= (byte) (EndlessGenerator.SURFACE | (Mats.get(c.mat[t]).emissive() ? FLAG_EMISSIVE : 0));
			one[0] = one[1] = one[2] = 0;
			cover(c.pos, t * 9, one);
			acc[idx * 3] += one[0];
			acc[idx * 3 + 1] += one[1];
			acc[idx * 3 + 2] += one[2];
		}
		float[] tmp = new float[3];
		for (int i = 0; i < cells.length; i++) {
			if (cells[i] != 0) {
				tmp[0] = acc[i * 3];
				tmp[1] = acc[i * 3 + 1];
				tmp[2] = acc[i * 3 + 2];
				cells[i] |= (byte) occlusion(tmp);
			}
		}
		// Solid ground of the field.
		for (int z = 0; z < REGION; z++) {
			for (int x = 0; x < REGION; x++) {
				double top = g.groundTop(x0 + x + 0.5, z0 + z + 0.5);
				if (Double.isNaN(top)) {
					continue;
				}
				for (int y = MIN_Y; y < MAX_Y && y + 0.5 < top - 0.02; y++) {
					int idx = ((y - MIN_Y) * REGION + z) * REGION + x;
					if (cells[idx] == 0) {
						cells[idx] = EndlessGenerator.SOLID;
					}
				}
			}
		}
		// Rendering does not need the per-cell slivers: the triangles of the scenes are only subdivided to a few blocks, and each
		// belongs to the cell holding its centre (the client hides the triangles of dug cells by that cell).
		Soup r = new Soup();
		for (Soup sc : g.scenesNear(x0, z0, x0 + REGION, z0 + REGION)) {
			for (int t = 0; t < sc.count; t++) {
				if ((sc.lod[t] & Soup.L0) != 0) {
					subdivide(r, sc, t, x0, z0);
				}
			}
		}
		return fromSoup(r, rx, rz, 0, cells, start);
	}

	private static final double MAX_EDGE = 4.5;

	/** Splits triangle t into n * n similar triangles (n from its longest edge) and keeps those whose centre lies in the region. */
	private static void subdivide(Soup out, Soup s, int t, int x0, int z0) {
		float[] p = s.p;
		int o = t * 9;
		double e0 = dist(p, o, o + 3), e1 = dist(p, o + 3, o + 6), e2 = dist(p, o + 6, o);
		int n = Math.max(1, (int) Math.ceil(Math.max(e0, Math.max(e1, e2)) / MAX_EDGE));
		n = Math.min(n, 8);
		if (n == 1) {
			float cx = (p[o] + p[o + 3] + p[o + 6]) / 3, cz = (p[o + 2] + p[o + 5] + p[o + 8]) / 3;
			if (cx >= x0 && cx < x0 + REGION && cz >= z0 && cz < z0 + REGION) {
				copy(out, s, t);
			}
			return;
		}
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n - j; i++) {
				addSub(out, s, t, n, i, j, false, x0, z0);
				if (i < n - j - 1) {
					addSub(out, s, t, n, i, j, true, x0, z0);
				}
			}
		}
	}

	private static double dist(float[] p, int a, int b) {
		double x = p[a] - p[b], y = p[a + 1] - p[b + 1], z = p[a + 2] - p[b + 2];
		return Math.sqrt(x * x + y * y + z * z);
	}

	/** Sub triangle (i, j) of the n-grid: upright (corners (i,j) (i+1,j) (i,j+1)) or inverted ((i+1,j) (i+1,j+1) (i,j+1)). */
	private static void addSub(Soup out, Soup s, int t, int n, int i, int j, boolean inverted, int x0, int z0) {
		int[][] g = inverted ? new int[][] {{i + 1, j}, {i + 1, j + 1}, {i, j + 1}} : new int[][] {{i, j}, {i + 1, j}, {i, j + 1}};
		float[] pos = new float[9], nrm = new float[9], uv = new float[6];
		for (int k = 0; k < 3; k++) {
			double b1 = (double) g[k][0] / n, b2 = (double) g[k][1] / n, b0 = 1 - b1 - b2;
			for (int c = 0; c < 3; c++) {
				pos[k * 3 + c] = (float) (s.p[t * 9 + c] * b0 + s.p[t * 9 + 3 + c] * b1 + s.p[t * 9 + 6 + c] * b2);
				nrm[k * 3 + c] = (float) (s.n[t * 9 + c] * b0 + s.n[t * 9 + 3 + c] * b1 + s.n[t * 9 + 6 + c] * b2);
			}
			for (int c = 0; c < 2; c++) {
				uv[k * 2 + c] = (float) (s.uv[t * 6 + c] * b0 + s.uv[t * 6 + 2 + c] * b1 + s.uv[t * 6 + 4 + c] * b2);
			}
		}
		float cx = (pos[0] + pos[3] + pos[6]) / 3, cz = (pos[2] + pos[5] + pos[8]) / 3;
		if (cx < x0 || cx >= x0 + REGION || cz < z0 || cz >= z0 + REGION) {
			return;
		}
		if (out.count + 1 > out.mat.length) {
			int cap = out.mat.length * 2;
			out.p = Arrays.copyOf(out.p, cap * 9);
			out.n = Arrays.copyOf(out.n, cap * 9);
			out.uv = Arrays.copyOf(out.uv, cap * 6);
			out.mat = Arrays.copyOf(out.mat, cap);
			out.lod = Arrays.copyOf(out.lod, cap);
		}
		int d = out.count++;
		System.arraycopy(pos, 0, out.p, d * 9, 9);
		System.arraycopy(nrm, 0, out.n, d * 9, 9);
		System.arraycopy(uv, 0, out.uv, d * 6, 6);
		out.mat[d] = s.mat[t];
		out.lod[d] = s.lod[t];
	}

	/** LOD 1 and 2: whole triangles (not cut), a triangle belongs to the region that holds its centre. */
	private static Region whole(EndlessGenerator g, int rx, int rz, int lod, long start) {
		int x0 = rx * REGION, z0 = rz * REGION;
		int bit = 1 << lod;
		Soup out = new Soup();
		for (Soup s : g.scenesNear(x0, z0, x0 + REGION, z0 + REGION)) {
			for (int t = 0; t < s.count; t++) {
				if ((s.lod[t] & bit) == 0) {
					continue;
				}
				float cx = (s.p[t * 9] + s.p[t * 9 + 3] + s.p[t * 9 + 6]) / 3, cz = (s.p[t * 9 + 2] + s.p[t * 9 + 5] + s.p[t * 9 + 8]) / 3;
				if (cx < x0 || cx >= x0 + REGION || cz < z0 || cz >= z0 + REGION) {
					continue;
				}
				copy(out, s, t);
			}
		}
		return fromSoup(out, rx, rz, lod, start);
	}

	private static void copy(Soup out, Soup s, int t) {
		Soup one = out;
		if (one.count + 1 > one.mat.length) {
			int cap = one.mat.length * 2;
			one.p = Arrays.copyOf(one.p, cap * 9);
			one.n = Arrays.copyOf(one.n, cap * 9);
			one.uv = Arrays.copyOf(one.uv, cap * 6);
			one.mat = Arrays.copyOf(one.mat, cap);
			one.lod = Arrays.copyOf(one.lod, cap);
		}
		int d = one.count++;
		System.arraycopy(s.p, t * 9, one.p, d * 9, 9);
		System.arraycopy(s.n, t * 9, one.n, d * 9, 9);
		System.arraycopy(s.uv, t * 6, one.uv, d * 6, 6);
		one.mat[d] = s.mat[t];
		one.lod[d] = s.lod[t];
	}

	private static Region fromSoup(Soup s, int rx, int rz, int lod, long start) {
		return fromSoup(s, rx, rz, lod, null, start);
	}

	private static Region fromSoup(Soup s, int rx, int rz, int lod, byte[] cells, long start) {
		int n = s.count;
		int[] indices = new int[n * 3];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = i;
		}
		return new Region(rx, rz, lod, Arrays.copyOf(s.p, n * 9), Arrays.copyOf(s.n, n * 9), Arrays.copyOf(s.uv, n * 6), indices, Arrays.copyOf(s.mat, n), cells,
			System.nanoTime() - start);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// LOD 3 and 4

	/** One column cell of the coarse silhouette. */
	private record Col(boolean block, double top, int topMat, int sideMat) {
	}

	private static Col column(EndlessGenerator g, double cx, double cz) {
		World w = g.world;
		World.Mod m = w.moduleAt(cx, cz);
		if (!m.field()) {
			return new Col(true, w.ceilY(m, m.mac.hi) + 0.8, Mats.CONCRETE, m.pool() ? Mats.TILE : Mats.WALLPAPER);
		}
		double ground = w.ground(cx, cz), ft = w.featureTop(cx, cz);
		if (ft > ground + 0.8) {
			return new Col(true, ft, Mats.STAIR, Mats.STAIR);
		}
		double n = Noise.fbm(w.seed, 8, cx / 37.0, cz / 37.0);
		return new Col(false, ground, n > 0.64 ? Mats.GRASS_B : Mats.GRASS_A, Mats.DIRT);
	}

	private static Region far(EndlessGenerator g, int rx, int rz, int lod, long start, int f) {
		World w = g.world;
		int n = REGION / f;
		double x0 = rx * REGION, z0 = rz * REGION;
		Soup s = new Soup();
		Col[][] cols = new Col[n + 2][n + 2];
		for (int j = -1; j <= n; j++) {
			for (int i = -1; i <= n; i++) {
				cols[j + 1][i + 1] = column(g, x0 + (i + 0.5) * f, z0 + (j + 0.5) * f);
			}
		}
		double[][] corner = new double[n + 1][n + 1];
		for (int j = 0; j <= n; j++) {
			for (int i = 0; i <= n; i++) {
				corner[j][i] = w.ground(x0 + i * f, z0 + j * f);
			}
		}
		Soup.Surf surf = Soup.Surf.of(Mats.GRASS_A, 3);
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				Col c = cols[j + 1][i + 1];
				double xa = x0 + i * f, xb = xa + f, za = z0 + j * f, zb = za + f;
				if (!c.block()) {
					double[] a = {xa, corner[j][i], za}, b = {xb, corner[j][i + 1], za}, d = {xb, corner[j + 1][i + 1], zb}, e = {xa, corner[j + 1][i], zb};
					s.tri(c.topMat(), 0, surf, a, b, d, 0, 1, 0);
					s.tri(c.topMat(), 0, surf, a, d, e, 0, 1, 0);
					continue;
				}
				double top = c.top();
				s.tri(c.topMat(), 0, surf, new double[] {xa, top, za}, new double[] {xb, top, za}, new double[] {xb, top, zb}, 0, 1, 0);
				s.tri(c.topMat(), 0, surf, new double[] {xa, top, za}, new double[] {xb, top, zb}, new double[] {xa, top, zb}, 0, 1, 0);
				int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
				for (int[] d : dirs) {
					Col o = cols[j + 1 + d[1]][i + 1 + d[0]];
					double base;
					if (o.block()) {
						base = o.top();
					} else {
						double ex0 = d[0] != 0 ? (d[0] > 0 ? xb : xa) : xa, ez0 = d[1] != 0 ? (d[1] > 0 ? zb : za) : za;
						double ex1 = d[0] != 0 ? ex0 : xb, ez1 = d[1] != 0 ? ez0 : zb;
						base = Math.min(w.ground(ex0, ez0), w.ground(ex1, ez1)) - 1.0;
					}
					if (base >= top - 0.01) {
						continue;
					}
					double ex0 = d[0] != 0 ? (d[0] > 0 ? xb : xa) : xa, ez0 = d[1] != 0 ? (d[1] > 0 ? zb : za) : za;
					double ex1 = d[0] != 0 ? ex0 : xb, ez1 = d[1] != 0 ? ez0 : zb;
					s.tri(c.sideMat(), 0, surf, new double[] {ex0, base, ez0}, new double[] {ex1, base, ez1}, new double[] {ex1, top, ez1}, d[0], 0, d[1]);
					s.tri(c.sideMat(), 0, surf, new double[] {ex0, base, ez0}, new double[] {ex1, top, ez1}, new double[] {ex0, top, ez0}, d[0], 0, d[1]);
				}
			}
		}
		return fromSoup(s, rx, rz, lod, start);
	}
}
