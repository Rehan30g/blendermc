package dev.echoliminal.endless.gen;

import static dev.echoliminal.endless.gen.Soup.L0;
import static dev.echoliminal.endless.gen.Soup.L01;
import static dev.echoliminal.endless.gen.Soup.L012;
import static dev.echoliminal.endless.gen.Soup.L2;

import dev.echoliminal.endless.gen.World.Edge;
import dev.echoliminal.endless.gen.World.Feat;
import dev.echoliminal.endless.gen.World.Macro;
import dev.echoliminal.endless.gen.World.Mod;

/**
 * Builds the triangles of one module (all its storeys, its west and north walls, its corner post, its field terrain and
 * features) from the {@link World} description. Everything is free-form geometry; nothing here knows about the block grid.
 */
final class Builder {
	private final World w;

	Builder(World w) {
		this.w = w;
	}

	/** Local frame of a module: origin at the centre of its outline, x axis along its north edge. */
	private static final class Frame {
		final double cx, cz, cs, sn, ang;

		Frame(double cx, double cz, double ang) {
			this.cx = cx;
			this.cz = cz;
			this.ang = ang;
			this.cs = Math.cos(ang);
			this.sn = Math.sin(ang);
		}

		double x(double u, double v) {
			return this.cx + u * this.cs - v * this.sn;
		}

		double z(double u, double v) {
			return this.cz + u * this.sn + v * this.cs;
		}
	}

	private Frame frame(Mod m) {
		double cx = (w.nodeX(m.mx, m.mz) + w.nodeX(m.mx + 1, m.mz) + w.nodeX(m.mx, m.mz + 1) + w.nodeX(m.mx + 1, m.mz + 1)) / 4;
		double cz = (w.nodeZ(m.mx, m.mz) + w.nodeZ(m.mx + 1, m.mz) + w.nodeZ(m.mx, m.mz + 1) + w.nodeZ(m.mx + 1, m.mz + 1)) / 4;
		double ang = Math.atan2(w.nodeZ(m.mx + 1, m.mz) - w.nodeZ(m.mx, m.mz), w.nodeX(m.mx + 1, m.mz) - w.nodeX(m.mx, m.mz));
		return new Frame(cx, cz, ang);
	}

	/** Centre of a module (used for spawn and tests). */
	double[] centre(Mod m) {
		Frame f = this.frame(m);
		return new double[] {f.cx, f.cz};
	}

	Soup build(int mx, int mz) {
		Soup s = new Soup();
		Mod m = w.mod(mx, mz);
		this.edgeWalls(s, 0, mx, mz);
		this.edgeWalls(s, 1, mx, mz);
		this.post(s, mx, mz);
		if (m.field()) {
			this.field(s, m);
		} else {
			for (int k = m.mac.lo; k <= m.mac.hi; k++) {
				this.storey(s, m, k);
			}
			if (m.core) {
				this.core(s, m);
			}
			if (m.atrium) {
				this.atriumLamp(s, m);
			}
		}
		return s;
	}

	private static double[][] at(double[][] outline, double y) {
		return Geo.lift(outline, y);
	}

	private int floorMat(Mod m, int k) {
		if (m.pool()) {
			return Mats.TILE;
		}
		return w.rndK(290, m.mx, m.mz, k) < 0.12 ? Mats.PLANK : Mats.CARPET;
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Walls

	private void edgeWalls(Soup s, int axis, int mx, int mz) {
		Mod a = axis == 0 ? w.mod(mx - 1, mz) : w.mod(mx, mz - 1), b = w.mod(mx, mz);
		if (a.field() && b.field()) {
			return;
		}
		int kLo = Integer.MAX_VALUE, kHi = Integer.MIN_VALUE;
		for (Mod x : new Mod[] {a, b}) {
			if (!x.field()) {
				kLo = Math.min(kLo, x.mac.lo);
				kHi = Math.max(kHi, x.mac.hi);
			}
		}
		double[] mid = w.edgeMid(axis, mx, mz);
		double[][] pts = {{w.nodeX(mx, mz), w.nodeZ(mx, mz)}, mid,
			axis == 0 ? new double[] {w.nodeX(mx, mz + 1), w.nodeZ(mx, mz + 1)} : new double[] {w.nodeX(mx + 1, mz), w.nodeZ(mx + 1, mz)}};
		boolean pool = a.pool() || b.pool();
		int wallMat = pool ? Mats.TILE : Mats.WALLPAPER;
		for (int k = kLo; k <= kHi; k++) {
			Edge e = w.edge(axis, mx, mz, k);
			if (e.state() == World.NONE) {
				continue;
			}
			boolean ea = w.exists(a, k), eb = w.exists(b, k);
			Macro ref = ea ? a.mac : b.mac;
			double fl = ref.floor(k);
			double y0 = fl - 0.08;
			double ca = ea ? w.ceilY(a, k) : -1e9, cb = eb ? w.ceilY(b, k) : -1e9;
			double cmax = Math.max(ca, cb), cmin = Math.min(ea ? ca : 1e9, eb ? cb : 1e9);
			boolean roof = ea && k == a.mac.hi || eb && k == b.mac.hi;
			double top = cmax + 0.1;
			long hash = w.hashK(300 + axis, mx, mz, k);
			double t = e.thick(), bev = e.bevel();
			for (int seg = 0; seg < 2; seg++) {
				double[] p = pts[seg], q = pts[seg + 1];
				double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
				double ext = t * 0.5;
				long sh = hash + seg * 977L;
				if (e.state() == World.WALL || e.seg() != seg && e.state() != World.OPEN) {
					this.span(s, p, q, 0, len, t, bev, ext, ext, y0, top, fl, wallMat, sh, roof, ca > -1e8 ? ca : cb, true);
				} else if (e.state() == World.OPEN) {
					if (cmax - cmin > 0.06) {
						this.piece(s, p, q, 0, len, t, bev, ext, ext, cmin, top, wallMat, sh, roof, true);
					}
				} else {
					double width = Math.min(e.width(), len - 1.0);
					if (width < 1.25) {
						this.span(s, p, q, 0, len, t, bev, ext, ext, y0, top, fl, wallMat, sh, roof, ca > -1e8 ? ca : cb, true);
						continue;
					}
					double c = e.at() * len, s0 = c - width / 2, s1 = c + width / 2;
					this.span(s, p, q, 0, s0, t, bev, ext, 0, y0, top, fl, wallMat, sh, roof, ca > -1e8 ? ca : cb, true);
					this.span(s, p, q, s1, len, t, bev, 0, ext, y0, top, fl, wallMat, sh + 1, roof, ca > -1e8 ? ca : cb, true);
					// Sill under the opening.
					if (!(ea && eb)) {
						this.piece(s, p, q, s0, s1, t, bev, 0, 0, fl - 0.6, fl, wallMat, sh + 2, false, false);
					}
					if (e.state() == World.DOOR) {
						double dh = Math.min(e.height(), cmin - fl - 0.5);
						this.piece(s, p, q, s0, s1, t, bev, 0, 0, fl + dh, top, wallMat, sh + 3, roof, true);
					} else {
						this.arch(s, p, q, s0, s1, t, fl, top, Math.min(cmin - 0.4, top), wallMat, sh + 3);
					}
				}
			}
		}
	}

	/** A plain wall piece along a segment between arc positions. */
	private void piece(Soup s, double[] p, double[] q, double from, double to, double t, double bev, double extA, double extB, double y0, double y1, int mat,
			long hash, boolean capTop, boolean capBot) {
		double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
		if (to - from < 0.03 || y1 - y0 < 0.03) {
			return;
		}
		double dx = (q[0] - p[0]) / len, dz = (q[1] - p[1]) / len;
		Geo.wallPiece(s, p[0] + dx * from, p[1] + dz * from, p[0] + dx * to, p[1] + dz * to, t, bev, extA, extB, y0, y1, mat, L012, hash, capTop, capBot);
	}

	/** A solid wall span with skirting on both faces. */
	private void span(Soup s, double[] p, double[] q, double from, double to, double t, double bev, double extA, double extB, double y0, double y1, double floor,
			int mat, long hash, boolean capTop, double ceil, boolean skirt) {
		if (to - from < 0.03) {
			return;
		}
		this.piece(s, p, q, from, to, t, bev, extA, extB, y0, y1, mat, hash, capTop, false);
		if (skirt && to - from > 0.5) {
			double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
			double dx = (q[0] - p[0]) / len, dz = (q[1] - p[1]) / len;
			double nx = -dz, nz = dx;
			for (int side = -1; side <= 1; side += 2) {
				double off = side * t * 0.5;
				// A bevelled strip: one sloped quad from the floor out in the room up to the wall face.
				double[] f0 = Geo.pt(p[0] + dx * from + nx * (off + side * 0.07), floor, p[1] + dz * from + nz * (off + side * 0.07));
				double[] f1 = Geo.pt(p[0] + dx * to + nx * (off + side * 0.07), floor, p[1] + dz * to + nz * (off + side * 0.07));
				double[] w1 = Geo.pt(p[0] + dx * to + nx * off, floor + 0.2, p[1] + dz * to + nz * off);
				double[] w0 = Geo.pt(p[0] + dx * from + nx * off, floor + 0.2, p[1] + dz * from + nz * off);
				Soup.Surf surf = Soup.Surf.of(Mats.SKIRTING, hash + 11 + side);
				s.tri(Mats.SKIRTING, L0, surf, f0, f1, w1, nx * side, 0.4, nz * side);
				s.tri(Mats.SKIRTING, L0, surf, f0, w1, w0, nx * side, 0.4, nz * side);
			}
		}
	}

	/** An arched opening: jambs up to the springline are the neighbouring spans; here the curved lintel is cut in strips. */
	private void arch(Soup s, double[] p, double[] q, double from, double to, double t, double floor, double top, double maxTop, int mat, long hash) {
		double len = Math.hypot(q[0] - p[0], q[1] - p[1]);
		double dx = (q[0] - p[0]) / len, dz = (q[1] - p[1]) / len;
		double r = (to - from) / 2, mid = (from + to) / 2;
		double spring = floor + 2.15;
		double rise = Math.min(r * 0.85, maxTop - spring - 0.05);
		int n = 8;
		for (int i = 0; i < n; i++) {
			double u0 = -r + 2 * r * i / n, u1 = -r + 2 * r * (i + 1) / n;
			double h0 = spring + rise * Math.sqrt(Math.max(0, 1 - (u0 / r) * (u0 / r))), h1 = spring + rise * Math.sqrt(Math.max(0, 1 - (u1 / r) * (u1 / r)));
			double ax = p[0] + dx * (mid + u0), az = p[1] + dz * (mid + u0), bx = p[0] + dx * (mid + u1), bz = p[1] + dz * (mid + u1);
			Geo.lintelStrip(s, ax, az, bx, bz, t, h0, h1, top, mat, L012, hash + i);
		}
	}

	private void post(Soup s, int mx, int mz) {
		Mod[] adj = {w.mod(mx - 1, mz - 1), w.mod(mx, mz - 1), w.mod(mx - 1, mz), w.mod(mx, mz)};
		int kLo = Integer.MAX_VALUE, kHi = Integer.MIN_VALUE;
		for (Mod x : adj) {
			if (!x.field()) {
				kLo = Math.min(kLo, x.mac.lo);
				kHi = Math.max(kHi, x.mac.hi);
			}
		}
		if (kLo > kHi) {
			return;
		}
		double cx = w.nodeX(mx, mz), cz = w.nodeZ(mx, mz);
		for (int k = kLo; k <= kHi; k++) {
			double cmax = -1e9, fl = 0, y0 = 0;
			boolean any = false, roof = false;
			for (Mod x : adj) {
				if (w.exists(x, k)) {
					cmax = Math.max(cmax, w.ceilY(x, k));
					if (!any) {
						fl = x.mac.floor(k);
						y0 = fl - 0.08;
					}
					any = true;
					roof |= k == x.mac.hi;
				}
			}
			if (!any) {
				continue;
			}
			Edge[] es = {w.edge(0, mx, mz, k), w.edge(1, mx, mz, k), w.edge(0, mx, mz - 1, k), w.edge(1, mx - 1, mz, k)};
			double thick = 0;
			boolean walls = false;
			for (Edge e : es) {
				if (e.state() == World.WALL || e.state() == World.DOOR || e.state() == World.ARCH) {
					walls = true;
					thick = Math.max(thick, e.thick());
				}
			}
			long h = w.hashK(310, mx, mz, k);
			boolean pool = adj[3].pool() || adj[0].pool();
			int mat = pool ? Mats.TILE : Mats.WALLPAPER;
			if (walls) {
				Geo.column(s, cx, cz, thick * 0.62 + 0.04, thick * 0.62 + 0.04, y0, cmax + 0.1, 8, Noise.unit(h) * Math.PI, mat, L012, h);
			} else if (!(mx % 4 == 0 && mz % 4 == 0) && Noise.unit(Noise.mix(h + 5)) < 0.3) {
				double r = 0.3 + Noise.unit(Noise.mix(h + 6)) * 0.15;
				Geo.column(s, cx, cz, r, r, y0, cmax + 0.1, 8, Noise.unit(h) * Math.PI, pool ? Mats.TILE : Mats.WALLPAPER, L012, h);
			}
		}
	}

	// ----------------------------------------------------------------------------------------------------------------
	// A storey of a building module

	private void storey(Soup s, Mod m, int k) {
		Macro mac = m.mac;
		Frame f = this.frame(m);
		double F = mac.floor(k), C = w.ceilY(m, k);
		boolean lowest = k == mac.lo, topmost = k == mac.hi;
		double[][] outline = w.outline(m.mx, m.mz);
		long h = w.hashK(320, m.mx, m.mz, k);
		int fmat = this.floorMat(m, k);
		int ceilMat = m.pool() ? Mats.POOL_CEILING : Mats.CEILING_TILE;
		double th = lowest ? 1.2 : 0.6;
		// Holes in this floor slab.
		double[][] hole = null;
		boolean sunken = false;
		double sinkR = 2.7;
		if (k > mac.lo) {
			if (m.core) {
				hole = Geo.circle(f.cx, F, f.cz, 2.42, 16, f.ang);
			} else if (m.atrium) {
				hole = Geo.scale(at(outline, F), 0.45, F);
			}
		} else if (!m.core && !m.atrium && !m.street && !m.pool() && w.rndK(330, m.mx, m.mz, k) < 0.12 && C - F >= 4.0) {
			sunken = true;
			hole = Geo.circle(f.cx, F, f.cz, sinkR, 12, f.ang + 0.2);
		}
		Soup.Surf top = new Soup.Surf(fmat, h, f.ang + (h % 2 == 0 ? 0 : Math.PI / 2));
		Soup.Surf bot = new Soup.Surf(lowest ? Mats.CONCRETE : ceilMat, h + 3, f.ang);
		double[][] oTop = at(outline, F), oBot = at(outline, F - th);
		if (hole == null) {
			Geo.cap(s, oTop, fmat, L012, top, true);
			Geo.cap(s, oBot, lowest ? Mats.CONCRETE : ceilMat, L012, bot, false);
		} else {
			Geo.bridge(s, oTop, hole, fmat, L012, top, 0, 1, 0, false, 1);
			if (!sunken) {
				double[][] holeBot = Geo.lift(hole, F - th);
				Geo.bridge(s, hole, holeBot, Mats.CONCRETE, L012, new Soup.Surf(Mats.CONCRETE, h + 5, 0), 0, 0, 0, true, -1);
				Geo.bridge(s, oBot, holeBot, lowest ? Mats.CONCRETE : ceilMat, L012, bot, 0, -1, 0, false, 1);
			} else {
				Geo.cap(s, oBot, Mats.CONCRETE, L012, bot, false);
				this.sunkenPit(s, f, F, th, h);
			}
		}
		if (m.atrium && k > mac.lo) {
			this.rail(s, Geo.scale(at(outline, F), 0.47, F), F, h);
		}
		// Roof slab over the topmost storey.
		if (topmost) {
			Geo.cap(s, at(outline, C), ceilMat, L012, new Soup.Surf(ceilMat, h + 7, f.ang), false);
			Geo.cap(s, at(outline, C + 0.8), Mats.CONCRETE, L012, new Soup.Surf(Mats.CONCRETE, h + 9, f.ang), true);
		}
		this.lights(s, m, k, f, C, h);
		this.interior(s, m, k, f, F, C, h);
	}

	private void sunkenPit(Soup s, Frame f, double F, double th, long h) {
		double[] radii = {2.7, 2.1, 1.5};
		int n = 12;
		double rot = f.ang + 0.2;
		for (int j = 0; j < radii.length; j++) {
			double y = F - 0.4 * (j + 1);
			double rOuter = radii[j], rInner = j + 1 < radii.length ? radii[j + 1] : 0.0;
			// Riser at the outer radius, from the upper level down to this tread.
			Geo.bridge(s, Geo.circle(f.cx, F - 0.4 * j, f.cz, rOuter, n, rot), Geo.circle(f.cx, y, f.cz, rOuter, n, rot), Mats.STAIR, L012, new Soup.Surf(Mats.STAIR, h + j, 0), 0,
				0, 0, true, -1);
			if (rInner > 0) {
				Geo.bridge(s, Geo.circle(f.cx, y, f.cz, rOuter, n, rot), Geo.circle(f.cx, y, f.cz, rInner, n, rot), Mats.STAIR, L012, new Soup.Surf(Mats.STAIR, h + 20 + j, 0),
					0, 1, 0, false, 1);
			} else {
				Geo.cap(s, Geo.circle(f.cx, y, f.cz, rOuter, n, rot), Mats.CONCRETE, L012, new Soup.Surf(Mats.CONCRETE, h + 30, 0), true);
			}
		}
		double[][] r = Geo.rect(f.cx, f.cz, f.ang, 0.7, 0.7, F - 1.19);
		Soup.Surf surf = new Soup.Surf(Mats.STRIP, 5, f.ang);
		s.tri(Mats.STRIP, L012, surf, r[0], r[1], r[2], 0, 1, 0);
		s.tri(Mats.STRIP, L012, surf, r[0], r[2], r[3], 0, 1, 0);
	}

	/** Thin metal handrail on the ring of points. */
	private void rail(Soup s, double[][] ring, double F, long h) {
		int n = ring.length;
		for (int i = 0; i < n; i++) {
			double[] a = ring[i], b = ring[(i + 1) % n];
			Geo.wallPiece(s, a[0], a[2], b[0], b[2], 0.09, 0.02, 0.05, 0.05, F - 0.1, F + 1.05, Mats.METAL, L01, h + 40 + i, true, false);
		}
	}

	private void lights(Soup s, Mod m, int k, Frame f, double C, long h) {
		double r = Noise.unit(Noise.mix(h + 50));
		boolean dead = r < 0.05, dark = r > 0.975;
		if (dark) {
			return;
		}
		int mat = m.pool() ? Mats.POOL_PANEL : dead ? Mats.DEAD_PANEL : Mats.LIGHT_PANEL;
		boolean coffer = !m.core && !m.atrium && C - w.mod(m.mx, m.mz).mac.floor(k) >= 4.0 && Noise.unit(Noise.mix(h + 51)) < 0.28;
		double y = C - 0.03;
		if (coffer) {
			Macro mac = m.mac;
			double depth = 0.45;
			double[][] outline = w.outline(m.mx, m.mz);
			double[][] topRing = Geo.scale(at(outline, C), 0.68, C), botRing = Geo.scale(at(outline, C - depth), 0.5, C - depth);
			Geo.prism(s, botRing, topRing, m.pool() ? Mats.POOL_CEILING : Mats.CEILING_TILE, Mats.CEILING_TILE, m.pool() ? Mats.POOL_CEILING : Mats.CEILING_TILE, L012, h + 52,
				false, true);
			y = C - depth - 0.03;
		}
		double ang = f.ang + (Noise.unit(Noise.mix(h + 53)) < 0.5 ? 0 : Math.PI / 2);
		if (m.core || m.atrium) {
			double[][] outline = Geo.lift(w.outline(m.mx, m.mz), C);
			for (int i = 0; i < 8; i += 2) {
				double[] p = Geo.scale(outline, 0.8, C)[i];
				Geo.panel(s, p[0], p[2], f.ang, 0.55, 1.0, y, mat, L012);
			}
			if (m.atrium && k == m.mac.hi) {
				Geo.panel(s, f.cx, f.cz, f.ang, 1.7, 1.7, y, Mats.LIGHT_PANEL, L012);
			}
			return;
		}
		if (m.pool()) {
			for (int i = 0; i < 4; i++) {
				double u = (i & 1) == 0 ? -2.0 : 2.0, v = (i & 2) == 0 ? -2.0 : 2.0;
				Geo.panel(s, f.x(u, v), f.z(u, v), f.ang, 0.95, 0.95, y, mat, L012);
			}
		} else {
			double off = coffer ? 1.3 : 2.1;
			for (int i = -1; i <= 1; i += 2) {
				Geo.panel(s, f.x(i * off, 0), f.z(i * off, 0), ang, 0.6, 1.2, y, mat, L012);
			}
			if (Noise.unit(Noise.mix(h + 54)) < 0.6) {
				Geo.panel(s, f.x(0, 2.9), f.z(0, 2.9), ang, 0.5, 0.9, y, mat, L012);
				Geo.panel(s, f.x(0, -2.9), f.z(0, -2.9), ang, 0.5, 0.9, y, mat, L012);
			}
		}
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Inside a module

	private void interior(Soup s, Mod m, int k, Frame f, double F, double C, long h) {
		if (m.core || m.atrium || m.intersection) {
			return;
		}
		double r = Noise.unit(Noise.mix(h + 60));
		double height = C - F;
		int wall = m.pool() ? Mats.TILE : Mats.WALLPAPER;
		if (m.pool()) {
			if (r < 0.65) {
				this.plinth(s, f, F, h);
			} else if (r < 0.85) {
				this.pillars(s, f, F, C, wall, h);
			}
			return;
		}
		if (m.street) {
			if (r < 0.3) {
				this.pillars(s, f, F, C, wall, h);
			}
			return;
		}
		if (r < 0.28) {
			return;
		} else if (r < 0.42) {
			this.pillars(s, f, F, C, wall, h);
		} else if (r < 0.52) {
			this.colonnade(s, f, F, C, wall, h);
		} else if (r < 0.62) {
			this.partition(s, f, F, C, wall, h);
		} else if (r < 0.74) {
			this.dais(s, f, F, h);
		} else if (r < 0.80 && height >= 5.0) {
			this.table(s, f, F, h);
		} else if (r < 0.85 && height >= 5.6) {
			this.stairNowhere(s, f, F, h);
		} else if (r < 0.89 && height >= 5.8) {
			this.floatingSlab(s, f, F, h);
		} else if (r < 0.93) {
			Geo.column(s, f.cx, f.cz, 0.95, 0.95, F - 0.3, C + 0.2, 8, f.ang + Math.PI / 8, wall, L012, h);
		}
	}

	private void pillars(Soup s, Frame f, double F, double C, int mat, long h) {
		for (int i = 0; i < 4; i++) {
			double u = (i & 1) == 0 ? -2.1 : 2.1, v = (i & 2) == 0 ? -2.1 : 2.1;
			double x = f.x(u, v), z = f.z(u, v);
			Geo.column(s, x, z, 0.34, 0.34, F - 0.3, C + 0.2, 8, f.ang + Math.PI / 8, mat, L012, h + i);
			Geo.column(s, x, z, 0.52, 0.36, F, F + 0.3, 8, f.ang + Math.PI / 8, Mats.SKIRTING, L0, h + 10 + i);
			Geo.column(s, x, z, 0.36, 0.52, C - 0.3, C, 8, f.ang + Math.PI / 8, Mats.SKIRTING, L0, h + 20 + i);
		}
	}

	private void colonnade(Soup s, Frame f, double F, double C, int mat, long h) {
		int n = 7;
		for (int i = 0; i < n; i++) {
			double a = f.ang + i * 2 * Math.PI / n;
			Geo.column(s, f.cx + Math.cos(a) * 2.6, f.cz + Math.sin(a) * 2.6, 0.27, 0.27, F - 0.3, C + 0.2, 8, a, mat, L012, h + i);
		}
	}

	private void partition(Soup s, Frame f, double F, double C, int mat, long h) {
		double a = f.ang + (Noise.unit(Noise.mix(h + 70)) - 0.5) * 0.9;
		double len = 2.2 + Noise.unit(Noise.mix(h + 71)) * 0.7;
		double ax = f.cx - Math.cos(a) * len, az = f.cz - Math.sin(a) * len, bx = f.cx + Math.cos(a) * len, bz = f.cz + Math.sin(a) * len;
		this.span(s, new double[] {ax, az}, new double[] {bx, bz}, 0, 2 * len, 0.26, 0.05, 0.1, 0.1, F - 0.3, C + 0.2, F, mat, h + 73, false, C, true);
	}

	private void dais(Soup s, Frame f, double F, long h) {
		double[] radii = {2.7, 2.1, 1.5};
		for (int j = 0; j < radii.length; j++) {
			Geo.prism(s, Geo.circle(f.cx, F - 0.1, f.cz, radii[j], 8, f.ang + Math.PI / 8), Geo.circle(f.cx, F + 0.4 * (j + 1), f.cz, radii[j], 8, f.ang + Math.PI / 8),
				Mats.STAIR, Mats.STAIR, Mats.STAIR, L012, h + j, true, false);
		}
	}

	private void table(Soup s, Frame f, double F, long h) {
		double hh = 3.0;
		Geo.box(s, f.cx, f.cz, f.ang + 0.2, 2.6, 1.5, F + hh, F + hh + 0.35, Mats.PLANK, Mats.PLANK, L012, h);
		for (int i = 0; i < 4; i++) {
			double u = (i & 1) == 0 ? -2.2 : 2.2, v = (i & 2) == 0 ? -1.1 : 1.1;
			double c = Math.cos(0.2), sn = Math.sin(0.2);
			double x = f.x(u * c - v * sn, u * sn + v * c), z = f.z(u * c - v * sn, u * sn + v * c);
			Geo.column(s, x, z, 0.26, 0.2, F - 0.2, F + hh, 8, f.ang, Mats.PLANK, L012, h + 1 + i);
		}
	}

	private void stairNowhere(Soup s, Frame f, double F, long h) {
		int steps = 7;
		double rise = 0.42, run = 0.6;
		double v0 = -3.0;
		for (int i = 0; i < steps; i++) {
			double v = v0 + run * (i + 0.5);
			Geo.box(s, f.x(0, v), f.z(0, v), f.ang + Math.PI / 2, run / 2 + 0.001, 0.8, F - 0.1, F + rise * (i + 1), Mats.STAIR, Mats.STAIR, L012, h + i);
		}
		double top = F + rise * steps, vEnd = v0 + run * steps;
		Geo.box(s, f.x(0, vEnd + 0.6), f.z(0, vEnd + 0.6), f.ang + Math.PI / 2, 0.6, 0.8, F - 0.1, top, Mats.STAIR, Mats.STAIR, L012, h + 20);
		double dv = vEnd + 0.7;
		Geo.box(s, f.x(-0.62, dv), f.z(-0.62, dv), f.ang, 0.09, 0.09, top, top + 2.4, Mats.FRAME, Mats.FRAME, L012, h + 21);
		Geo.box(s, f.x(0.62, dv), f.z(0.62, dv), f.ang, 0.09, 0.09, top, top + 2.4, Mats.FRAME, Mats.FRAME, L012, h + 22);
		Geo.box(s, f.x(0, dv), f.z(0, dv), f.ang, 0.72, 0.09, top + 2.4, top + 2.58, Mats.FRAME, Mats.FRAME, L012, h + 23);
	}

	private void floatingSlab(Soup s, Frame f, double F, long h) {
		int from = s.count;
		Geo.prism(s, Geo.circle(f.cx, F + 3.3, f.cz, 2.3, 8, f.ang), Geo.circle(f.cx, F + 3.7, f.cz, 2.3, 8, f.ang), Mats.CONCRETE, Mats.STAIR, Mats.CONCRETE, L012, h, true, true);
		s.transform(from, f.cx, F + 3.5, f.cz, 0, 0.16, Noise.unit(Noise.mix(h + 80)) * Math.PI * 2);
	}

	/** A stepped round plinth with a sloped basin in it, water in the basin. */
	private void plinth(Soup s, Frame f, double F, long h) {
		int n = 12;
		double rot = f.ang + 0.13;
		double rA = 3.2, rB = 2.6, rRim = 1.7, rBot = 0.8;
		double yA = F + 0.45, yB = F + 0.9, yBot = F + 0.35;
		Soup.Surf surf = new Soup.Surf(Mats.TILE, h, rot);
		Geo.bridge(s, Geo.circle(f.cx, F, f.cz, rA, n, rot), Geo.circle(f.cx, yA, f.cz, rA, n, rot), Mats.TILE, L012, surf, 0, 0, 0, true, 1);
		Geo.bridge(s, Geo.circle(f.cx, yA, f.cz, rA, n, rot), Geo.circle(f.cx, yA, f.cz, rB, n, rot), Mats.TILE, L012, surf, 0, 1, 0, false, 1);
		Geo.bridge(s, Geo.circle(f.cx, yA, f.cz, rB, n, rot), Geo.circle(f.cx, yB, f.cz, rB, n, rot), Mats.TILE, L012, surf, 0, 0, 0, true, 1);
		Geo.bridge(s, Geo.circle(f.cx, yB, f.cz, rB, n, rot), Geo.circle(f.cx, yB, f.cz, rRim, n, rot), Mats.TILE, L012, surf, 0, 1, 0, false, 1);
		Geo.bridge(s, Geo.circle(f.cx, yB, f.cz, rRim, n, rot), Geo.circle(f.cx, yBot, f.cz, rBot, n, rot), Mats.TILE_BLUE, L012, new Soup.Surf(Mats.TILE_BLUE, h + 1, rot), 0,
			1, 0, false, 1);
		Geo.cap(s, Geo.circle(f.cx, yBot, f.cz, rBot, n, rot), Mats.TILE_BLUE, L012, new Soup.Surf(Mats.TILE_BLUE, h + 2, rot), true);
		double wy = yB - 0.2;
		double[][] ring = Geo.circle(f.cx, wy, f.cz, rRim - 0.12, n, rot);
		Soup.Surf ws = new Soup.Surf(Mats.WATER, h + 3, rot);
		Geo.cap(s, ring, Mats.WATER, L012, ws, true);
		Geo.cap(s, ring, Mats.WATER, L012, ws, false);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Stair core and atrium

	private void core(Soup s, Mod m) {
		Macro mac = m.mac;
		Frame f = this.frame(m);
		int perTurn = mac.steps;
		int total = (mac.hi - mac.lo) * perTurn + (mac.coreIntoCeiling ? perTurn : 0);
		double base = mac.floor(mac.lo);
		double dir = mac.spinCw ? -1 : 1;
		double a0 = w.rnd(340, m.mx, m.mz) * Math.PI * 2;
		double da = 2 * Math.PI / perTurn;
		long h = w.hashK(341, m.mx, m.mz, 0);
		double roof = w.ceilY(m, mac.hi) + 0.8;
		Geo.column(s, f.cx, f.cz, 0.7, 0.7, base - 0.6, roof, 8, a0, Mats.STAIR, L012, h);
		for (int i = 0; i < total; i++) {
			double top = base + 0.4 * (i + 1);
			double aa = a0 + dir * i * da;
			double bb = aa + dir * da * 1.05;
			Geo.tread(s, f.cx, f.cz, 0.62, 2.3, aa, bb, top, 0.75, Mats.STAIR, L012, h + 100 + i);
		}
	}

	private void atriumLamp(Soup s, Mod m) {
		Frame f = this.frame(m);
		double F = m.mac.floor(m.mac.lo);
		double[][] r = Geo.rect(f.cx, f.cz, f.ang, 0.9, 0.9, F + 0.03);
		Soup.Surf surf = new Soup.Surf(Mats.STRIP, 9, f.ang);
		s.tri(Mats.STRIP, L012, surf, r[0], r[1], r[2], 0, 1, 0);
		s.tri(Mats.STRIP, L012, surf, r[0], r[2], r[3], 0, 1, 0);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Field

	private static final Soup.Surf[] GRASS_SURF = new Soup.Surf[Mats.ALL.size()];

	private static Soup.Surf groundSurf(int mat) {
		Soup.Surf s = GRASS_SURF[mat];
		if (s == null) {
			s = new Soup.Surf(mat, 1234 + mat, 0.6);
			GRASS_SURF[mat] = s;
		}
		return s;
	}

	private int groundMat(double x, double z) {
		double n = Noise.fbm(w.seed, 8, x / 37.0, z / 37.0);
		if (n > 0.64) {
			return Mats.GRASS_B;
		}
		return Noise.fbm(w.seed, 9, x / 71.0 + 5, z / 71.0) > 0.68 ? Mats.GRASS_C : Mats.GRASS_A;
	}

	private void field(Soup s, Mod m) {
		double[][] outline = w.outline(m.mx, m.mz);
		this.terrain(s, outline, 2, L01);
		this.terrain(s, outline, 4, L2);
		this.poles(s, m, outline);
		Macro mac = m.mac;
		if (mac.feat != null && m.offI == 2 && m.offJ == 2) {
			this.feature(s, mac.feat, mac.mx, mac.mz);
		}
	}

	/** Heightfield triangles at grid {@code step}, clipped to the module outline. */
	private void terrain(Soup s, double[][] outline, int step, int lod) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double[] p : outline) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[2]);
			maxZ = Math.max(maxZ, p[2]);
		}
		int gx0 = Math.floorDiv((int) Math.floor(minX), step), gx1 = Math.floorDiv((int) Math.ceil(maxX), step);
		int gz0 = Math.floorDiv((int) Math.floor(minZ), step), gz1 = Math.floorDiv((int) Math.ceil(maxZ), step);
		int nx = gx1 - gx0 + 2, nz = gz1 - gz0 + 2;
		// Heights with a one cell margin, so every grid vertex has a normal from central differences.
		double[][] hm = new double[nz + 2][nx + 2];
		for (int j = 0; j < nz + 2; j++) {
			for (int i = 0; i < nx + 2; i++) {
				hm[j][i] = w.ground((gx0 + i - 1) * step, (gz0 + j - 1) * step);
			}
		}
		double[][] hts = new double[nz][nx];
		double[][][] gn = new double[nz][nx][];
		for (int j = 0; j < nz; j++) {
			for (int i = 0; i < nx; i++) {
				hts[j][i] = hm[j + 1][i + 1];
				double gxs = (hm[j + 1][i + 2] - hm[j + 1][i]) / (2.0 * step), gzs = (hm[j + 2][i + 1] - hm[j][i + 1]) / (2.0 * step);
				double len = Math.sqrt(gxs * gxs + 1 + gzs * gzs);
				gn[j][i] = new double[] {-gxs / len, 1 / len, -gzs / len};
			}
		}
		int[] tris = Geo.earClip(outline);
		for (int j = 0; j < nz - 1; j++) {
			for (int i = 0; i < nx - 1; i++) {
				double x0 = (gx0 + i) * step, x1 = x0 + step, z0 = (gz0 + j) * step, z1 = z0 + step;
				double[][] v = {{x0, hts[j][i], z0}, {x1, hts[j][i + 1], z0}, {x1, hts[j + 1][i + 1], z1}, {x0, hts[j + 1][i], z1}};
				double[][] n = {gn[j][i], gn[j][i + 1], gn[j + 1][i + 1], gn[j + 1][i]};
				// Each corner carries its normal as extra components for the clipper.
				double[][] vn = new double[4][];
				for (int c = 0; c < 4; c++) {
					vn[c] = new double[] {v[c][0], v[c][1], v[c][2], n[c][0], n[c][1], n[c][2]};
				}
				if (((gx0 + i + gz0 + j) & 1) == 0) {
					this.terrainTri(s, outline, tris, new double[][] {vn[0], vn[1], vn[2]}, lod);
					this.terrainTri(s, outline, tris, new double[][] {vn[0], vn[2], vn[3]}, lod);
				} else {
					this.terrainTri(s, outline, tris, new double[][] {vn[0], vn[1], vn[3]}, lod);
					this.terrainTri(s, outline, tris, new double[][] {vn[1], vn[2], vn[3]}, lod);
				}
			}
		}
	}

	private void terrainTri(Soup s, double[][] outline, int[] ear, double[][] tri, int lod) {
		// Quick reject / accept against the outline's bounding box.
		double minX = Math.min(tri[0][0], Math.min(tri[1][0], tri[2][0])), maxX = Math.max(tri[0][0], Math.max(tri[1][0], tri[2][0]));
		double minZ = Math.min(tri[0][2], Math.min(tri[1][2], tri[2][2])), maxZ = Math.max(tri[0][2], Math.max(tri[1][2], tri[2][2]));
		boolean allIn = true, anyBox = false;
		for (double[] p : outline) {
			if (p[0] >= minX && p[0] <= maxX && p[2] >= minZ && p[2] <= maxZ) {
				anyBox = true;
			}
		}
		for (double[] p : tri) {
			allIn &= World.inside(outline, p[0], p[2]);
		}
		// A triangle fully inside the outline is kept whole when no outline corner pokes into it.
		if (allIn && !anyBox) {
			this.emitTerrain(s, new double[][] {tri[0], tri[1], tri[2]}, lod);
			return;
		}
		for (int e = 0; e < ear.length; e += 3) {
			double[][] poly = {tri[0], tri[1], tri[2]};
			double[][] clip = {outline[ear[e]], outline[ear[e + 1]], outline[ear[e + 2]]};
			double area = (clip[1][0] - clip[0][0]) * (clip[2][2] - clip[0][2]) - (clip[1][2] - clip[0][2]) * (clip[2][0] - clip[0][0]);
			double sign = area >= 0 ? 1 : -1;
			for (int c = 0; c < 3 && poly.length > 0; c++) {
				double[] a = clip[c], b = clip[(c + 1) % 3];
				poly = clipHalf(poly, a, b, sign);
			}
			if (poly.length >= 3) {
				this.emitTerrain(s, poly, lod);
			}
		}
	}

	/** Keeps the part of a polygon (x, y, z points) left of a-b (xz plane), interpolating y. */
	private static double[][] clipHalf(double[][] poly, double[] a, double[] b, double sign) {
		int n = poly.length;
		double[][] out = new double[n * 2 + 2][];
		int o = 0;
		for (int i = 0; i < n; i++) {
			double[] p = poly[i], q = poly[(i + 1) % n];
			double dp = side(p, a, b) * sign, dq = side(q, a, b) * sign;
			if (dp >= 0) {
				out[o++] = p;
			}
			if ((dp >= 0) != (dq >= 0)) {
				double t = dp / (dp - dq);
				double[] r = new double[p.length];
				for (int c = 0; c < p.length; c++) {
					r[c] = p[c] + (q[c] - p[c]) * t;
				}
				out[o++] = r;
			}
		}
		return java.util.Arrays.copyOf(out, o);
	}

	private static double side(double[] p, double[] a, double[] b) {
		return (b[0] - a[0]) * (p[2] - a[2]) - (b[2] - a[2]) * (p[0] - a[0]);
	}

	private void emitTerrain(Soup s, double[][] poly, int lod) {
		double cx = 0, cz = 0;
		for (double[] p : poly) {
			cx += p[0];
			cz += p[2];
		}
		cx /= poly.length;
		cz /= poly.length;
		int mat = this.groundMat(cx, cz);
		Soup.Surf surf = groundSurf(mat);
		double[][] norms = new double[poly.length][];
		for (int i = 0; i < poly.length; i++) {
			double nx = poly[i][3], ny = poly[i][4], nz = poly[i][5], l = Math.sqrt(nx * nx + ny * ny + nz * nz);
			norms[i] = new double[] {nx / l, ny / l, nz / l};
		}
		for (int i = 1; i + 1 < poly.length; i++) {
			double ar = (poly[i][0] - poly[0][0]) * (poly[i + 1][2] - poly[0][2]) - (poly[i][2] - poly[0][2]) * (poly[i + 1][0] - poly[0][0]);
			if (Math.abs(ar) < 1.0E-4) {
				continue;
			}
			s.smooth(mat, lod, surf, poly[0], poly[i], poly[i + 1], norms[0], norms[i], norms[i + 1]);
		}
	}

	private double rowZ(int j) {
		return j * 96 + 24 + w.rnd(120, j, 0) * 40;
	}

	private double poleX(int j, int i) {
		return i * 28 + w.rnd(121, j, 0) * 28 + 0.5 * Math.sin(i * 1.7 + j);
	}

	/** Whether a pole stands at (j, i): open field around, level ground, clear of features. */
	private boolean poleStands(int j, int i) {
		double px = this.poleX(j, i), pz = this.rowZ(j);
		for (int d = 0; d < 5; d++) {
			double ox = d == 1 ? 3 : d == 2 ? -3 : 0, oz = d == 3 ? 3 : d == 4 ? -3 : 0;
			if (!w.moduleAt(px + ox, pz + oz).field()) {
				return false;
			}
		}
		int Mx = Math.floorDiv((int) Math.floor(px), 32), Mz = Math.floorDiv((int) Math.floor(pz), 32);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Feat f = w.macro(Mx + dx, Mz + dz).feat;
				if (f != null && Math.hypot(px - f.cx, pz - f.cz) < f.padR + f.blend + (f.type == World.F_PIT ? 6 : 3)) {
					return false;
				}
			}
		}
		double[] n = w.groundNormal(px, pz);
		return n[1] > 0.93;
	}

	private void poles(Soup s, Mod m, double[][] outline) {
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double[] p : outline) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[2]);
			maxZ = Math.max(maxZ, p[2]);
		}
		for (int j = Math.floorDiv((int) Math.floor(minZ) - 64, 96); j <= Math.floorDiv((int) Math.ceil(maxZ), 96); j++) {
			double pz = this.rowZ(j);
			if (pz < minZ - 0.01 || pz > maxZ + 0.01) {
				continue;
			}
			for (int i = Math.floorDiv((int) Math.floor(minX) - 56, 28); i <= Math.floorDiv((int) Math.ceil(maxX), 28); i++) {
				double px = this.poleX(j, i);
				if (px < minX || px > maxX || w.moduleAt(px, pz) != m || !this.poleStands(j, i)) {
					continue;
				}
				this.pole(s, j, i, px, pz);
			}
		}
	}

	private void pole(Soup s, int j, int i, double px, double pz) {
		double g = w.ground(px, pz);
		long h = Noise.hash(w.seed, 125, i, j);
		double lean = (Noise.unit(h) - 0.5) * 0.07, leanYaw = Noise.unit(Noise.mix(h + 1)) * Math.PI * 2;
		int from = s.count;
		Geo.column(s, px, pz, 0.22, 0.14, g - 0.5, g + 10.2, 8, Noise.unit(h) * 3, Mats.POLE_WOOD, L01, h);
		// Cross arm and insulators.
		double armY = g + 9.5;
		Geo.box(s, px, pz, Noise.unit(Noise.mix(h + 2)) * 0.2, 0.12, 1.6, armY, armY + 0.22, Mats.POLE_WOOD, Mats.POLE_WOOD, L01, h + 3);
		for (int q = -1; q <= 1; q++) {
			Geo.column(s, px + 0.0, pz + q * 1.3, 0.05, 0.05, armY + 0.22, armY + 0.5, 6, 0, Mats.CONCRETE, L0, h + 4 + q);
		}
		s.transform(from, px, g, pz, 0, lean, leanYaw);
		// Thin pole for the middle distance.
		Geo.column(s, px, pz, 0.2, 0.12, g - 0.3, g + 10.2, 4, Noise.unit(h) * 3, Mats.POLE_WOOD, L2, h + 9);
		if (this.poleStands(j, i + 1)) {
			double nx = this.poleX(j, i + 1), gn = w.ground(nx, pz);
			for (int q = -1; q <= 1; q++) {
				double y0 = g + 9.75, y1 = gn + 9.75;
				double wz = pz + q * 1.3;
				int parts = 14;
				double[] prev = null;
				for (int p = 0; p <= parts; p++) {
					double t = (double) p / parts;
					double[] pt = {px + (nx - px) * t, y0 + (y1 - y0) * t - 1.7 * 4 * t * (1 - t), wz};
					if (prev != null) {
						this.wire(s, prev, pt, L01);
					}
					prev = pt;
				}
			}
		}
	}

	private void wire(Soup s, double[] a, double[] b, int lod) {
		double r = 0.04;
		double[][] bot = {{a[0], a[1] - r, a[2] - r}, {a[0], a[1] - r, a[2] + r}, {a[0], a[1] + r, a[2]}};
		double[][] top = {{b[0], b[1] - r, b[2] - r}, {b[0], b[1] - r, b[2] + r}, {b[0], b[1] + r, b[2]}};
		Soup.Surf surf = Soup.Surf.of(Mats.WIRE, 1);
		for (int i = 0; i < 3; i++) {
			int j = (i + 1) % 3;
			double mx = (bot[i][0] + bot[j][0] + top[i][0] + top[j][0]) / 4, my = (bot[i][1] + bot[j][1] + top[i][1] + top[j][1]) / 4;
			double mz = (bot[i][2] + bot[j][2] + top[i][2] + top[j][2]) / 4;
			double cy = (a[1] + b[1]) / 2, cz = a[2];
			double dx = 0, dy = my - cy, dz = mz - cz;
			s.tri(Mats.WIRE, lod, surf, bot[i], bot[j], top[j], dx, dy, dz);
			s.tri(Mats.WIRE, lod, surf, bot[i], top[j], top[i], dx, dy, dz);
		}
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Field features

	private void feature(Soup s, Feat f, int Mx, int Mz) {
		if (f.padY == 0) {
			f.padY = w.baseGround(f.cx, f.cz);
		}
		long h = Noise.hash(w.seed, 350, Mx, Mz);
		switch (f.type) {
			case World.F_ZIG -> {
				for (int i = 0; i < f.n; i++) {
					double r = Math.max(1.6, f.a - i * 1.1);
					Geo.prism(s, Geo.circle(f.cx, f.padY - 1.5, f.cz, r, 8, f.rot), Geo.circle(f.cx, f.padY + 0.45 * (i + 1), f.cz, r, 8, f.rot), Mats.STAIR, Mats.STAIR, Mats.STAIR,
						L012, h + i, true, false);
				}
				// A small obelisk on top.
				Geo.column(s, f.cx, f.cz, 0.5, 0.2, f.padY + 0.45 * f.n, f.padY + 0.45 * f.n + 3.2, 4, f.rot, Mats.STONE_DARK, L012, h + 50);
			}
			case World.F_PIT -> this.pit(s, f, h);
			case World.F_MONO -> {
				int from = s.count;
				double dx = Math.cos(f.rot), dz = Math.sin(f.rot);
				Geo.wallPiece(s, f.cx - dx * 1.6, f.cz - dz * 1.6, f.cx + dx * 1.6, f.cz + dz * 1.6, 1.1, 0.25, 0, 0, f.padY - 2, f.padY + f.a, Mats.STONE_DARK, L012, h, true,
					false);
				s.transform(from, f.cx, f.padY, f.cz, 0, 0.06, f.rot + Math.PI / 2);
			}
			case World.F_GIANT_DOOR -> {
				double dx = Math.cos(f.rot), dz = Math.sin(f.rot);
				double half = 2.9;
				for (int sd = -1; sd <= 1; sd += 2) {
					Geo.column(s, f.cx + dx * half * sd, f.cz + dz * half * sd, 0.62, 0.62, f.padY - 1.5, f.padY + f.a, 8, f.rot, Mats.FRAME, L012, h + sd);
				}
				Geo.box(s, f.cx, f.cz, f.rot + Math.PI / 2, 0.55, half + 0.9, f.padY + f.a - 1.0, f.padY + f.a + 0.4, Mats.FRAME, Mats.FRAME, L012, h + 5);
			}
			case World.F_PLAT -> this.platform(s, f, h);
			default -> {
			}
		}
	}

	/** The pit is the smooth ground bowl (slope below 30 degrees, walkable); only its lamp is added here. */
	private void pit(Soup s, Feat f, long h) {
		double yBot = f.padY - f.a;
		double[][] r = Geo.rect(f.cx, f.cz, f.rot, 1.2, 1.2, yBot + 0.04);
		Soup.Surf surf = new Soup.Surf(Mats.STRIP, 5, f.rot);
		s.tri(Mats.STRIP, L012, surf, r[0], r[1], r[2], 0, 1, 0);
		s.tri(Mats.STRIP, L012, surf, r[0], r[2], r[3], 0, 1, 0);
		// A light shaft: a thin emissive pillar rising out of the pit so it reads from afar.
		Geo.column(s, f.cx, f.cz, 0.18, 0.18, yBot, f.padY + 4.5, 6, f.rot, Mats.STRIP, L012, h);
	}

	private static double smoothS(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** A tilted floating platform reached by a spiral stair that ends at its edge. */
	private void platform(Soup s, Feat f, long h) {
		int perTurn = 14;
		int total = (int) Math.round(f.a / 0.4);
		double rOut = 2.3;
		double platR = 6.0, dist = rOut + platR - 0.35;
		double px = f.cx + Math.cos(f.rot) * dist, pz = f.cz + Math.sin(f.rot) * dist;
		double top = f.padY + 0.4 * total;
		double da = 2 * Math.PI / perTurn;
		double aFinal = f.rot;
		// The stair winds so that its last tread points at the platform centre.
		Geo.column(s, f.cx, f.cz, 0.5, 0.5, f.padY - 1.0, top - 0.2, 8, f.rot, Mats.STAIR, L012, h);
		double aLast = aFinal;
		for (int i = 0; i < total; i++) {
			double a = aLast - (total - 1 - i) * da;
			Geo.tread(s, f.cx, f.cz, 0.45, rOut, a, a + da * 1.05, f.padY + 0.4 * (i + 1), 0.75, Mats.STAIR, L012, h + 100 + i);
		}
		int from = s.count;
		Geo.prism(s, Geo.circle(px, top - 0.5, pz, platR, 8, f.rot), Geo.circle(px, top, pz, platR, 8, f.rot), Mats.CONCRETE, Mats.STAIR, Mats.CONCRETE, L012, h + 7, true, true);
		// Lamp under the platform.
		double[][] r = Geo.rect(px, pz, f.rot, 1.4, 1.4, top - 0.53);
		Soup.Surf surf = new Soup.Surf(Mats.STRIP, 5, f.rot);
		s.tri(Mats.STRIP, L012, surf, r[0], r[1], r[2], 0, -1, 0);
		s.tri(Mats.STRIP, L012, surf, r[0], r[2], r[3], 0, -1, 0);
		s.transform(from, px, top, pz, 0, 0.05, f.rot + Math.PI / 2);
	}
}
