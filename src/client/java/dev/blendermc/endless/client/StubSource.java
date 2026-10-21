package dev.blendermc.endless.client;

import dev.blendermc.liminal.LiminalMesh;
import java.util.ArrayList;
import java.util.List;

/** Stand-in geometry (flat ground, pillars, a few walls) for testing the streaming renderer without the generator. */
final class StubSource implements EndlessSource {
	private final List<LiminalMesh.Material> materials = List.of(
		new LiminalMesh.Material("grass", new float[] {0.45F, 0.62F, 0.30F, 1}, null, false, true),
		new LiminalMesh.Material("wallpaper", new float[] {0.80F, 0.72F, 0.38F, 1}, null, false, true),
		new LiminalMesh.Material("light", new float[] {1, 1, 0.9F, 1}, null, true, true),
		new LiminalMesh.Material("water_nocollide", new float[] {0.3F, 0.6F, 0.8F, 0.55F}, null, false, false));

	@Override
	public List<LiminalMesh.Material> materials() {
		return this.materials;
	}

	@Override
	public int zone(int x, int z) {
		int h = hash(Math.floorDiv(x, 700), Math.floorDiv(z, 700));
		return Math.floorMod(h, 3);
	}

	@Override
	public EndlessMesh region(int rx, int rz, int lod) {
		Builder b = new Builder();
		int x0 = rx * REGION, z0 = rz * REGION;
		int step = lod == 0 ? 1 : REGION;
		for (int x = 0; x < REGION; x += step) {
			for (int z = 0; z < REGION; z += step) {
				b.quad(x0 + x, 64, z0 + z, step, step, 0);
			}
		}
		int boxes = lod == 0 ? 6 : 3;
		for (int i = 0; i < boxes; i++) {
			int h = hash(rx * 31 + i, rz * 17 + i);
			int bx = x0 + Math.floorMod(h, 56), bz = z0 + Math.floorMod(h >> 8, 56);
			b.box(bx, 64, bz, 3, 8, 3, 1);
		}
		return b.build();
	}

	private static int hash(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	private static final class Builder {
		final List<float[]> verts = new ArrayList<>();
		final List<Integer> mats = new ArrayList<>();
		final List<float[]> norms = new ArrayList<>();

		void quad(float x, float y, float z, float w, float d, int mat) {
			add(new float[] {x, y, z, x, y, z + d, x + w, y, z + d, x + w, y, z}, 0, 1, 0, mat, w, d);
		}

		void box(float x, float y, float z, float w, float h, float d, int mat) {
			add(new float[] {x, y + h, z, x, y + h, z + d, x + w, y + h, z + d, x + w, y + h, z}, 0, 1, 0, mat, w, d);
			add(new float[] {x, y, z, x + w, y, z, x + w, y + h, z, x, y + h, z}, 0, 0, -1, mat, w, h);
			add(new float[] {x + w, y, z + d, x, y, z + d, x, y + h, z + d, x + w, y + h, z + d}, 0, 0, 1, mat, w, h);
			add(new float[] {x, y, z + d, x, y, z, x, y + h, z, x, y + h, z + d}, -1, 0, 0, mat, d, h);
			add(new float[] {x + w, y, z, x + w, y, z + d, x + w, y + h, z + d, x + w, y + h, z}, 1, 0, 0, mat, d, h);
		}

		private void add(float[] c, float nx, float ny, float nz, int mat, float u, float v) {
			float[] uvs = {0, 0, u, 0, u, v, 0, v};
			for (int i = 0; i < 4; i++) {
				this.verts.add(new float[] {c[i * 3], c[i * 3 + 1], c[i * 3 + 2], uvs[i * 2], uvs[i * 2 + 1]});
				this.norms.add(new float[] {nx, ny, nz});
			}
			this.mats.add(mat);
		}

		EndlessMesh build() {
			int quads = this.mats.size();
			float[] pos = new float[quads * 12], nor = new float[quads * 12], uv = new float[quads * 8];
			int[] idx = new int[quads * 6], mat = new int[quads * 2];
			for (int q = 0; q < quads; q++) {
				for (int k = 0; k < 4; k++) {
					float[] v = this.verts.get(q * 4 + k), n = this.norms.get(q * 4 + k);
					System.arraycopy(v, 0, pos, (q * 4 + k) * 3, 3);
					System.arraycopy(n, 0, nor, (q * 4 + k) * 3, 3);
					uv[(q * 4 + k) * 2] = v[3];
					uv[(q * 4 + k) * 2 + 1] = v[4];
				}
				int b = q * 4;
				int[] t = {b, b + 1, b + 2, b, b + 2, b + 3};
				System.arraycopy(t, 0, idx, q * 6, 6);
				mat[q * 2] = this.mats.get(q);
				mat[q * 2 + 1] = this.mats.get(q);
			}
			return new EndlessMesh(pos, nor, uv, idx, mat);
		}
	}
}
