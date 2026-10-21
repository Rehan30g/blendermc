package dev.blendermc.endless.gen;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of the endless world as pure functions of a seed. The plan lives on a lattice of 8-block modules whose corners
 * are jittered and warped (so rooms are irregular quads with bent walls); modules group into 4 x 4 macro cells that carry
 * the zone, the storeys (multi-level buildings with a stair core and atria) or the field features. Streets (every
 * fourth module row and column) and a parent edge per interior module guarantee that every room connects.
 */
final class World {
	static final int MOD = 8, MAC = 4;
	static final double GROUND = 64.0;
	static final int WALL = 1, DOOR = 2, OPEN = 3, ARCH = 4, NONE = 0;
	static final int F_ZIG = 1, F_PIT = 2, F_MONO = 3, F_GIANT_DOOR = 4, F_PLAT = 5;

	final long seed;
	private final ConcurrentHashMap<Long, Macro> macros = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, Mod> mods = new ConcurrentHashMap<>();

	World(long seed) {
		this.seed = seed;
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Records

	/** A field feature standing on flattened ground. */
	static final class Feat {
		int type;
		double cx, cz, padR, blend, padY, rot, a, b, c;
		int n;
	}

	static final class Macro {
		int mx, mz;
		Zone zone;
		int lo, hi, steps;
		double h;
		int coreI = -1, coreJ = -1, atI = -1, atJ = -1;
		boolean coreIntoCeiling, spinCw;
		Feat feat;

		boolean field() {
			return this.zone == Zone.FIELD;
		}

		boolean has(int k) {
			return this.zone != Zone.FIELD && k >= this.lo && k <= this.hi;
		}

		/** Walking surface of storey k (k in lo..hi+1). */
		double floor(int k) {
			return GROUND + k * this.h;
		}
	}

	static final class Mod {
		int mx, mz;
		Macro mac;
		boolean street, intersection;
		int offI, offJ;
		boolean core, atrium;
		/** 0 maze, 1 halls, 2 rooms. */
		int style;
		volatile double[][] outline;

		boolean field() {
			return this.mac.field();
		}

		boolean pool() {
			return this.mac.zone == Zone.POOLROOMS;
		}
	}

	record Edge(int state, int seg, double at, double width, double height, double thick, double bevel) {
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Random helpers

	double rnd(int salt, int a, int b) {
		return Noise.rnd(this.seed, salt, a, b);
	}

	/** Random per module and storey. */
	double rndK(int salt, int mx, int mz, int k) {
		return Noise.rnd(this.seed, salt, mx * 32 + k + 16, mz);
	}

	long hashK(int salt, int mx, int mz, int k) {
		return Noise.hash(this.seed, salt, mx * 32 + k + 16, mz);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Macros and modules

	Macro macro(int Mx, int Mz) {
		long key = (long) Mx << 32 ^ (Mz & 0xFFFFFFFFL);
		Macro m = this.macros.get(key);
		if (m == null) {
			m = this.computeMacro(Mx, Mz);
			if (this.macros.size() > 50_000) {
				this.macros.clear();
			}
			this.macros.put(key, m);
		}
		return m;
	}

	private Zone zoneOf(int Mx, int Mz) {
		if (Noise.fbm(this.seed, 1, Mx / 5.0, Mz / 5.0) > 0.566) {
			return Zone.FIELD;
		}
		return Noise.fbm(this.seed, 4, Mx / 3.6 + 31.7, Mz / 3.6 - 12.3) > 0.585 ? Zone.POOLROOMS : Zone.BACKROOMS;
	}

	private Macro computeMacro(int Mx, int Mz) {
		Macro m = new Macro();
		m.mx = Mx;
		m.mz = Mz;
		m.zone = this.zoneOf(Mx, Mz);
		m.steps = 12 + (int) (this.rnd(210, Mx, Mz) * 6) % 6;
		m.h = m.steps * 0.4;
		if (m.zone == Zone.FIELD) {
			m.feat = this.computeFeat(Mx, Mz);
			return m;
		}
		double rh = this.rnd(211, Mx, Mz), rl = this.rnd(212, Mx, Mz);
		m.hi = rh < 0.60 ? 0 : rh < 0.80 ? 1 : rh < 0.90 ? 2 : rh < 0.95 ? 3 : rh < 0.99 ? 4 + (int) (this.rnd(213, Mx, Mz) * 4) : 8 + (int) (this.rnd(214, Mx, Mz) * 4);
		m.lo = rl < 0.66 ? 0 : rl < 0.83 ? -1 : rl < 0.91 ? -2 : rl < 0.96 ? -3 : -4;
		if (m.hi > 7) {
			m.lo = Math.max(m.lo, -2);
		}
		m.spinCw = this.rnd(215, Mx, Mz) < 0.5;
		if (m.hi - m.lo >= 1) {
			m.coreI = 1 + (int) (this.rnd(216, Mx, Mz) * 3) % 3;
			m.coreJ = 1 + (int) (this.rnd(217, Mx, Mz) * 3) % 3;
			m.coreIntoCeiling = this.rnd(218, Mx, Mz) < 0.4;
			if (this.rnd(219, Mx, Mz) < 0.55) {
				int ai = 1 + (int) (this.rnd(220, Mx, Mz) * 3) % 3, aj = 1 + (int) (this.rnd(221, Mx, Mz) * 3) % 3;
				if (ai == m.coreI && aj == m.coreJ) {
					ai = 1 + ai % 3;
				}
				m.atI = ai;
				m.atJ = aj;
			}
		}
		return m;
	}

	private Feat computeFeat(int Mx, int Mz) {
		double r = this.rnd(230, Mx, Mz);
		Feat f = new Feat();
		f.cx = Mx * 32 + 16 + (this.rnd(231, Mx, Mz) - 0.5) * 8;
		f.cz = Mz * 32 + 16 + (this.rnd(232, Mx, Mz) - 0.5) * 8;
		f.rot = this.rnd(233, Mx, Mz) * Math.PI * 2;
		if (r < 0.17) {
			f.type = F_ZIG;
			f.n = 7 + (int) (this.rnd(234, Mx, Mz) * 4);
			f.a = 9.5;
			f.padR = f.a + 2;
			f.blend = 12;
		} else if (r < 0.32) {
			f.type = F_PIT;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (this.zoneOf(Mx + dx, Mz + dz) != Zone.FIELD) {
						return null;
					}
				}
			}
			f.a = 3.5 + this.rnd(235, Mx, Mz) * 3.0;
			f.b = 5.0;
			f.c = f.b + 2.7 * f.a;
			f.padR = f.c;
			f.blend = 10;
			f.cx = Mx * 32 + 16 + (this.rnd(231, Mx, Mz) - 0.5) * 3;
			f.cz = Mz * 32 + 16 + (this.rnd(232, Mx, Mz) - 0.5) * 3;
		} else if (r < 0.44) {
			f.type = F_MONO;
			f.a = 11 + this.rnd(236, Mx, Mz) * 8;
			f.padR = 3;
			f.blend = 8;
		} else if (r < 0.53) {
			f.type = F_GIANT_DOOR;
			f.a = 9 + this.rnd(237, Mx, Mz) * 4;
			f.padR = 6;
			f.blend = 8;
		} else if (r < 0.63) {
			f.type = F_PLAT;
			f.a = 8 + (int) (this.rnd(238, Mx, Mz) * 6);
			f.padR = 5;
			f.blend = 8;
		} else {
			return null;
		}
		return f;
	}

	Mod mod(int mx, int mz) {
		long key = (long) mx << 32 ^ (mz & 0xFFFFFFFFL);
		Mod m = this.mods.get(key);
		if (m == null) {
			m = new Mod();
			m.mx = mx;
			m.mz = mz;
			m.mac = this.macro(Math.floorDiv(mx, MAC), Math.floorDiv(mz, MAC));
			m.offI = Math.floorMod(mx, MAC);
			m.offJ = Math.floorMod(mz, MAC);
			m.street = m.offI == 0 || m.offJ == 0;
			m.intersection = m.offI == 0 && m.offJ == 0;
			m.core = m.offI == m.mac.coreI && m.offJ == m.mac.coreJ;
			m.atrium = m.offI == m.mac.atI && m.offJ == m.mac.atJ;
			double r = this.rnd(240, mx, mz);
			m.style = r < 0.5 ? 0 : r < 0.75 ? 1 : 2;
			if (this.mods.size() > 200_000) {
				this.mods.clear();
			}
			this.mods.put(key, m);
		}
		return m;
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Plan: lattice nodes, bent edges, module outlines

	double nodeX(int i, int j) {
		double bx = i * MOD, bz = j * MOD;
		double warp = (Noise.value(this.seed, 201, bx / 61.0, bz / 61.0) - 0.5) * 6.0 + (Noise.value(this.seed, 203, bx / 23.0, bz / 23.0) - 0.5) * 1.8;
		return bx + warp + (this.rnd(205, i, j) - 0.5) * 3.0;
	}

	double nodeZ(int i, int j) {
		double bx = i * MOD, bz = j * MOD;
		double warp = (Noise.value(this.seed, 207, bx / 61.0, bz / 61.0) - 0.5) * 6.0 + (Noise.value(this.seed, 209, bx / 23.0, bz / 23.0) - 0.5) * 1.8;
		return bz + warp + (this.rnd(206, i, j) - 0.5) * 3.0;
	}

	/**
	 * Mid point of the edge starting at node (i, j): axis 0 runs along z (the west edge of module (i, j)), axis 1 along x (its
	 * north edge). Bent sideways unless both sides are open field.
	 */
	double[] edgeMid(int axis, int i, int j) {
		double ax = this.nodeX(i, j), az = this.nodeZ(i, j);
		double bx = axis == 0 ? this.nodeX(i, j + 1) : this.nodeX(i + 1, j), bz = axis == 0 ? this.nodeZ(i, j + 1) : this.nodeZ(i + 1, j);
		boolean flat = axis == 0 ? this.mod(i - 1, j).field() && this.mod(i, j).field() : this.mod(i, j - 1).field() && this.mod(i, j).field();
		double bulge = flat ? 0 : (this.rnd(250 + axis, i, j) - 0.5) * 1.9;
		double shift = 0.5 + (this.rnd(252 + axis, i, j) - 0.5) * 0.16;
		double dx = bx - ax, dz = bz - az, len = Math.sqrt(dx * dx + dz * dz);
		return new double[] {ax + dx * shift - dz / len * bulge, az + dz * shift + dx / len * bulge};
	}

	/** The outline of a module: 8 points (corner, mid, corner, mid, ...) in xz, y = 0. */
	double[][] outline(int mx, int mz) {
		Mod m = this.mod(mx, mz);
		double[][] o = m.outline;
		if (o == null) {
			double[] n = edgeMid(1, mx, mz), e = edgeMid(0, mx + 1, mz), s = edgeMid(1, mx, mz + 1), w = edgeMid(0, mx, mz);
			o = new double[][] {{this.nodeX(mx, mz), 0, this.nodeZ(mx, mz)}, {n[0], 0, n[1]}, {this.nodeX(mx + 1, mz), 0, this.nodeZ(mx + 1, mz)},
				{e[0], 0, e[1]}, {this.nodeX(mx + 1, mz + 1), 0, this.nodeZ(mx + 1, mz + 1)}, {s[0], 0, s[1]}, {this.nodeX(mx, mz + 1), 0, this.nodeZ(mx, mz + 1)},
				{w[0], 0, w[1]}};
			m.outline = o;
		}
		return o;
	}

	static boolean inside(double[][] ring, double x, double z) {
		boolean in = false;
		int n = ring.length;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = ring[i][0], zi = ring[i][2], xj = ring[j][0], zj = ring[j][2];
			if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
				in = !in;
			}
		}
		return in;
	}

	/** The module whose outline contains the column point. */
	Mod moduleAt(double x, double z) {
		int mx0 = Math.floorDiv((int) Math.floor(x), MOD), mz0 = Math.floorDiv((int) Math.floor(z), MOD);
		if (inside(this.outline(mx0, mz0), x, z)) {
			return this.mod(mx0, mz0);
		}
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if ((dx != 0 || dz != 0) && inside(this.outline(mx0 + dx, mz0 + dz), x, z)) {
					return this.mod(mx0 + dx, mz0 + dz);
				}
			}
		}
		return this.mod(mx0, mz0);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Storeys and edges

	/** Height of the ceiling's underside of a module at storey k (k must exist in the module's macro). */
	double ceilY(Mod m, int k) {
		Macro mac = m.mac;
		if (k < mac.hi) {
			return mac.floor(k + 1) - 0.6;
		}
		double base = mac.floor(k);
		if (m.core || m.atrium) {
			return base + mac.h + (m.atrium ? 1.5 : 0);
		}
		double rh = mac.h + (this.rnd(260, m.mx, m.mz) - 0.5) * 3.2;
		if (mac.hi == mac.lo) {
			rh = 3.7 + this.rnd(261, m.mx, m.mz) * 2.1;
			if (this.rnd(262, m.mx, m.mz) < 0.05) {
				rh += 4.0;
			}
		}
		return base + Math.max(3.7, Math.min(8.5, rh));
	}

	/** Parent direction of an interior module: true = west, false = north. */
	boolean parentWest(int mx, int mz) {
		return this.rnd(270, mx, mz) < 0.5;
	}

	boolean exists(Mod m, int k) {
		return m.mac.has(k);
	}

	/** Edge between module a (west or north neighbour) and b = (mx, mz) at storey k. */
	Edge edge(int axis, int mx, int mz, int k) {
		Mod a = axis == 0 ? this.mod(mx - 1, mz) : this.mod(mx, mz - 1);
		Mod b = this.mod(mx, mz);
		boolean ea = this.exists(a, k), eb = this.exists(b, k);
		if (!ea && !eb) {
			return new Edge(NONE, 0, 0, 0, 0, 0, 0);
		}
		long h = this.hashK(280 + axis, mx, mz, k);
		double r = Noise.unit(h), r2 = Noise.unit(Noise.mix(h + 1)), r3 = Noise.unit(Noise.mix(h + 2)), r4 = Noise.unit(Noise.mix(h + 3));
		double thick = 0.28 + this.rnd(284 + axis, mx, mz) * 0.38, bevel = 0.04 + this.rnd(286 + axis, mx, mz) * 0.06;
		int state;
		boolean sameMacro = a.mac == b.mac;
		boolean street = axis == 0 ? (mz & 3) == 0 : (mx & 3) == 0;
		boolean forced;
		if (sameMacro) {
			boolean parent = !b.street && (axis == 0) == this.parentWest(mx, mz);
			if (b.street) {
				parent = false;
			}
			forced = parent || street;
			if (forced) {
				state = street ? (r < 0.6 ? OPEN : r < 0.85 ? ARCH : DOOR) : (r < 0.35 ? OPEN : r < 0.7 ? ARCH : DOOR);
			} else {
				state = b.style == 1 ? (r < 0.2 ? WALL : r < 0.4 ? DOOR : r < 0.65 ? ARCH : OPEN)
					: b.style == 0 ? (r < 0.4 ? WALL : r < 0.7 ? DOOR : r < 0.85 ? ARCH : OPEN) : (r < 0.3 ? WALL : r < 0.7 ? DOOR : r < 0.9 ? ARCH : OPEN);
			}
			if (k != 0 && !ea) {
				state = WALL;
			}
		} else if (k != 0) {
			state = WALL;
		} else if ((axis == 0 ? mz : mx) % 4 == 1 || (axis == 0 ? mz : mx) % 4 == -3) {
			// One guaranteed way through every macro border (every fourth module along it), so no building or field is walled in.
			state = a.field() || b.field() ? (r < 0.5 ? ARCH : DOOR) : (r < 0.5 ? ARCH : OPEN);
		} else if (a.field() || b.field()) {
			state = street ? (r < 0.5 ? ARCH : DOOR) : (r < 0.6 ? WALL : r < 0.85 ? DOOR : ARCH);
		} else {
			state = street ? (r < 0.5 ? ARCH : r < 0.8 ? OPEN : DOOR) : (r < 0.45 ? WALL : r < 0.75 ? DOOR : ARCH);
		}
		int seg = r2 < 0.5 ? 0 : 1;
		double at = 0.36 + r3 * 0.28;
		double width = state == ARCH ? 2.4 + r4 * 1.0 : 1.5 + r4 * 0.8;
		double height = state == ARCH ? 2.3 : 2.35 + r4 * 0.6;
		return new Edge(state, seg, at, width, height, thick, bevel);
	}

	// ----------------------------------------------------------------------------------------------------------------
	// Ground (the field)

	/** Distance in blocks from a point to the nearest building macro cell (unwarped rectangles), capped. */
	double buildingDistance(double x, double z) {
		int Mx = Math.floorDiv((int) Math.floor(x), 32), Mz = Math.floorDiv((int) Math.floor(z), 32);
		double best = 60;
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				if (this.macro(Mx + dx, Mz + dz).field()) {
					continue;
				}
				double x0 = (Mx + dx) * 32, z0 = (Mz + dz) * 32;
				double ex = Math.max(Math.max(x0 - x, 0), x - (x0 + 32)), ez = Math.max(Math.max(z0 - z, 0), z - (z0 + 32));
				best = Math.min(best, Math.sqrt(ex * ex + ez * ez));
			}
		}
		return best;
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** Ground without features: terraces and plateaus on a broad scale, rolling swell on a small one, flat next to buildings. */
	double baseGround(double x, double z) {
		double w = smooth((this.buildingDistance(x, z) - 10) / 32.0);
		if (w <= 0) {
			return GROUND;
		}
		double n = (Noise.fbm(this.seed, 20, x / 95.0, z / 95.0) - 0.3) / 0.4;
		n = Math.max(0, Math.min(0.999, n));
		double t = n * 3;
		int lvl = (int) Math.floor(t);
		double f = t - lvl;
		double terrace = (lvl + smooth((f - 0.5) / 0.5)) * 2.4 - 2.4;
		double roll = (Noise.value(this.seed, 22, x / 17.0, z / 17.0) - 0.5) * 1.3 + (Noise.value(this.seed, 23, x / 6.5, z / 6.5) - 0.5) * 0.3;
		return GROUND + (terrace + roll) * w;
	}

	/** The walkable ground height at a field column. */
	double ground(double x, double z) {
		double g = this.baseGround(x, z);
		int Mx = Math.floorDiv((int) Math.floor(x), 32), Mz = Math.floorDiv((int) Math.floor(z), 32);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Macro mac = this.macro(Mx + dx, Mz + dz);
				Feat f = mac.feat;
				if (f == null) {
					continue;
				}
				double d = Math.hypot(x - f.cx, z - f.cz);
				if (d > f.padR + f.blend) {
					continue;
				}
				if (f.padY == 0) {
					f.padY = this.baseGround(f.cx, f.cz);
				}
				double k = smooth((d - f.padR) / f.blend);
				g = f.padY + (g - f.padY) * k;
				if (f.type == F_PIT) {
					double depth = f.a * (1 - smooth((d - f.b) / (f.c - f.b)));
					double w = smooth((this.buildingDistance(x, z) - 6) / 20.0);
					g -= depth * w;
				}
			}
		}
		return g;
	}

	/** Surface normal of the ground by central differences. */
	double[] groundNormal(double x, double z) {
		double e = 0.5;
		double gx = (this.ground(x + e, z) - this.ground(x - e, z)) / (2 * e), gz = (this.ground(x, z + e) - this.ground(x, z - e)) / (2 * e);
		double len = Math.sqrt(gx * gx + 1 + gz * gz);
		return new double[] {-gx / len, 1 / len, -gz / len};
	}

	/** Top of the tall field features (for the far levels of detail): -1 if none at the column. */
	double featureTop(double x, double z) {
		int Mx = Math.floorDiv((int) Math.floor(x), 32), Mz = Math.floorDiv((int) Math.floor(z), 32);
		double top = -1;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Feat f = this.macro(Mx + dx, Mz + dz).feat;
				if (f == null) {
					continue;
				}
				double d = Math.hypot(x - f.cx, z - f.cz);
				if (f.padY == 0) {
					f.padY = this.baseGround(f.cx, f.cz);
				}
				if (f.type == F_ZIG && d < f.a) {
					int tier = (int) Math.min(f.n - 1, Math.floor((f.a - d) / 1.1));
					top = Math.max(top, f.padY + 0.45 * (tier + 1));
				} else if (f.type == F_MONO && d < 2.2) {
					top = Math.max(top, f.padY + f.a);
				} else if (f.type == F_GIANT_DOOR && d < 3.6) {
					top = Math.max(top, f.padY + f.a);
				}
			}
		}
		return top;
	}
}
