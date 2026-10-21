package dev.echoliminal.endless.client;

/** A streamed unit: level of detail {@code lod} over regions [tx*size, (tx+1)*size) x [tz*size, (tz+1)*size). */
record Tile(int lod, int tx, int tz) {
	/** Region edge of a tile per level of detail: coarse tiles cover several regions so far rings stay a few hundred draws. */
	static final int[] SIZE = {1, 1, 2, 4, 8};

	int size() {
		return SIZE[this.lod];
	}

	/** Block bounds of the footprint. */
	int minX() {
		return this.tx * this.size() * EndlessSource.REGION;
	}

	int minZ() {
		return this.tz * this.size() * EndlessSource.REGION;
	}

	int edge() {
		return this.size() * EndlessSource.REGION;
	}

	/** True if the footprints (in regions) overlap. */
	boolean overlaps(Tile o) {
		int s = this.size(), t = o.size();
		return this.tx * s < (o.tx + 1) * t && o.tx * t < (this.tx + 1) * s && this.tz * s < (o.tz + 1) * t && o.tz * t < (this.tz + 1) * s;
	}

	/** Horizontal distance from (x, z) to the footprint, 0 inside. */
	double distance(double x, double z) {
		double dx = Math.max(Math.max(this.minX() - x, 0), x - (this.minX() + this.edge()));
		double dz = Math.max(Math.max(this.minZ() - z, 0), z - (this.minZ() + this.edge()));
		return Math.sqrt(dx * dx + dz * dz);
	}
}
