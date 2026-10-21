package dev.blendermc.endless.client;

/**
 * Triangles of one generated region at one level of detail, in world block coordinates: vertex i has position
 * {@code pos[3i..]}, normal {@code nor[3i..]}, uv {@code uv[2i..]}; triangle t uses {@code idx[3t..]} (counter clockwise
 * front) and palette material {@code mat[t]}.
 */
public record EndlessMesh(float[] pos, float[] nor, float[] uv, int[] idx, int[] mat) {
	public static final EndlessMesh EMPTY = new EndlessMesh(new float[0], new float[0], new float[0], new int[0], new int[0]);

	public int triangles() {
		return this.idx.length / 3;
	}
}
