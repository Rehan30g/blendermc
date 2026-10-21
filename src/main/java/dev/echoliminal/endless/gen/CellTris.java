package dev.echoliminal.endless.gen;

/**
 * The triangles owned by one cell, WORLD coordinates, non-indexed: triangle t has positions {@code pos[9t..9t+8]},
 * normals {@code nrm[9t..]}, uvs {@code uv[6t..]} (cell-local 0..1) and material {@code mat[t]}.
 */
public record CellTris(float[] pos, float[] nrm, float[] uv, int[] mat) {
	public static final CellTris EMPTY = new CellTris(new float[0], new float[0], new float[0], new int[0]);

	public int triangleCount() {
		return this.mat.length;
	}

	public boolean isEmpty() {
		return this.mat.length == 0;
	}
}
