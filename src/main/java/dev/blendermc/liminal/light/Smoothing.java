package dev.blendermc.liminal.light;

import java.util.Arrays;

/**
 * Normal-aware smoothing of per-vertex values over the mesh's edges: a vertex is averaged with its 1-ring
 * neighbours (about one block on the grid-split meshes), but only neighbours whose normal is within ~25 degrees, so
 * nothing bleeds across corners or creases.
 */
public final class Smoothing {
	private static final float MIN_DOT = 0.9F;
	private final int[] start;
	private final int[] neighbours;
	private final float[] normals;

	/** {@code normals}: unit normal per vertex (3 floats each); {@code indices}: triangles over those vertices. */
	public Smoothing(int vertexCount, int[] indices, int indexCount, float[] normals) {
		this.normals = normals;
		long[] pairs = new long[indexCount * 2];
		int n = 0;
		for (int t = 0; t + 2 < indexCount; t += 3) {
			for (int k = 0; k < 3; k++) {
				int a = indices[t + k], b = indices[t + (k + 1) % 3];
				if (a != b) {
					pairs[n++] = (long) a << 32 | b;
					pairs[n++] = (long) b << 32 | a;
				}
			}
		}
		Arrays.sort(pairs, 0, n);
		this.start = new int[vertexCount + 1];
		int[] out = new int[n];
		int m = 0;
		long last = -1;
		for (int i = 0; i < n; i++) {
			if (pairs[i] == last) {
				continue;
			}
			last = pairs[i];
			out[m++] = (int) pairs[i];
			this.start[(int) (pairs[i] >>> 32) + 1]++;
		}
		for (int i = 0; i < vertexCount; i++) {
			this.start[i + 1] += this.start[i];
		}
		this.neighbours = Arrays.copyOf(out, m);
	}

	/** Smoothed value of vertex {@code v}. */
	public float smooth(float[] values, int v) {
		float nx = this.normals[v * 3], ny = this.normals[v * 3 + 1], nz = this.normals[v * 3 + 2];
		float sum = values[v] * 2.0F;
		float weight = 2.0F;
		for (int i = this.start[v]; i < this.start[v + 1]; i++) {
			int j = this.neighbours[i];
			if (nx * this.normals[j * 3] + ny * this.normals[j * 3 + 1] + nz * this.normals[j * 3 + 2] >= MIN_DOT) {
				sum += values[j];
				weight += 1.0F;
			}
		}
		return sum / weight;
	}

	/** Smoothed value of vertex {@code v} of unsigned byte values (0..255). */
	public float smoothUnsigned(byte[] values, int v) {
		float nx = this.normals[v * 3], ny = this.normals[v * 3 + 1], nz = this.normals[v * 3 + 2];
		float sum = (values[v] & 0xFF) * 2.0F;
		float weight = 2.0F;
		for (int i = this.start[v]; i < this.start[v + 1]; i++) {
			int j = this.neighbours[i];
			if (nx * this.normals[j * 3] + ny * this.normals[j * 3 + 1] + nz * this.normals[j * 3 + 2] >= MIN_DOT) {
				sum += values[j] & 0xFF;
				weight += 1.0F;
			}
		}
		return sum / weight;
	}

	/** Calls {@code sink} with every neighbour of {@code v}. */
	public void forEachNeighbour(int v, java.util.function.IntConsumer sink) {
		for (int i = this.start[v]; i < this.start[v + 1]; i++) {
			sink.accept(this.neighbours[i]);
		}
	}
}
