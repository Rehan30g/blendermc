package dev.blendermc.endless.gen;

/**
 * Geometry kit that writes into a {@link Soup}: closed prisms between two rings of points, ring-to-ring bridges (slabs with
 * holes, sloped basin edges, steps), wall pieces with chamfered corners, octagonal columns, spiral stair treads. All
 * shapes are free-form: any angle, any real number position and thickness.
 */
final class Geo {
	private Geo() {
	}

	static double[] pt(double x, double y, double z) {
		return new double[] {x, y, z};
	}

	/** A ring of {@code n} points on a circle (a regular polygon), rotated by {@code rot}. */
	static double[][] circle(double cx, double y, double cz, double r, int n, double rot) {
		double[][] ring = new double[n][];
		for (int i = 0; i < n; i++) {
			double a = rot + i * 2 * Math.PI / n;
			ring[i] = pt(cx + Math.cos(a) * r, y, cz + Math.sin(a) * r);
		}
		return ring;
	}

	/** The same ring at another height. */
	static double[][] lift(double[][] ring, double y) {
		double[][] out = new double[ring.length][];
		for (int i = 0; i < ring.length; i++) {
			out[i] = pt(ring[i][0], y, ring[i][2]);
		}
		return out;
	}

	/** The ring scaled about its centroid (xz). */
	static double[][] scale(double[][] ring, double f, double y) {
		double cx = 0, cz = 0;
		for (double[] p : ring) {
			cx += p[0];
			cz += p[2];
		}
		cx /= ring.length;
		cz /= ring.length;
		double[][] out = new double[ring.length][];
		for (int i = 0; i < ring.length; i++) {
			out[i] = pt(cx + (ring[i][0] - cx) * f, y, cz + (ring[i][2] - cz) * f);
		}
		return out;
	}

	/** The ring pushed in (negative: out) by a distance, about its centroid. */
	static double[][] inset(double[][] ring, double d, double y) {
		double cx = 0, cz = 0;
		for (double[] p : ring) {
			cx += p[0];
			cz += p[2];
		}
		cx /= ring.length;
		cz /= ring.length;
		double[][] out = new double[ring.length][];
		for (int i = 0; i < ring.length; i++) {
			double dx = ring[i][0] - cx, dz = ring[i][2] - cz, len = Math.max(1.0E-6, Math.sqrt(dx * dx + dz * dz));
			double k = Math.max(0.05, (len - d) / len);
			out[i] = pt(cx + dx * k, y, cz + dz * k);
		}
		return out;
	}

	static double[] centroid(double[][] ring) {
		double cx = 0, cy = 0, cz = 0;
		for (double[] p : ring) {
			cx += p[0];
			cy += p[1];
			cz += p[2];
		}
		return pt(cx / ring.length, cy / ring.length, cz / ring.length);
	}

	/** Ear clipping of a simple polygon in the xz plane; triangles as index triples. */
	static int[] earClip(double[][] ring) {
		int n = ring.length;
		if (n < 3) {
			return new int[0];
		}
		int[] order = new int[n];
		double area = 0;
		for (int i = 0; i < n; i++) {
			double[] a = ring[i], b = ring[(i + 1) % n];
			area += a[0] * b[2] - b[0] * a[2];
		}
		for (int i = 0; i < n; i++) {
			order[i] = area >= 0 ? i : n - 1 - i;
		}
		int[] out = new int[(n - 2) * 3];
		int o = 0, left = n;
		int guard = 0;
		while (left > 3 && guard++ < n * n) {
			boolean clipped = false;
			for (int i = 0; i < left; i++) {
				int ia = order[(i + left - 1) % left], ib = order[i], ic = order[(i + 1) % left];
				double[] a = ring[ia], b = ring[ib], c = ring[ic];
				double cross = (b[0] - a[0]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[0] - a[0]);
				if (cross <= 1.0E-9) {
					continue;
				}
				boolean ear = true;
				for (int j = 0; j < left && ear; j++) {
					int id = order[j];
					if (id == ia || id == ib || id == ic) {
						continue;
					}
					ear = !inTriangle(ring[id], a, b, c);
				}
				if (ear) {
					out[o++] = ia;
					out[o++] = ib;
					out[o++] = ic;
					System.arraycopy(order, i + 1, order, i, left - i - 1);
					left--;
					clipped = true;
					break;
				}
			}
			if (!clipped) {
				break;
			}
		}
		if (left == 3 && o + 3 <= out.length) {
			out[o++] = order[0];
			out[o++] = order[1];
			out[o++] = order[2];
		} else {
			// Degenerate leftovers: fan.
			for (int i = 1; i + 1 < left && o + 3 <= out.length; i++) {
				out[o++] = order[0];
				out[o++] = order[i];
				out[o++] = order[i + 1];
			}
		}
		return o == out.length ? out : java.util.Arrays.copyOf(out, o);
	}

	private static boolean inTriangle(double[] p, double[] a, double[] b, double[] c) {
		double d1 = (p[0] - b[0]) * (a[2] - b[2]) - (a[0] - b[0]) * (p[2] - b[2]);
		double d2 = (p[0] - c[0]) * (b[2] - c[2]) - (b[0] - c[0]) * (p[2] - c[2]);
		double d3 = (p[0] - a[0]) * (c[2] - a[2]) - (c[0] - a[0]) * (p[2] - a[2]);
		boolean neg = d1 < -1.0E-9 || d2 < -1.0E-9 || d3 < -1.0E-9, pos = d1 > 1.0E-9 || d2 > 1.0E-9 || d3 > 1.0E-9;
		return !(neg && pos);
	}

	/** Fills a ring (a flat or gently sloped polygon) facing up or down. */
	static void cap(Soup s, double[][] ring, int mat, int lod, Soup.Surf surf, boolean up) {
		int[] t = earClip(ring);
		double dy = up ? 1 : -1;
		for (int i = 0; i < t.length; i += 3) {
			s.tri(mat, lod, surf, ring[t[i]], ring[t[i + 1]], ring[t[i + 2]], 0, dy, 0);
		}
	}

	/**
	 * A closed prism between two rings of equal length (the sides are quads {@code bot[i], bot[i+1], top[i+1], top[i]}, facing
	 * away from the middle). The caps are optional.
	 */
	static void prism(Soup s, double[][] bot, double[][] top, int matSide, int matTop, int matBot, int lod, long hash, boolean capTop, boolean capBot) {
		prism(s, bot, top, matSide, matTop, matBot, lod, hash, capTop, capBot, null);
	}

	static void prism(Soup s, double[][] bot, double[][] top, int matSide, int matTop, int matBot, int lod, long hash, boolean capTop, boolean capBot, boolean[] skip) {
		int n = bot.length;
		double[] c = centroid(bot);
		Soup.Surf side = new Soup.Surf(matSide, hash, 0);
		for (int i = 0; i < n; i++) {
			if (skip != null && skip[i]) {
				continue;
			}
			int j = (i + 1) % n;
			double mx = (bot[i][0] + bot[j][0]) * 0.5 - c[0], mz = (bot[i][2] + bot[j][2]) * 0.5 - c[2];
			double len = Math.sqrt(mx * mx + mz * mz);
			if (len < 1.0E-9) {
				continue;
			}
			double dx = mx / len, dz = mz / len;
			// Use the quad's own horizontal normal when it has one (a long thin wall seen from its end gives a poor radial direction).
			double ex = bot[j][0] - bot[i][0], ez = bot[j][2] - bot[i][2];
			double nx = ez, nz = -ex;
			double nl = Math.sqrt(nx * nx + nz * nz);
			if (nl > 1.0E-9) {
				nx /= nl;
				nz /= nl;
				if (nx * dx + nz * dz < 0) {
					nx = -nx;
					nz = -nz;
				}
				dx = nx;
				dz = nz;
			}
			s.tri(matSide, lod, side, bot[i], bot[j], top[j], dx, 0, dz);
			s.tri(matSide, lod, side, bot[i], top[j], top[i], dx, 0, dz);
		}
		if (capTop) {
			cap(s, top, matTop, lod, new Soup.Surf(matTop, hash + 1, (hash & 3) * Math.PI / 2), true);
		}
		if (capBot) {
			cap(s, bot, matBot, lod, new Soup.Surf(matBot, hash + 2, (hash & 3) * Math.PI / 2), false);
		}
	}

	/**
	 * Triangle strip between two closed rings (any vertex counts, possibly different heights), zippered by the shorter
	 * diagonal. The faces are oriented towards {@code (dx, dy, dz)}; with {@code radial} the direction is horizontal
	 * and points away from ring {@code a}'s centre (negative scale: towards it).
	 */
	static void bridge(Soup s, double[][] a, double[][] b, int mat, int lod, Soup.Surf surf, double dx, double dy, double dz, boolean radial, double radialSign) {
		int na = a.length, nb = b.length;
		// Start at the closest pair.
		int sa = 0, sb = 0;
		double best = Double.MAX_VALUE;
		for (int i = 0; i < na; i++) {
			for (int j = 0; j < nb; j++) {
				double d = dist2(a[i], b[j]);
				if (d < best) {
					best = d;
					sa = i;
					sb = j;
				}
			}
		}
		double[] c = centroid(a);
		int ia = 0, ib = 0;
		while (ia < na || ib < nb) {
			double[] a0 = a[(sa + ia) % na], b0 = b[(sb + ib) % nb];
			double[] a1 = a[(sa + ia + 1) % na], b1 = b[(sb + ib + 1) % nb];
			boolean advanceA;
			if (ia >= na) {
				advanceA = false;
			} else if (ib >= nb) {
				advanceA = true;
			} else {
				advanceA = dist2(a1, b0) <= dist2(a0, b1);
			}
			double[] t0, t1, t2;
			if (advanceA) {
				t0 = a0;
				t1 = a1;
				t2 = b0;
				ia++;
			} else {
				t0 = a0;
				t1 = b1;
				t2 = b0;
				ib++;
			}
			double ox = dx, oy = dy, oz = dz;
			if (radial) {
				double mx = (t0[0] + t1[0] + t2[0]) / 3 - c[0], mz = (t0[2] + t1[2] + t2[2]) / 3 - c[2];
				ox = mx * radialSign;
				oz = mz * radialSign;
			}
			s.tri(mat, lod, surf, t0, t1, t2, ox, oy, oz);
		}
	}

	private static double dist2(double[] p, double[] q) {
		double x = p[0] - q[0], y = p[1] - q[1], z = p[2] - q[2];
		return x * x + y * y + z * z;
	}

	/** A box rotated about the vertical axis, as a prism; {@code (cx, cz)} centre, half sizes along (cos, sin) of {@code ang}. */
	static void box(Soup s, double cx, double cz, double ang, double hx, double hz, double y0, double y1, int matSide, int matTop, int lod, long hash) {
		double[][] bot = rect(cx, cz, ang, hx, hz, y0), top = rect(cx, cz, ang, hx, hz, y1);
		prism(s, bot, top, matSide, matTop, matSide, lod, hash, true, true);
	}

	static double[][] rect(double cx, double cz, double ang, double hx, double hz, double y) {
		double c = Math.cos(ang), sn = Math.sin(ang);
		double[][] r = new double[4][];
		double[][] k = {{-hx, -hz}, {hx, -hz}, {hx, hz}, {-hx, hz}};
		for (int i = 0; i < 4; i++) {
			r[i] = pt(cx + k[i][0] * c - k[i][1] * sn, y, cz + k[i][0] * sn + k[i][1] * c);
		}
		return r;
	}

	/** Octagon (or any n-gon) column, optionally tapered, from y0 to y1. */
	static void column(Soup s, double cx, double cz, double r0, double r1, double y0, double y1, int n, double rot, int mat, int lod, long hash) {
		prism(s, circle(cx, y0, cz, r0, n, rot), circle(cx, y1, cz, r1, n, rot), mat, mat, mat, lod, hash, true, false);
	}

	/**
	 * A wall piece: plan view a chamfered rectangle from {@code (ax, az)} to {@code (bx, bz)} (extended by {@code ext} at both ends),
	 * thickness {@code t}, corners cut by {@code c}. Sides only, plus the soffit when {@code soffit} (the bottom face).
	 */
	static void wallPiece(Soup s, double ax, double az, double bx, double bz, double t, double c, double extA, double extB, double y0, double y1, int mat, int lod,
			long hash, boolean capTop, boolean capBot) {
		double dx = bx - ax, dz = bz - az, len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.02) {
			return;
		}
		dx /= len;
		dz /= len;
		double nx = -dz, nz = dx, h = t * 0.5;
		double s0 = -extA, s1 = len + extB;
		double cc = Math.min(c, Math.min(h * 0.9, (s1 - s0) * 0.3));
		// Ends buried in other walls (extended) are left open and unbevelled: nobody sees them.
		boolean bevA = extA <= 0, bevB = extB <= 0;
		java.util.List<double[]> k = new java.util.ArrayList<>();
		if (bevA) {
			k.add(new double[] {s0, h - cc});
			k.add(new double[] {s0 + cc, h});
		} else {
			k.add(new double[] {s0, h});
		}
		if (bevB) {
			k.add(new double[] {s1 - cc, h});
			k.add(new double[] {s1, h - cc});
			k.add(new double[] {s1, -h + cc});
			k.add(new double[] {s1 - cc, -h});
		} else {
			k.add(new double[] {s1, h});
			k.add(new double[] {s1, -h});
		}
		if (bevA) {
			k.add(new double[] {s0 + cc, -h});
			k.add(new double[] {s0, -h + cc});
		} else {
			k.add(new double[] {s0, -h});
		}
		int n = k.size();
		double[][] bot = new double[n][], top = new double[n][];
		boolean[] skip = new boolean[n];
		for (int i = 0; i < n; i++) {
			double[] q = k.get(i);
			double x = ax + dx * q[0] + nx * q[1], z = az + dz * q[0] + nz * q[1];
			bot[i] = pt(x, y0, z);
			top[i] = pt(x, y1, z);
			double[] r = k.get((i + 1) % n);
			skip[i] = !bevB && q[0] == s1 && r[0] == s1 || !bevA && q[0] == s0 && r[0] == s0;
		}
		prism(s, bot, top, mat, mat, mat, lod, hash, capTop, capBot, skip);
	}

	/**
	 * A wall piece whose bottom edge is a polyline (the soffit of an arch or a lintel): a prism from the plan quad
	 * {@code A-B} (thickness t) whose bottom heights are {@code yA} and {@code yB} and whose top is {@code y1}.
	 */
	static void lintelStrip(Soup s, double ax, double az, double bx, double bz, double t, double yA, double yB, double y1, int mat, int lod, long hash) {
		double dx = bx - ax, dz = bz - az, len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0E-4) {
			return;
		}
		double nx = -dz / len * t * 0.5, nz = dx / len * t * 0.5;
		double[][] bot = {pt(ax + nx, yA, az + nz), pt(bx + nx, yB, bz + nz), pt(bx - nx, yB, bz - nz), pt(ax - nx, yA, az - nz)};
		double[][] top = {pt(ax + nx, y1, az + nz), pt(bx + nx, y1, bz + nz), pt(bx - nx, y1, bz - nz), pt(ax - nx, y1, az - nz)};
		prism(s, bot, top, mat, mat, mat, lod, hash, false, true);
	}

	/** A spiral stair tread: wedge between radii and angles at top height {@code h}, {@code thick} deep. */
	static void tread(Soup s, double cx, double cz, double rin, double rout, double a0, double a1, double h, double thick, int mat, int lod, long hash) {
		double[][] bot = {pt(cx + Math.cos(a0) * rin, h - thick, cz + Math.sin(a0) * rin), pt(cx + Math.cos(a0) * rout, h - thick, cz + Math.sin(a0) * rout),
			pt(cx + Math.cos(a1) * rout, h - thick, cz + Math.sin(a1) * rout), pt(cx + Math.cos(a1) * rin, h - thick, cz + Math.sin(a1) * rin)};
		double[][] top = lift(bot, h);
		prism(s, bot, top, mat, mat, mat, lod, hash, true, true);
	}

	/** An emissive rectangular panel flush under a ceiling (faces down). */
	static void panel(Soup s, double cx, double cz, double ang, double hx, double hz, double y, int mat, int lod) {
		double[][] r = rect(cx, cz, ang, hx, hz, y);
		Soup.Surf surf = new Soup.Surf(mat, 5, ang);
		s.tri(mat, lod, surf, r[0], r[1], r[2], 0, -1, 0);
		s.tri(mat, lod, surf, r[0], r[2], r[3], 0, -1, 0);
	}
}
