package dev.blendermc.endless.gen;

/** Stateless hashing and value noise; everything the generator knows is a pure function of the seed and coordinates. */
final class Noise {
	private Noise() {
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static long hash(long seed, int salt, int a, int b) {
		long h = mix(seed ^ (salt * 0x9E3779B97F4A7C15L));
		h = mix(h + a * 0xD6E8FEB86659FD93L);
		return mix(h + b * 0xCA5A826395121157L);
	}

	/** 0..1 from a hash. */
	static double unit(long h) {
		return (h >>> 11) * (1.0 / (1L << 53));
	}

	static double rnd(long seed, int salt, int a, int b) {
		return unit(hash(seed, salt, a, b));
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	/** Value noise 0..1 at (x, z) on a unit lattice. */
	static double value(long seed, int salt, double x, double z) {
		int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
		double fx = smooth(x - ix), fz = smooth(z - iz);
		double a = rnd(seed, salt, ix, iz), b = rnd(seed, salt, ix + 1, iz);
		double c = rnd(seed, salt, ix, iz + 1), d = rnd(seed, salt, ix + 1, iz + 1);
		return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
	}

	/** Three octaves, roughly 0.15..0.85. */
	static double fbm(long seed, int salt, double x, double z) {
		return value(seed, salt, x, z) * 0.55 + value(seed, salt + 1, x * 2.1, z * 2.1) * 0.3 + value(seed, salt + 2, x * 4.3, z * 4.3) * 0.15;
	}
}
