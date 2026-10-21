package dev.echoliminal.endless.gen;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Dev check (used by the endlessExport tooling): every module storey of an area must be reachable from the spawn or leave the area. */
public final class ConnectivityCheck {
	private ConnectivityCheck() {
	}

	private static long key(int mx, int mz, int k) {
		return ((long) mx & 0xFFFFF) << 40 | ((long) mz & 0xFFFFF) << 16 | (k + 100) & 0xFFFF;
	}

	private static boolean passable(World w, int axis, int mx, int mz, int k, World.Mod a, World.Mod b) {
		if (a.field() && b.field()) {
			return true;
		}
		if (a.field() || b.field()) {
			if (k != 0) {
				return false;
			}
		}
		World.Edge e = w.edge(axis, mx, mz, k);
		return e.state() != World.WALL && e.state() != World.NONE || e.state() == World.NONE && a.field() && b.field();
	}

	public static String run(long seed, int radius) {
		World w = new World(seed);
		Map<Long, Integer> comp = new HashMap<>();
		int next = 0;
		int[] size = new int[1 << 16];
		boolean[] touchesBorder = new boolean[1 << 16];
		int total = 0;
		int spawnComp = -1;
		double[] sp = new EndlessGenerator(seed).spawn();
		int smx = Math.floorDiv((int) sp[0], 8), smz = Math.floorDiv((int) sp[2], 8);
		for (int mz = -radius; mz < radius; mz++) {
			for (int mx = -radius; mx < radius; mx++) {
				World.Mod m = w.mod(mx, mz);
				int kLo = m.field() ? 0 : m.mac.lo, kHi = m.field() ? 0 : m.mac.hi;
				for (int k = kLo; k <= kHi; k++) {
					if (comp.containsKey(key(mx, mz, k))) {
						continue;
					}
					int id = next++;
					ArrayDeque<int[]> q = new ArrayDeque<>();
					q.add(new int[] {mx, mz, k});
					comp.put(key(mx, mz, k), id);
					while (!q.isEmpty()) {
						int[] c = q.poll();
						size[id]++;
						total++;
						if (Math.abs(c[0]) >= radius - 1 || Math.abs(c[1]) >= radius - 1) {
							touchesBorder[id] = true;
						}
						if (c[0] == smx && c[1] == smz && c[2] == 0) {
							spawnComp = id;
						}
						World.Mod cm = w.mod(c[0], c[1]);
						int[][] nb = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 1}, {0, 1, 1}};
						for (int[] d : nb) {
							int nx = c[0] + d[0], nz = c[1] + d[1];
							if (Math.abs(nx) > radius || Math.abs(nz) > radius) {
								touchesBorder[id] = true;
								continue;
							}
							World.Mod nm = w.mod(nx, nz);
							int ck = cm.field() ? 0 : c[2];
							boolean ok;
							int nk = c[2];
							if (cm.field() && !nm.field()) {
								nk = 0;
							}
							if (nm.field()) {
								nk = 0;
							}
							if (!cm.field() && nm.field() && c[2] != 0 || cm.field() && !nm.field() && !nm.mac.has(0)) {
								continue;
							}
							if (!nm.field() && !nm.mac.has(nk)) {
								continue;
							}
							World.Mod a = d[0] < 0 || d[1] < 0 ? nm : cm, b = d[0] < 0 || d[1] < 0 ? cm : nm;
							int ex = d[0] < 0 || d[1] < 0 ? c[0] : nx, ez = d[0] < 0 || d[1] < 0 ? c[1] : nz;
							ok = passable(w, d[2], ex, ez, nk, a, b);
							if (ok && comp.putIfAbsent(key(nx, nz, nk), id) == null) {
								q.add(new int[] {nx, nz, nk});
							}
						}
						if (cm.core && !cm.field()) {
							for (int dk = -1; dk <= 1; dk += 2) {
								int nk = c[2] + dk;
								if (cm.mac.has(nk) && comp.putIfAbsent(key(c[0], c[1], nk), id) == null) {
									q.add(new int[] {c[0], c[1], nk});
								}
							}
						}
					}
				}
			}
		}
		int isolatedComps = 0, isolatedNodes = 0, outside = 0;
		for (int i = 0; i < next; i++) {
			if (i != spawnComp && !touchesBorder[i]) {
				isolatedComps++;
				isolatedNodes += size[i];
			} else if (i != spawnComp) {
				outside += size[i];
			}
		}
		return String.format("connectivity r=%d: %d module storeys, %d components; spawn component %d storeys; %d sealed components (%d storeys) fully inside the area and cut off; %d storeys in other components touching the area border",
			radius, total, next, spawnComp < 0 ? 0 : size[spawnComp], isolatedComps, isolatedNodes, outside);
	}
}
