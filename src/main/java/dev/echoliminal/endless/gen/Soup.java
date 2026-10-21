package dev.echoliminal.endless.gen;

import java.util.Arrays;

/**
 * A growable triangle soup: three vertices per triangle (position, normal, uv), a material id and a LOD mask per
 * triangle (bit L set: drawn at LOD L). Everything in the endless world is first collected in soups, then cut on the block
 * grid (LOD 0) or used whole (LOD 1+).
 */
final class Soup {
	/** LOD masks. */
	static final int L0 = 1, L01 = 3, L012 = 7, L2 = 4;

	float[] p = new float[9 * 256], n = new float[9 * 256], uv = new float[6 * 256];
	int[] mat = new int[256];
	byte[] lod = new byte[256];
	int count;
	double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

	/** How a surface maps world space to texture space: planar, in real-world scale, with a random offset (and rotation for floors). */
	static final class Surf {
		final double scale, ou, ov, cs, sn;

		Surf(int mat, long hash, double angle) {
			this.scale = Mats.get(mat).scale();
			this.ou = Noise.unit(Noise.mix(hash)) * 16.0;
			this.ov = Noise.unit(Noise.mix(hash + 7)) * 16.0;
			this.cs = Math.cos(angle);
			this.sn = Math.sin(angle);
		}

		static Surf of(int mat, long hash) {
			return new Surf(mat, hash, 0);
		}
	}

	private void ensure() {
		if (this.count + 1 > this.mat.length) {
			int cap = this.mat.length * 2;
			this.p = Arrays.copyOf(this.p, cap * 9);
			this.n = Arrays.copyOf(this.n, cap * 9);
			this.uv = Arrays.copyOf(this.uv, cap * 6);
			this.mat = Arrays.copyOf(this.mat, cap);
			this.lod = Arrays.copyOf(this.lod, cap);
		}
	}

	/**
	 * Adds a triangle facing {@code (dx, dy, dz)} (the winding is flipped if needed; zero direction keeps the given winding),
	 * flat shaded, texture coordinates from {@code s}.
	 */
	void tri(int material, int lodMask, Surf s, double[] a, double[] b, double[] c, double dx, double dy, double dz) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0E-9) {
			return;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		if ((dx != 0 || dy != 0 || dz != 0) && nx * dx + ny * dy + nz * dz < 0) {
			double[] t = b;
			b = c;
			c = t;
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		this.emit(material, lodMask, s, a, b, c, nx, ny, nz, nx, ny, nz, nx, ny, nz, nx, ny, nz);
	}

	/** Smooth shaded triangle with vertex normals; winding follows the summed normals. */
	void smooth(int material, int lodMask, Surf s, double[] a, double[] b, double[] c, double[] na, double[] nb, double[] nc) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		if (nx * nx + ny * ny + nz * nz < 1.0E-18) {
			return;
		}
		if (nx * (na[0] + nb[0] + nc[0]) + ny * (na[1] + nb[1] + nc[1]) + nz * (na[2] + nb[2] + nc[2]) < 0) {
			double[] t = b;
			b = c;
			c = t;
			t = nb;
			nb = nc;
			nc = t;
		}
		// Surface projection uses the face normal so tiling stays planar.
		double fx = (na[0] + nb[0] + nc[0]), fy = (na[1] + nb[1] + nc[1]), fz = (na[2] + nb[2] + nc[2]);
		double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
		this.emit(material, lodMask, s, a, b, c, fx / fl, fy / fl, fz / fl, na[0], na[1], na[2], nb[0], nb[1], nb[2], nc[0], nc[1], nc[2]);
	}

	private void emit(int material, int lodMask, Surf s, double[] a, double[] b, double[] c, double fnx, double fny, double fnz, double... nrm) {
		this.ensure();
		int t = this.count++;
		this.mat[t] = material;
		this.lod[t] = (byte) lodMask;
		double[][] v = {a, b, c};
		double[] u = new double[3], w = new double[3];
		for (int k = 0; k < 3; k++) {
			double x = v[k][0], y = v[k][1], z = v[k][2];
			this.p[t * 9 + k * 3] = (float) x;
			this.p[t * 9 + k * 3 + 1] = (float) y;
			this.p[t * 9 + k * 3 + 2] = (float) z;
			this.n[t * 9 + k * 3] = (float) nrm[k * 3];
			this.n[t * 9 + k * 3 + 1] = (float) nrm[k * 3 + 1];
			this.n[t * 9 + k * 3 + 2] = (float) nrm[k * 3 + 2];
			if (Math.abs(fny) >= 0.6) {
				u[k] = (s.cs * x + s.sn * z) / s.scale + s.ou;
				w[k] = (-s.sn * x + s.cs * z) / s.scale + s.ov;
			} else {
				double h = Math.sqrt(fnx * fnx + fnz * fnz);
				double tx = h < 1.0E-9 ? 1 : -fnz / h, tz = h < 1.0E-9 ? 0 : fnx / h;
				u[k] = (x * tx + z * tz) / s.scale + s.ou;
				w[k] = y / s.scale + s.ov;
			}
			this.minX = Math.min(this.minX, x);
			this.minY = Math.min(this.minY, y);
			this.minZ = Math.min(this.minZ, z);
			this.maxX = Math.max(this.maxX, x);
			this.maxY = Math.max(this.maxY, y);
			this.maxZ = Math.max(this.maxZ, z);
		}
		double fu = Math.floor(Math.min(u[0], Math.min(u[1], u[2]))), fv = Math.floor(Math.min(w[0], Math.min(w[1], w[2])));
		for (int k = 0; k < 3; k++) {
			this.uv[t * 6 + k * 2] = (float) (u[k] - fu);
			this.uv[t * 6 + k * 2 + 1] = (float) (w[k] - fv);
		}
	}

	/** Appends all triangles of another soup. */
	void addAll(Soup o) {
		for (int t = 0; t < o.count; t++) {
			this.ensure();
			int d = this.count++;
			System.arraycopy(o.p, t * 9, this.p, d * 9, 9);
			System.arraycopy(o.n, t * 9, this.n, d * 9, 9);
			System.arraycopy(o.uv, t * 6, this.uv, d * 6, 6);
			this.mat[d] = o.mat[t];
			this.lod[d] = o.lod[t];
		}
		this.minX = Math.min(this.minX, o.minX);
		this.minY = Math.min(this.minY, o.minY);
		this.minZ = Math.min(this.minZ, o.minZ);
		this.maxX = Math.max(this.maxX, o.maxX);
		this.maxY = Math.max(this.maxY, o.maxY);
		this.maxZ = Math.max(this.maxZ, o.maxZ);
	}

	/** Rotates triangles {@code from..count} about the vertical axis through (cx, cz), tilts them about the horizontal axis (tiltAxis angle) and moves them. */
	void transform(int from, double cx, double cy, double cz, double yaw, double tilt, double tiltYaw) {
		double cy1 = Math.cos(yaw), sy1 = Math.sin(yaw), ct = Math.cos(tilt), st = Math.sin(tilt), ctY = Math.cos(tiltYaw), stY = Math.sin(tiltYaw);
		for (int i = from * 3; i < this.count * 3; i++) {
			for (int pass = 0; pass < 2; pass++) {
				float[] arr = pass == 0 ? this.p : this.n;
				double x = arr[i * 3] - (pass == 0 ? cx : 0), y = arr[i * 3 + 1] - (pass == 0 ? cy : 0), z = arr[i * 3 + 2] - (pass == 0 ? cz : 0);
				// Yaw.
				double x1 = x * cy1 + z * sy1, z1 = -x * sy1 + z * cy1;
				// Tilt about a horizontal axis whose direction is tiltYaw: rotate into that frame, roll about its z, rotate back.
				double xa = x1 * ctY + z1 * stY, za = -x1 * stY + z1 * ctY;
				double yb = y * ct - xa * st, xb = y * st + xa * ct;
				double x2 = xb * ctY - za * stY, z2 = xb * stY + za * ctY;
				arr[i * 3] = (float) (x2 + (pass == 0 ? cx : 0));
				arr[i * 3 + 1] = (float) (yb + (pass == 0 ? cy : 0));
				arr[i * 3 + 2] = (float) (z2 + (pass == 0 ? cz : 0));
			}
		}
		for (int t = from; t < this.count; t++) {
			for (int k = 0; k < 3; k++) {
				double x = this.p[t * 9 + k * 3], y = this.p[t * 9 + k * 3 + 1], z = this.p[t * 9 + k * 3 + 2];
				this.minX = Math.min(this.minX, x);
				this.minY = Math.min(this.minY, y);
				this.minZ = Math.min(this.minZ, z);
				this.maxX = Math.max(this.maxX, x);
				this.maxY = Math.max(this.maxY, y);
				this.maxZ = Math.max(this.maxZ, z);
			}
		}
	}
}
