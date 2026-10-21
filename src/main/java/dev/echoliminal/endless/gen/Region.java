package dev.echoliminal.endless.gen;

/**
 * One generated region (64 x 64 columns, y {@link EndlessGenerator#MIN_Y}..{@link EndlessGenerator#MAX_Y}) at one LOD.
 * Immutable. Vertex i: position {@code positions[3i..]} (WORLD block coordinates), normal {@code normals[3i..]}, uv
 * {@code uvs[2i..]}. Triangle t: vertices {@code indices[3t..3t+2]}, counter-clockwise seen from the front, material id
 * {@code triMaterial[t]} (index into {@link Mats#ALL}).
 *
 * <p>UVs: LOD 0 triangles lie inside one cell and their uv is cell-local (0..1, one texture repeat per block). LOD 1-4
 * quads span several blocks and their uv is in blocks (u, v exceed 1: sample with REPEAT wrapping, 1 repeat per block).
 */
public final class Region {
	public final int rx, rz, lod;
	public final float[] positions, normals, uvs;
	public final int[] indices, triMaterial;
	/** LOD 0 only: class per cell (low 2 bits {@link EndlessGenerator#AIR}/SURFACE/SOLID, plus FLAG_*), else null. Index see {@link #cellIndex}. */
	public final byte[] cells;
	public final long genNanos;

	Region(int rx, int rz, int lod, float[] positions, float[] normals, float[] uvs, int[] indices, int[] triMaterial, byte[] cells, long genNanos) {
		this.rx = rx;
		this.rz = rz;
		this.lod = lod;
		this.positions = positions;
		this.normals = normals;
		this.uvs = uvs;
		this.indices = indices;
		this.triMaterial = triMaterial;
		this.cells = cells;
		this.genNanos = genNanos;
	}

	public int triangleCount() {
		return this.indices.length / 3;
	}

	public int vertexCount() {
		return this.positions.length / 3;
	}

	public int minX() {
		return this.rx * EndlessGenerator.REGION;
	}

	public int minZ() {
		return this.rz * EndlessGenerator.REGION;
	}

	/** Index into {@link #cells} for the cell at world (x, y, z), which must lie in this region. */
	public int cellIndex(int x, int y, int z) {
		return ((y - EndlessGenerator.MIN_Y) * EndlessGenerator.REGION + (z - this.minZ())) * EndlessGenerator.REGION + (x - this.minX());
	}

	/** Class (AIR / SURFACE / SOLID) of a cell, LOD 0 regions only. */
	public int cellClass(int x, int y, int z) {
		return this.cells[this.cellIndex(x, y, z)] & 3;
	}

	/** Approximate memory in bytes. */
	public long bytes() {
		return (this.positions.length + this.normals.length + this.uvs.length) * 4L + (this.indices.length + this.triMaterial.length) * 4L
			+ (this.cells == null ? 0 : this.cells.length);
	}
}
