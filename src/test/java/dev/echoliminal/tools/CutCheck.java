package dev.echoliminal.tools;

import dev.echoliminal.liminal.CutFaces;
import dev.echoliminal.liminal.GlbLoader;
import dev.echoliminal.liminal.LiminalCells;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Dev tool: counts the stone cut faces of a liminal level with nothing dug, and with one cell dug out. Usage:
 * {@code CutCheck <file.glb>} (treated as a level without stone fill).
 */
public final class CutCheck {
	private CutCheck() {
	}

	public static void main(String[] args) throws Exception {
		Path path = Path.of(args[0]);
		String name = path.getFileName().toString().replaceFirst("[.]glb$", "");
		GlbLoader.Level level = GlbLoader.loadLevel(name, Files.readAllBytes(path));
		LiminalCells cells = new LiminalCells(level.mesh());
		System.out.println(name + ": stone_fill=" + level.settings().stoneFill() + ", " + cells.cells().size() + " cells");
		LiminalCells.Volume volume = cells.volume();
		System.out.println("nothing dug, previous rule (every cell-less neighbour open): " + count(cells, volume, Long.MIN_VALUE, true) + " cut faces");
		System.out.println("nothing dug, dug-only rule: " + count(cells, volume, Long.MIN_VALUE, false) + " cut faces " + java.util.Arrays.toString(BY_DIR));
		// A dug floor cell: the first cell with an upward triangle exactly on its bottom half boundary, far from the border.
		float[] p = level.mesh().positions();
		int[] idx = level.mesh().indices();
		LongIterator it = cells.cells().iterator();
		int shown = 0;
		while (it.hasNext() && shown < 3) {
			long cell = it.nextLong();
			int[] tris = cells.triangles(cell);
			boolean floor = tris.length == 2;
			for (int t : tris) {
				int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
				floor &= Math.abs(p[a + 1] - BlockPos.getY(cell) - 1) < 1e-3 && Math.abs(p[b + 1] - p[a + 1]) < 1e-3 && Math.abs(p[c + 1] - p[a + 1]) < 1e-3;
			}
			boolean surrounded = floor;
			for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
				surrounded &= cells.cells().contains(BlockPos.offset(cell, d));
			}
			if (surrounded) {
				System.out.println("dug floor cell " + BlockPos.of(cell).toShortString() + ", new rule: " + count(cells, volume, cell, false) + " cut faces");
				shown++;
			}
		}
	}

	private static int COPLANAR;

	/** Whether a mesh triangle (of the cell or its neighbours) lies in the quad's plane over the quad's centre. */
	private static boolean coplanar(LiminalCells cells, long cell, float[] q, int at) {
		float[] p = cells.mesh().positions();
		int[] idx = cells.mesh().indices();
		int f = -1;
		for (int a = 0; a < 3; a++) {
			if (q[at + a] == q[at + 3 + a] && q[at + a] == q[at + 6 + a] && q[at + a] == q[at + 9 + a]) {
				f = a;
			}
		}
		float[] c = new float[3];
		for (int a = 0; a < 3; a++) {
			c[a] = (q[at + a] + q[at + 3 + a] + q[at + 6 + a] + q[at + 9 + a]) / 4;
		}
		int u = (f + 1) % 3, v = (f + 2) % 3;
		for (Direction d : Direction.values()) {
			int[] tris = cells.triangles(BlockPos.offset(cell, d));
			if (tris == null) {
				continue;
			}
			for (int t : tris) {
				int[] k = {idx[t * 3] * 3, idx[t * 3 + 1] * 3, idx[t * 3 + 2] * 3};
				boolean flat = true;
				for (int i : k) {
					flat &= Math.abs(p[i + f] - q[at + f]) < 1e-3;
				}
				if (flat) {
					double det = (p[k[1] + u] - p[k[0] + u]) * (p[k[2] + v] - p[k[0] + v]) - (p[k[2] + u] - p[k[0] + u]) * (p[k[1] + v] - p[k[0] + v]);
					double l1 = ((c[u] - p[k[0] + u]) * (p[k[2] + v] - p[k[0] + v]) - (p[k[2] + u] - p[k[0] + u]) * (c[v] - p[k[0] + v])) / det;
					double l2 = ((p[k[1] + u] - p[k[0] + u]) * (c[v] - p[k[0] + v]) - (c[u] - p[k[0] + u]) * (p[k[1] + v] - p[k[0] + v])) / det;
					if (l1 >= -1e-6 && l2 >= -1e-6 && l1 + l2 <= 1 + 1e-6) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static String tris(LiminalCells cells, long cell) {
		float[] p = cells.mesh().positions();
		int[] idx = cells.mesh().indices();
		StringBuilder sb = new StringBuilder();
		for (int t : cells.triangles(cell)) {
			sb.append("[");
			for (int k = 0; k < 3; k++) {
				int a = idx[t * 3 + k] * 3;
				sb.append(String.format("(%.2f %.2f %.2f)", p[a], p[a + 1], p[a + 2]));
			}
			sb.append("] ");
		}
		return sb.toString();
	}

	private static final int[] BY_DIR = new int[6];
	private static int SAMPLES;

	private static int count(LiminalCells cells, LiminalCells.Volume volume, long dug, boolean old) {
		int faces = 0;
		COPLANAR = 0;
		java.util.Arrays.fill(BY_DIR, 0);
		LongIterator it = cells.cells().iterator();
		while (it.hasNext()) {
			long cell = it.nextLong();
			if (cell == dug) {
				continue;
			}
			float[] quads = CutFaces.compute(cells, cell, d -> {
				long n = BlockPos.offset(cell, d);
				if (n == dug) {
					return true;
				}
				return old && !cells.cells().contains(n);
			});
			if (quads != null) {
				faces += quads.length / 13;
				for (int q = 0; q < quads.length; q += 13) {
					BY_DIR[(int) quads[q + 12]]++;
					if (coplanar(cells, cell, quads, q)) {
						COPLANAR++;
					}
					if (dug == Long.MIN_VALUE && !old && SAMPLES++ < 14) {
						System.out.println("  sample cell " + BlockPos.of(cell).toShortString() + " dir " + Direction.values()[(int) quads[q + 12]] + " quad " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(quads, q, q + 12)) + " tris " + tris(cells, cell));
					}
				}
			}
		}
		System.out.println("    (of which coplanar with another cell's triangle: " + COPLANAR + ")");
		return faces;
	}
}
