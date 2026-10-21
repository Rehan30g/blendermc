package dev.blendermc.liminal;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The solid part of a surface cell: the region BEHIND its triangles, at 1/16. Rays are cast through each voxel row
 * along the cell's main normal axis: hitting a triangle from its back means "inside". Voxels within 2/16 of a surface
 * (along the row) are left empty so the exact triangle collision alone decides contact; the interior only holds up
 * what got inside (primed TNT, a player standing in a freshly restored cell).
 */
final class CellInterior {
	private static final int RES = MeshCollision.RES;
	private static final double CLEARANCE = 2.0 / RES;

	/** Solid-or-not for a point (cell-local) whose row hits no triangle of the cell; null = empty. */
	@FunctionalInterface
	interface Fallback {
		boolean solid(double x, double y, double z);
	}

	private CellInterior() {
	}

	/** Filled voxels behind the triangles, or null if none. */
	static @Nullable BitSetDiscreteVoxelShape build(float[] tris, @Nullable Fallback fallback) {
		return build(tris, fallback, CLEARANCE);
	}

	/** {@link #build(float[], Fallback)} with the given clearance from the surface (0 = right up to it, for rays). */
	static @Nullable BitSetDiscreteVoxelShape build(float[] tris, @Nullable Fallback fallback, double clearance) {
		return build(tris, fallback, clearance, false);
	}

	/**
	 * The solid that holds things up (collision): behind a surface it is only a slab of at most 2/16 measured along the
	 * surface's normal, so standing on a back face puts the feet on the surface, not on a block-thick volume. The full
	 * depth to the cell boundary stays only where {@code thick} says the space behind the surface is solid (stone-filled
	 * level volume); null means never.
	 */
	static @Nullable BitSetDiscreteVoxelShape buildThin(float[] tris, @Nullable Fallback thick) {
		return build(tris, thick, CLEARANCE, true);
	}

	private static @Nullable BitSetDiscreteVoxelShape build(float[] tris, @Nullable Fallback fallback, double clearance, boolean thin) {
		int count = tris.length / 9;
		if (count == 0) {
			return null;
		}
		// Main axis: area-weighted |normal| per axis.
		double[] weight = new double[3];
		double[] normals = new double[count * 3];
		for (int t = 0; t < count; t++) {
			int i = t * 9;
			double ux = tris[i + 3] - tris[i], uy = tris[i + 4] - tris[i + 1], uz = tris[i + 5] - tris[i + 2];
			double vx = tris[i + 6] - tris[i], vy = tris[i + 7] - tris[i + 1], vz = tris[i + 8] - tris[i + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-12) {
				continue;
			}
			normals[t * 3] = nx / len;
			normals[t * 3 + 1] = ny / len;
			normals[t * 3 + 2] = nz / len;
			weight[0] += Math.abs(nx);
			weight[1] += Math.abs(ny);
			weight[2] += Math.abs(nz);
		}
		int a = weight[1] >= weight[0] && weight[1] >= weight[2] ? 1 : weight[0] >= weight[2] ? 0 : 2;
		int b = (a + 1) % 3, c = (a + 2) % 3;
		BitSetDiscreteVoxelShape voxels = new BitSetDiscreteVoxelShape(RES, RES, RES);
		boolean any = false;
		double[] hitT = new double[count];
		int[] hitSign = new int[count];
		int[] hitTri = new int[count];
		/** Per row: whether the space beyond the cell boundary behind the surface is solid (0 unknown, 1 no, 2 yes), for -a and +a. */
		int[] thickBehind = new int[2];
		int[] neighbourhood = new int[2];
		int[] idx = new int[3];
		double[] point = new double[3];
		for (int j = 0; j < RES; j++) {
			for (int k = 0; k < RES; k++) {
				double pb = (j + 0.5) / RES, pc = (k + 0.5) / RES;
				int hits = 0;
				thickBehind[0] = 0;
				thickBehind[1] = 0;
				for (int t = 0; t < count; t++) {
					double na = normals[t * 3 + a];
					if (Math.abs(na) < 1.0E-6) {
						continue;
					}
					double s = lineHit(tris, t * 9, a, b, c, pb, pc);
					if (!Double.isNaN(s)) {
						hitT[hits] = s;
						hitSign[hits] = na > 0 ? 1 : -1;
						hitTri[hits] = t;
						hits++;
					}
				}
				for (int i = 0; i < RES; i++) {
					double s = (i + 0.5) / RES;
					double ahead = Double.MAX_VALUE, behind = -Double.MAX_VALUE, nearest = Double.MAX_VALUE;
					int aheadSign = 0, behindSign = 0, aheadHit = -1, behindHit = -1;
					for (int h = 0; h < hits; h++) {
						double ht = hitT[h];
						nearest = Math.min(nearest, Math.abs(ht - s));
						if (ht > s && ht < ahead) {
							ahead = ht;
							aheadSign = hitSign[h];
							aheadHit = h;
						} else if (ht <= s && ht > behind) {
							behind = ht;
							behindSign = hitSign[h];
							behindHit = h;
						}
					}
					boolean solid;
					int owner = -1;
					if (aheadSign != 0) {
						solid = aheadSign > 0;
						owner = aheadHit;
					} else if (behindSign != 0) {
						solid = behindSign < 0;
						owner = behindHit;
					} else {
						solid = true;
					}
					if (thin && solid && owner >= 0) {
						// Behind the owner surface: full depth only if the space beyond the cell is solid, else a thin slab.
						int side = hitSign[owner] > 0 ? 0 : 1;
						if (thickBehind[side] == 0) {
							point[a] = side == 0 ? -0.5 : 1.5;
							point[b] = pb;
							point[c] = pc;
							thickBehind[side] = fallback != null && fallback.solid(point[0], point[1], point[2]) && backedAround(fallback, a, b, c, side, neighbourhood) ? 2 : 1;
						}
						if (thickBehind[side] == 1) {
							int tn = hitTri[owner] * 3;
							double na = Math.abs(normals[tn + a]);
							double half = 0.5 / RES * (na + Math.abs(normals[tn + b]) + Math.abs(normals[tn + c]));
							double depth = Math.abs(hitT[owner] - s) * na;
							if (depth < half - 1.0E-9 || depth > 1.5 / RES + 1.0E-9) {
								continue;
							}
							nearest = Double.MAX_VALUE;
						}
					}
					if (nearest < clearance) {
						continue;
					}
					if (owner < 0) {
						point[a] = s;
						point[b] = pb;
						point[c] = pc;
						solid = fallback != null && fallback.solid(point[0], point[1], point[2])
							&& (!thin || backedAround(fallback, a, b, c, 0, neighbourhood) && backedAround(fallback, a, b, c, 1, neighbourhood));
					}
					if (solid) {
						idx[a] = i;
						idx[b] = j;
						idx[c] = k;
						voxels.fill(idx[0], idx[1], idx[2]);
						any = true;
					}
				}
			}
		}
		return any ? voxels : null;
	}

	/** Whether the whole cell-wide patch behind the surface, just beyond the cell boundary, is solid (cached per side: 0 unknown, 1 no, 2 yes). */
	private static boolean backedAround(Fallback f, int a, int b, int c, int side, int[] cache) {
		if (cache[side] == 0) {
			boolean all = true;
			double[] point = new double[3];
			point[a] = side == 0 ? -0.5 : 1.5;
			for (double pb : new double[] {0.1, 0.5, 0.9}) {
				for (double pc : new double[] {0.1, 0.5, 0.9}) {
					point[b] = pb;
					point[c] = pc;
					all &= f.solid(point[0], point[1], point[2]);
				}
			}
			cache[side] = all ? 2 : 1;
		}
		return cache[side] == 2;
	}

	/** Where the line {b=pb, c=pc} along axis a crosses triangle t (its a-coordinate), or NaN. */
	private static double lineHit(float[] v, int t, int a, int b, int c, double pb, double pc) {
		double b0 = v[t + b], c0 = v[t + c], b1 = v[t + 3 + b], c1 = v[t + 3 + c], b2 = v[t + 6 + b], c2 = v[t + 6 + c];
		double det = (b1 - b0) * (c2 - c0) - (b2 - b0) * (c1 - c0);
		if (Math.abs(det) < 1.0E-12) {
			return Double.NaN;
		}
		double u = ((pb - b0) * (c2 - c0) - (b2 - b0) * (pc - c0)) / det;
		double w = ((b1 - b0) * (pc - c0) - (pb - b0) * (c1 - c0)) / det;
		if (u < -1.0E-9 || w < -1.0E-9 || u + w > 1 + 1.0E-9) {
			return Double.NaN;
		}
		return v[t + a] + u * (v[t + 3 + a] - v[t + a]) + w * (v[t + 6 + a] - v[t + a]);
	}
}
