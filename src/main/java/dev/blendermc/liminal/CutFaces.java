package dev.blendermc.liminal;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * The cut faces of a mesh cell: the cross-section of its solid part (behind its triangles) on each face whose
 * neighbour is open, drawn as stone so a dug hole does not show the void. Pure geometry, shared by the renderer and
 * the {@code cutCheck} dev tool.
 */
public final class CutFaces {
	/** Fraction of a face that surface triangles lying in its plane must cover for the face to need no cut. */
	private static final float COVERED = 0.95F;

	private CutFaces() {
	}

	/**
	 * The cross-section of the cell's solid part (behind its triangles) on each face whose neighbour is open, as
	 * quads. The surface meets a face plane exactly along triangle edges (the mesh is cut on the block grid); each
	 * such edge is swept across the face, along the in-plane axis that points most into the solid side, to the face
	 * border, and kept if the swept quad's middle is solid. A face without any surface edge is drawn whole when its
	 * middle is solid (a face fully behind a wall or under a floor).
	 */
	public static float @Nullable [] compute(LiminalCells cells, long cell, Predicate<Direction> open) {
		int[] tris = cells.triangles(cell);
		if (tris == null) {
			return null;
		}
		LiminalMesh mesh = cells.mesh();
		// Thin surfaces (beams, emissive panels, decals) have no solid behind them: no cut faces.
		tris = java.util.Arrays.stream(tris).filter(t -> !mesh.materials().get(mesh.triangleMaterial()[t]).thin()).toArray();
		if (tris.length == 0) {
			return null;
		}
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		int[] origin = {BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell)};
		float[] normals = new float[tris.length * 3];
		for (int i = 0; i < tris.length; i++) {
			faceNormal(p, idx, tris[i], normals, i * 3);
		}
		FloatArrayList out = new FloatArrayList();
		for (Direction d : Direction.values()) {
			if (!open.test(d)) {
				continue;
			}
			int f = d.getAxis().ordinal();
			int u = (f + 1) % 3, v = (f + 2) % 3;
			float plane = origin[f] + (d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
			// Points on the face are tested slightly inside the cell.
			float inward = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -1.0E-3F : 1.0E-3F;
			// A floor, ceiling or wall lying on this face and facing out of it already covers the face: no stone in its plane.
			float sign = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
			float covered = 0;
			for (int i = 0; i < tris.length; i++) {
				if (normals[i * 3 + f] * sign < 0.999F) {
					continue;
				}
				int a = idx[tris[i] * 3] * 3, b = idx[tris[i] * 3 + 1] * 3, c = idx[tris[i] * 3 + 2] * 3;
				if (Math.abs(p[a + f] - plane) < 1.0E-3F && Math.abs(p[b + f] - plane) < 1.0E-3F && Math.abs(p[c + f] - plane) < 1.0E-3F) {
					covered += Math.abs((p[b + u] - p[a + u]) * (p[c + v] - p[a + v]) - (p[c + u] - p[a + u]) * (p[b + v] - p[a + v])) / 2;
				}
			}
			if (covered >= COVERED) {
				continue;
			}
			boolean anyEdge = false;
			for (int i = 0; i < tris.length; i++) {
				int t = tris[i];
				float nf = normals[i * 3 + f], nu = normals[i * 3 + u], nv = normals[i * 3 + v];
				if (Math.abs(nf) > 0.999F) {
					continue;
				}
				for (int k = 0; k < 3; k++) {
					int a = idx[t * 3 + k] * 3, b = idx[t * 3 + (k + 1) % 3] * 3;
					if (Math.abs(p[a + f] - plane) > 1.0E-3F || Math.abs(p[b + f] - plane) > 1.0E-3F) {
						continue;
					}
					anyEdge = true;
					// Sweep along the in-plane axis most opposite to the normal (towards the solid side).
					int s = Math.abs(nu) >= Math.abs(nv) ? u : v;
					float ns = s == u ? nu : nv;
					if (Math.abs(ns) < 1.0E-4F) {
						continue;
					}
					float border = origin[s] + (ns > 0 ? 0 : 1);
					float[] quad = new float[13];
					corner(quad, 0, p, a, -1, 0);
					corner(quad, 3, p, b, -1, 0);
					corner(quad, 6, p, b, s, border);
					corner(quad, 9, p, a, s, border);
					quad[12] = d.ordinal();
					float[] mid = {(quad[0] + quad[3] + quad[6] + quad[9]) / 4, (quad[1] + quad[4] + quad[7] + quad[10]) / 4,
						(quad[2] + quad[5] + quad[8] + quad[11]) / 4};
					mid[f] += inward;
					if (area(quad) > 1.0E-6F && solid(p, idx, tris, normals, mid)) {
						out.addElements(out.size(), quad);
					}
				}
			}
			if (!anyEdge) {
				float[] mid = {origin[0] + 0.5F, origin[1] + 0.5F, origin[2] + 0.5F};
				mid[f] = plane + inward;
				if (solid(p, idx, tris, normals, mid)) {
					float[] quad = new float[13];
					float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
					for (int k = 0; k < 4; k++) {
						quad[k * 3 + f] = plane;
						quad[k * 3 + u] = origin[u] + uv[k][0];
						quad[k * 3 + v] = origin[v] + uv[k][1];
					}
					quad[12] = d.ordinal();
					out.addElements(out.size(), quad);
				}
			}
		}
		return out.isEmpty() ? null : out.toFloatArray();
	}

	/** Copies vertex {@code src} into the quad at {@code at}, with axis {@code axis} (if >= 0) set to {@code value}. */
	private static void corner(float[] quad, int at, float[] p, int src, int axis, float value) {
		quad[at] = p[src];
		quad[at + 1] = p[src + 1];
		quad[at + 2] = p[src + 2];
		if (axis >= 0) {
			quad[at + axis] = value;
		}
	}

	private static float area(float[] q) {
		float ax = q[6] - q[0], ay = q[7] - q[1], az = q[8] - q[2], bx = q[9] - q[3], by = q[10] - q[4], bz = q[11] - q[5];
		float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
		return (float) Math.sqrt(cx * cx + cy * cy + cz * cz) / 2;
	}

	/**
	 * Whether a point of the cell lies behind its surface: a ray along each axis (both ways) is cast through the
	 * cell's triangles, and the nearest hit decides (hit from the back = solid). The first axis with a hit wins,
	 * starting with the axis the surface faces most.
	 */
	private static boolean solid(float[] p, int[] idx, int[] tris, float[] normals, float[] point) {
		double[] weight = new double[3];
		for (int i = 0; i < tris.length; i++) {
			for (int a = 0; a < 3; a++) {
				weight[a] += Math.abs(normals[i * 3 + a]);
			}
		}
		int[] order = {0, 1, 2};
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2 - i; j++) {
				if (weight[order[j]] < weight[order[j + 1]]) {
					int tmp = order[j];
					order[j] = order[j + 1];
					order[j + 1] = tmp;
				}
			}
		}
		for (int axis : order) {
			double best = Double.MAX_VALUE;
			int bestSign = 0;
			for (int dir = -1; dir <= 1; dir += 2) {
				for (int i = 0; i < tris.length; i++) {
					double t = crossing(p, idx, tris[i], axis, point);
					if (Double.isNaN(t)) {
						continue;
					}
					double dist = (t - point[axis]) * dir;
					if (dist > 1.0E-5 && dist < best) {
						best = dist;
						// Hit from the back when the ray runs along the normal.
						bestSign = normals[i * 3 + axis] * dir > 0 ? 1 : -1;
					}
				}
			}
			if (bestSign != 0) {
				return bestSign > 0;
			}
		}
		return false;
	}

	/** Where the line through {@code point} along {@code axis} crosses triangle t (its axis coordinate), or NaN. */
	private static double crossing(float[] p, int[] idx, int t, int axis, float[] point) {
		int b = (axis + 1) % 3, c = (axis + 2) % 3;
		int i0 = idx[t * 3] * 3, i1 = idx[t * 3 + 1] * 3, i2 = idx[t * 3 + 2] * 3;
		double x0 = p[i0 + b], y0 = p[i0 + c], x1 = p[i1 + b], y1 = p[i1 + c], x2 = p[i2 + b], y2 = p[i2 + c];
		double det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(det) < 1.0E-12) {
			return Double.NaN;
		}
		double px = point[b] - x0, py = point[c] - y0;
		double l1 = (px * (y2 - y0) - (x2 - x0) * py) / det;
		double l2 = ((x1 - x0) * py - px * (y1 - y0)) / det;
		if (l1 < 0 || l2 < 0 || l1 + l2 > 1) {
			return Double.NaN;
		}
		return p[i0 + axis] + l1 * (p[i1 + axis] - p[i0 + axis]) + l2 * (p[i2 + axis] - p[i0 + axis]);
	}

	private static void faceNormal(float[] p, int[] idx, int t, float[] out, int at) {
		int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
		float ax = p[b] - p[a], ay = p[b + 1] - p[a + 1], az = p[b + 2] - p[a + 2];
		float bx = p[c] - p[a], by = p[c + 1] - p[a + 1], bz = p[c + 2] - p[a + 2];
		float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 1.0E-9F) {
			out[at] = nx / len;
			out[at + 1] = ny / len;
			out[at + 2] = nz / len;
		}
	}
}
