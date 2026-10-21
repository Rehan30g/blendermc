package dev.echoliminal.tools;

import dev.echoliminal.liminal.GlbLoader;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.LiminalPhysics;
import dev.echoliminal.liminal.MeshCollision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Dev tool: loads a liminal .glb and prints what the game will see (triangles, bounds, materials, spawn), plus a
 * collision/ray sanity probe under the spawn. Usage: {@code GlbCheck <file.glb>...}
 */
public final class GlbCheck {
	private GlbCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("usage: GlbCheck <file.glb>...");
			System.exit(2);
		}
		for (String arg : args) {
			Path path = Path.of(arg);
			String name = path.getFileName().toString().replaceFirst("\\.glb$", "");
			long start = System.nanoTime();
			GlbLoader.Level level = GlbLoader.loadLevel(name, Files.readAllBytes(path));
			LiminalMesh mesh = level.mesh();
			long loaded = System.nanoTime();
			MeshCollision collision = new MeshCollision(mesh);
			long built = System.nanoTime();
			System.out.printf("%s: %d vertices, %d triangles (%d colliding), load %d ms, collision grid %d ms%n", path, mesh.positions().length / 3,
				mesh.triangleCount(), collision.solidTriangles(), (loaded - start) / 1_000_000, (built - loaded) / 1_000_000);
			double maxEdge = 0;
			float[] p = mesh.positions();
			int[] idx = mesh.indices();
			for (int t = 0; t < idx.length; t += 3) {
				for (int e = 0; e < 3; e++) {
					int a = idx[t + e] * 3, b = idx[t + (e + 1) % 3] * 3;
					maxEdge = Math.max(maxEdge, Math.sqrt(Math.pow(p[a] - p[b], 2) + Math.pow(p[a + 1] - p[b + 1], 2) + Math.pow(p[a + 2] - p[b + 2], 2)));
				}
			}
			System.out.printf("  longest triangle edge %.3f%n", maxEdge);
			System.out.println("  bounds " + mesh.bounds() + " (origin " + LiminalLevels.ORIGIN.toShortString() + ")");
			System.out.printf("  spawn %.3f %.3f %.3f yaw %.1f%n", mesh.spawn().x, mesh.spawn().y, mesh.spawn().z, mesh.spawnYaw());
			System.out.println("  sky " + level.settings() + (level.sky() == null ? ", no skybox"
				: ", skybox " + level.sky().triangleCount() + " triangles, bounds " + level.sky().bounds()));
			if (level.settings().dayCycle()) {
				dev.echoliminal.liminal.LevelSky ds = level.settings();
				dev.echoliminal.liminal.LiminalDay.Path dp = dev.echoliminal.liminal.LiminalDay.path(ds);
				System.out.printf("  day cycle: %.0f s per day, start %.1f ticks, path rise axis (%.3f, 0, %.3f), peak (%.3f, %.3f, %.3f)%n", ds.dayLength(), dp.start(), dp.rx(), dp.rz(),
					dp.mx(), dp.my(), dp.mz());
				System.out.println("  sun_dir toSun = " + ds.sunDirection().scale(-1.0));
				for (int t : new int[] {0, 1500, (int) dp.start(), 6000, 10500, 12000, 13000, 18000, 22500}) {
					dev.echoliminal.liminal.LiminalDay.Day d = dev.echoliminal.liminal.LiminalDay.atTime(dp, t);
					System.out.printf("    t=%5d toSun (%.3f, %.3f, %.3f) day %.2f twilight %.2f moonUp %.2f slice %.2f shade(up) %.2f%n", t, d.sx, d.sy, d.sz, d.day, d.twilight,
						d.moonUp, d.sliceU, d.shade(0, 1, 0));
				}
				for (long gt : new long[] {0, 6000, 12000, 24000, 1200 * 20}) {
					System.out.printf("    gameTime %d -> t=%.1f%n", gt, dev.echoliminal.liminal.LiminalDay.time(ds, name, gt));
				}
			}
			int[] perMaterial = new int[mesh.materials().size()];
			for (int m : mesh.triangleMaterial()) {
				perMaterial[m]++;
			}
			for (int i = 0; i < mesh.materials().size(); i++) {
				LiminalMesh.Material m = mesh.materials().get(i);
				System.out.printf("  material %d '%s': color %.2f %.2f %.2f %.2f, texture %s, emissive %s, collides %s, %d triangles%n", i, m.name(),
					m.baseColor()[0], m.baseColor()[1], m.baseColor()[2], m.baseColor()[3],
					m.texturePng() == null ? "none" : m.texturePng().length + " bytes png", m.emissive(), m.collides(), perMaterial[i]);
			}

			// Ray-traced lighting bake (same estimator the client runs): direct pass, bounce pass, smoothing.
			for (int run = 0; run < 2; run++) {
				long b0 = System.nanoTime();
				dev.echoliminal.liminal.light.LightModel model = new dev.echoliminal.liminal.light.LightModel(mesh, level.settings());
				long b1 = System.nanoTime();
				float[] nor = mesh.normals();
				int verts = p.length / 3;
				float[] nrm = new float[verts * 3];
				for (int v = 0; v < verts; v++) {
					float x = nor.length > v * 3 + 2 ? nor[v * 3] : 0, y = nor.length > v * 3 + 2 ? nor[v * 3 + 1] : 1, z = nor.length > v * 3 + 2 ? nor[v * 3 + 2] : 0;
					float len = (float) Math.sqrt(x * x + y * y + z * z);
					nrm[v * 3] = x / len;
					nrm[v * 3 + 1] = y / len;
					nrm[v * 3 + 2] = z / len;
				}
				float[] legacy = new float[verts], direct = new float[verts], raw = new float[verts], fin = new float[verts];
				dev.echoliminal.liminal.light.LightModel.legacySampling = true;
				model.parallel(verts, (v, probe) -> {
					model.direct(probe, dev.echoliminal.liminal.light.LightSources.EMPTY, p[v * 3], p[v * 3 + 1], p[v * 3 + 2], nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2], false, v, true);
					legacy[v] = probe.block;
				});
				dev.echoliminal.liminal.light.LightModel.legacySampling = false;
				long b2 = System.nanoTime();
				model.parallel(verts, (v, probe) -> {
					model.direct(probe, dev.echoliminal.liminal.light.LightSources.EMPTY, p[v * 3], p[v * 3 + 1], p[v * 3 + 2], nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2], false, v, true);
					direct[v] = probe.block;
				});
				model.setDirect(direct);
				long b3 = System.nanoTime();
				model.parallel(verts, (v, probe) -> raw[v] = dev.echoliminal.liminal.light.LightModel.combine(direct[v],
					model.bounce(probe, p[v * 3], p[v * 3 + 1], p[v * 3 + 2], nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2], false)));
				long b4 = System.nanoTime();
				dev.echoliminal.liminal.light.Smoothing smoothing = new dev.echoliminal.liminal.light.Smoothing(verts, mesh.indices(), mesh.indices().length, nrm);
				for (int v = 0; v < verts; v++) {
					fin[v] = smoothing.smooth(raw, v);
				}
				long b5 = System.nanoTime();
				System.out.printf("  light bake%s: BVH %d tris/%d nodes %d ms; legacy direct %d ms; direct %d ms, bounce %d ms, smoothing %d ms (%d vertices, total new %d ms)%n",
					run == 0 ? " (cold)" : "", model.scene.triangleCount(), model.scene.nodeCount(), (b1 - b0) / 1_000_000, (b2 - b1) / 1_000_000,
					(b3 - b2) / 1_000_000, (b4 - b3) / 1_000_000, (b5 - b4) / 1_000_000, verts, (b5 - b2) / 1_000_000);
				if (run == 1) {
					// A corridor floor strip: upward-facing vertices at the lowest height, the row with most vertices.
					float lowest = Float.MAX_VALUE;
					for (int v = 0; v < verts; v++) {
						if (nrm[v * 3 + 1] > 0.99F) {
							lowest = Math.min(lowest, p[v * 3 + 1]);
						}
					}
					java.util.Map<Integer, java.util.List<Integer>> rows = new java.util.HashMap<>();
					for (int v = 0; v < verts; v++) {
						if (nrm[v * 3 + 1] > 0.99F && Math.abs(p[v * 3 + 1] - lowest) < 0.01F) {
							rows.computeIfAbsent(Math.round(p[v * 3 + 2] * 4), k -> new ArrayList<>()).add(v);
						}
					}
					java.util.List<Integer> best = new ArrayList<>();
					for (java.util.List<Integer> row : rows.values()) {
						if (row.size() > best.size()) {
							best = row;
						}
					}
					best.sort((a, b) -> Float.compare(p[a * 3], p[b * 3]));
					int n = Math.min(20, best.size());
					int from = Math.max(0, (best.size() - n) / 2);
					StringBuilder l = new StringBuilder(), d = new StringBuilder(), f = new StringBuilder();
					double[][] values = {new double[n], new double[n], new double[n], new double[n]};
					for (int i = 0; i < n; i++) {
						int v = best.get(from + i);
						values[0][i] = legacy[v];
						values[1][i] = direct[v];
						values[2][i] = raw[v];
						values[3][i] = fin[v];
					}
					String[] names = {"legacy direct (random 1-2 rays)", "new direct (all samples)", "new + bounce", "new + bounce + smoothing"};
					System.out.println("  floor strip of " + n + " consecutive vertices (x " + p[best.get(from) * 3] + " .. " + p[best.get(from + n - 1) * 3] + "):");
					for (int k = 0; k < 4; k++) {
						StringBuilder row = new StringBuilder();
						double mean = 0, var = 0, step = 0;
						for (double x : values[k]) {
							mean += x / n;
							row.append(String.format("%.1f ", x));
						}
						for (int i = 0; i < n; i++) {
							var += (values[k][i] - mean) * (values[k][i] - mean) / n;
							if (i > 0) {
								step += Math.abs(values[k][i] - values[k][i - 1]) / (n - 1);
							}
						}
						System.out.printf("    %-34s mean %.2f stddev %.3f mean step %.3f | %s%n", names[k], mean, Math.sqrt(var), step, row);
					}
				}
			}

			// Probe: drop a ray from the spawn straight down and look at the floor voxels there.
			Vec3 from = mesh.spawn().add(0, 0.5, 0);
			MeshCollision.Hit hit = collision.hit(from, from.add(0, -64, 0));
			if (hit == null) {
				System.out.println("  WARNING: nothing below the spawn");
			} else {
				System.out.printf("  floor below spawn at y=%.4f (normal %.2f %.2f %.2f, face %s, place cell %s)%n", hit.point().y, hit.normal().x,
					hit.normal().y, hit.normal().z, hit.direction(), hit.cellInFront().toShortString());
				int cx = (int) Math.floor(hit.point().x), cz = (int) Math.floor(hit.point().z);
				double top = Double.NEGATIVE_INFINITY;
				for (int cy = (int) Math.floor(hit.point().y) - 1; cy <= (int) Math.floor(hit.point().y) + 1; cy++) {
					VoxelShape shape = collision.cellShape(cx, cy, cz);
					if (!shape.isEmpty()) {
						top = Math.max(top, cy + shape.max(net.minecraft.core.Direction.Axis.Y, hit.point().x - cx, hit.point().z - cz));
					}
				}
				System.out.printf("  collision top there y=%.4f (feet would rest %.4f above the visual floor)%n", top, top - hit.point().y);
			}
			Vec3 up = mesh.spawn().add(0, 1.0, 0);
			MeshCollision.Hit ceiling = collision.hit(up, up.add(0, 64, 0));
			System.out.println(ceiling == null ? "  no ceiling above the spawn" : String.format("  ceiling above spawn at y=%.4f", ceiling.point().y));
			dev.echoliminal.liminal.LiminalCells walkCells = new dev.echoliminal.liminal.LiminalCells(mesh, level.settings().stoneFill());
			walk(mesh, walkCells);
			for (int turn = 0; turn < 8; turn++) {
				roam(mesh, walkCells, mesh.spawnYaw() + turn * 45.0F, false);
			}
			for (int turn = 0; turn < 8; turn++) {
				roam(mesh, walkCells, mesh.spawnYaw() + turn * 45.0F, true);
			}
			backSide(mesh, walkCells);
			backFloat(mesh, walkCells, collision);
			// Inside-out copy of the level: every floor is a back face seen from above.
			int[] flippedIdx = mesh.indices().clone();
			for (int t = 0; t < flippedIdx.length; t += 3) {
				int swap = flippedIdx[t + 1];
				flippedIdx[t + 1] = flippedIdx[t + 2];
				flippedIdx[t + 2] = swap;
			}
			LiminalMesh flipped = new LiminalMesh(name + "_flipped", mesh.positions(), mesh.normals(), mesh.uvs(), flippedIdx, mesh.triangleMaterial(),
				mesh.materials(), mesh.spawn(), mesh.spawnYaw(), mesh.bounds());
			dev.echoliminal.liminal.LiminalCells flippedCells = new dev.echoliminal.liminal.LiminalCells(flipped, level.settings().stoneFill());
			System.out.println("  INSIDE-OUT level:");
			for (int turn = 0; turn < 8; turn++) {
				roam(flipped, flippedCells, mesh.spawnYaw() + turn * 45.0F, turn % 2 == 1);
			}
			volume(mesh, walkCells);
			rays(mesh, walkCells);
			cells(mesh);
		}
	}

	/** Walks a player-sized box from the spawn along the spawn yaw for 3 s with the game's collision code. */
	private static void walk(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells) {
		double yaw = Math.toRadians(mesh.spawnYaw());
		double speed = 0.2158;
		double vx = -Math.sin(yaw) * speed, vz = Math.cos(yaw) * speed, vy = 0;
		Vec3 feet = mesh.spawn();
		boolean onGround = true;
		System.out.println("  walk from spawn, exact triangle collision (tick: x y z ground):");
		for (int tick = 1; tick <= 60; tick++) {
			vy = (vy - 0.08) * 0.98;
			AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
			Vec3 move = new Vec3(vx, vy, vz);
			Vec3 moved = LiminalPhysics.collide(box, move, 0.6F, onGround, area -> shapesIn(cells, area));
			onGround = moved.y != move.y && move.y < 0;
			if (moved.y != move.y) {
				vy = 0;
			}
			feet = feet.add(moved);
			if (tick % 4 == 0) {
				System.out.printf("    %2d: %.3f %.4f %.3f %s%n", tick, feet.x, feet.y, feet.z, onGround);
			}
		}
	}

	/** Walks 10 s from the spawn in one direction; one summary line: distance made, climb, airborne and stuck ticks. */
	private static void roam(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells, float yawDegrees, boolean sneak) {
		double yaw = Math.toRadians(yawDegrees);
		double speed = sneak ? 0.0647 : 0.2158;
		double vx = -Math.sin(yaw) * speed, vz = Math.cos(yaw) * speed, vy = 0;
		Vec3 feet = mesh.spawn();
		boolean onGround = true;
		int air = 0, stuck = 0;
		double minY = feet.y, maxY = feet.y, maxRise = 0;
		for (int tick = 1; tick <= 200; tick++) {
			vy = (vy - 0.08) * 0.98;
			AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
			Vec3 move = new Vec3(vx, vy, vz);
			if (sneak && onGround) {
				move = backOffFromEdge(box, move, cells);
			}
			Vec3 moved = LiminalPhysics.collide(box, move, 0.6F, onGround, area -> shapesIn(cells, area));
			onGround = moved.y != move.y && move.y < 0;
			if (moved.y != move.y) {
				vy = 0;
			}
			if (!onGround) {
				air++;
			}
			if (moved.horizontalDistance() < speed * 0.5) {
				stuck++;
			}
			maxRise = Math.max(maxRise, moved.y);
			feet = feet.add(moved);
			minY = Math.min(minY, feet.y);
			maxY = Math.max(maxY, feet.y);
		}
		System.out.printf("  %s yaw %5.0f: %.1f blocks, y %.2f..%.2f, biggest rise per tick %.3f, airborne %d/200, slow/stuck %d/200%n", sneak ? "sneak" : "roam ", yawDegrees,
			Math.sqrt(Math.pow(feet.x - mesh.spawn().x, 2) + Math.pow(feet.z - mesh.spawn().z, 2)), minY, maxY, maxRise, air, stuck);
	}


	/** Scenarios on the BACK side of triangles (front normal pointing down) and boxes starting inside interior volumes. */
	private static void backSide(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells) {
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		List<double[]> down = new ArrayList<>(), up = new ArrayList<>();
		for (int t = 0; t < idx.length; t += 3) {
			int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
			double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
			double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-6 || !mesh.materials().get(mesh.triangleMaterial()[t / 3]).collides()) {
				continue;
			}
			ny /= len;
			double[] centre = {(p[a] + p[b] + p[c]) / 3, (p[a + 1] + p[b + 1] + p[c + 1]) / 3, (p[a + 2] + p[b + 2] + p[c + 2]) / 3};
			if (Math.abs(ny) > 0.9 && len / 2 > 0.5) {
				(ny < 0 ? down : up).add(centre);
			}
		}
		System.out.printf("  backside: %d large down-facing, %d up-facing triangles%n", down.size(), up.size());
		String[] names = {"on back face (down-facing)", "start 0.1 inside interior (up-facing)", "start 0.4 inside interior (up-facing)"};
		for (int s = 0; s < 3; s++) {
			List<double[]> list = s == 0 ? down : up;
			double dy = s == 0 ? 0.0 : s == 1 ? -0.1 : -0.4;
			int runs = 0, frozen = 0, trapped = 0, oldRejects = 0, newRejects = 0;
			long worst = 0;
			double worstDist = Double.MAX_VALUE;
			for (int i = 0; i < list.size() && runs < 60; i += Math.max(1, list.size() / 60)) {
				double[] c = list.get(i);
				for (int dir = 0; dir < 4; dir++) {
					for (int mode = 0; mode < 3; mode++) {
						double yaw = Math.PI / 2 * dir + 0.3;
						double speed = mode == 1 ? 0.0647 : 0.2158;
						Vec3 feet = new Vec3(c[0], c[1] + dy + 1.0E-4, c[2]);
						double vy = 0;
						boolean onGround = false;
						long maxNs = 0;
						for (int tick = 0; tick < 200; tick++) {
							vy = (vy - 0.08) * 0.98;
							if (mode == 2 && onGround && tick % 10 == 0) {
								vy = 0.42;
							}
							AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
							Vec3 move = new Vec3(-Math.sin(yaw) * speed, vy, Math.cos(yaw) * speed);
							long t0 = System.nanoTime();
							if (mode == 1 && onGround) {
								move = backOffFromEdge(box, move, cells);
							}
							Vec3 moved = LiminalPhysics.collide(box, move, 0.6F, onGround, area -> shapesIn(cells, area));
							maxNs = Math.max(maxNs, System.nanoTime() - t0);
							AABB newBox = box.move(moved).deflate(1.0E-5F);
							VoxelShape oldShape = net.minecraft.world.phys.shapes.Shapes.create(box.deflate(1.0E-5F));
							for (VoxelShape shape : shapesIn(cells, newBox)) {
								if (!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape, oldShape, net.minecraft.world.phys.shapes.BooleanOp.AND)) {
									oldRejects++;
									if (!LiminalPhysics.harmlessContact(shape, newBox)) {
										newRejects++;
									}
									break;
								}
							}
							onGround = moved.y != move.y && move.y < 0;
							if (moved.y != move.y) {
								vy = 0;
							}
							feet = feet.add(moved);
							if (feet.y < c[1] - 30) {
								break;
							}
						}
						runs++;
						double dist = Math.sqrt(Math.pow(feet.x - c[0], 2) + Math.pow(feet.z - c[2], 2));
						if (dist < 0.5 && mode != 1) {
							trapped++;
						}
						worstDist = Math.min(worstDist, dist);
						if (maxNs > 20_000_000L) {
							frozen++;
						}
						worst = Math.max(worst, maxNs);
					}
				}
			}
			System.out.printf("  backside %s: %d runs, trapped (moved <0.5 in 200 ticks) %d, slow ticks >20 ms %d, worst tick %.2f ms, server move-check rollbacks: cell-wide test %d, exact triangle test %d%n", names[s], runs, trapped,
				frozen, worst / 1.0E6, oldRejects, newRejects);
		}
	}
	/**
	 * Drops a player box onto large flat down-facing triangles from above (the BACK side) and measures how high the feet
	 * rest above the visible surface. The solid behind a surface must not lift them off it (limit about 0.13).
	 */
	private static void backFloat(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells, MeshCollision collision) {
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		List<double[]> flat = new ArrayList<>();
		for (int t = 0; t < idx.length; t += 3) {
			int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
			double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
			double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-6 || !mesh.materials().get(mesh.triangleMaterial()[t / 3]).collides() || ny / len > -0.95 || len / 2 < 0.03) {
				continue;
			}
			flat.add(new double[] {(p[a] + p[b] + p[c]) / 3, (p[a + 1] + p[b + 1] + p[c + 1]) / 3, (p[a + 2] + p[b + 2] + p[c + 2]) / 3});
		}
		int runs = 0, over = 0, blocked = 0;
		double worst = 0, sum = 0;
		for (int i = 0; i < flat.size() && runs < 600; i += Math.max(1, flat.size() / 600)) {
			double[] c = flat.get(i);
			Vec3 feet = new Vec3(c[0], c[1] + 0.6, c[2]);
			double vy = 0;
			for (int tick = 0; tick < 40; tick++) {
				vy = (vy - 0.08) * 0.98;
				AABB box = new AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
				Vec3 move = new Vec3(0, vy, 0);
				Vec3 moved = LiminalPhysics.collide(box, move, 0.6F, false, area -> shapesIn(cells, area));
				if (moved.y != move.y) {
					vy = 0;
				}
				feet = feet.add(moved);
			}
			// The visible surface under the feet start: the first one a ray from just above it hits.
			MeshCollision.Hit seen = collision.hit(new Vec3(c[0], c[1] + 0.59, c[2]), new Vec3(c[0], c[1] - 1.0, c[2]));
			double rest = feet.y - (seen == null ? c[1] : seen.point().y);
			runs++;
			if (rest > 0.5 || rest < -0.2) {
				blocked++;
				continue;
			}
			sum += rest;
			worst = Math.max(worst, rest);
			if (rest > 0.13) {
				if (over < 6) {
					System.out.printf("    over: surface at %.3f %.3f %.3f, feet rest %.3f above it, volume solid just above the cell: %s%n", c[0], c[1], c[2], rest,
						cells.volume().solidAt(c[0], Math.floor(c[1]) + 1.5, c[2]));
				}
				over++;
			}
		}
		System.out.printf("  back-face float: %d drops onto the top of down-facing surfaces, feet above the visible surface: mean %.3f, worst %.3f, over 0.13: %d (other geometry in the way, skipped: %d)%n",
			runs, sum / Math.max(1, runs - blocked), worst, over, blocked);
	}
	/** Volume stats, and a primed-TNT-sized box dropped from inside the surface under the spawn must come to rest. */
	private static void volume(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells) {
		long start = System.nanoTime();
		int solid = cells.volume().solidCells().size();
		System.out.printf("  volume: %d stone cells (built in %d ms), %d surface cells%n", solid, (System.nanoTime() - start) / 1_000_000, cells.cells().size());
		dev.echoliminal.liminal.LiminalCells.Volume v = cells.volume();
		AABB b = mesh.bounds();
		long total = (long) (Math.floor(b.maxX) - Math.floor(b.minX) + 1) * (long) (Math.floor(b.maxY) - Math.floor(b.minY) + 3)
			* (long) (Math.floor(b.maxZ) - Math.floor(b.minZ) + 1);
		int underAir = 0, edge = 0, surfaceAbove = 0;
		for (long cell : v.solidCells()) {
			if (cells.triangles(cell) != null) {
				surfaceAbove++;
			}
			if (!v.inside(net.minecraft.core.BlockPos.offset(cell, net.minecraft.core.Direction.UP))) {
				underAir++;
			}
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				if (!v.inside(net.minecraft.core.BlockPos.offset(cell, d))) {
					edge++;
					break;
				}
			}
		}
		System.out.printf("  classes in the bounds box (%d cells): SURFACE %d, SOLID %d, AIR %d; solid cells that are also surface %d; stone with open air"
			+ " right above %d; stone touching original air on any side %d (those faces never draw)%n", total, cells.cells().size(), solid,
			total - cells.cells().size() - solid, surfaceAbove, underAir, edge);
		Vec3 feet = mesh.spawn().add(0, -0.4, 0);
		double vy = 0;
		for (int tick = 0; tick < 60; tick++) {
			vy = (vy - 0.04) * 0.98;
			AABB box = new AABB(feet.x - 0.49, feet.y, feet.z - 0.49, feet.x + 0.49, feet.y + 0.98, feet.z + 0.49);
			Vec3 push = LiminalPhysics.depenetrate(box, shapesIn(cells, box.inflate(0.0, 1.0, 0.0)));
			if (push != null) {
				feet = feet.add(push);
				box = box.move(push);
			}
			Vec3 move = new Vec3(0, vy, 0);
			Vec3 moved = LiminalPhysics.collide(box, move, 0.0F, false, area -> shapesIn(cells, area));
			if (moved.y != move.y) {
				vy = 0;
			}
			feet = feet.add(moved);
		}
		for (double fall : new double[] {0.0, -1.0, -3.0}) {
			Vec3 tnt = mesh.spawn().add(0, 3.0, 0);
			double speed = fall;
			for (int tick = 0; tick < 120; tick++) {
				speed = (speed - 0.04) * 0.98;
				AABB box = new AABB(tnt.x - 0.49, tnt.y, tnt.z - 0.49, tnt.x + 0.49, tnt.y + 0.98, tnt.z + 0.49);
				Vec3 move = new Vec3(0, speed, 0);
				Vec3 moved = LiminalPhysics.collide(box, move, 0.0F, false, area -> shapesIn(cells, area));
				if (moved.y != move.y) {
					speed = 0;
				}
				tnt = tnt.add(moved);
			}
			System.out.printf("  tnt dropped from 3 above the spawn surface at speed %.1f rests at y=%.3f (surface %.3f)%n", fall, tnt.y, mesh.spawn().y);
		}
		System.out.printf("  tnt dropped from 0.4 below the spawn surface rests at y=%.3f (surface %.3f, cell floor %d)%n", feet.y, mesh.spawn().y,
			(int) Math.floor(mesh.spawn().y - 0.4));
	}

	/**
	 * Clicks from the spawn eye in 16 directions (level and 30 degrees down) and straight down/up, walking cells like
	 * vanilla's BlockGetter.clip over the mesh cells' ray shapes: where would a placed block go, and could it?
	 */
	private static void rays(LiminalMesh mesh, dev.echoliminal.liminal.LiminalCells cells) {
		Vec3 eye = mesh.spawn().add(0, 1.62, 0);
		int hits = 0, placeable = 0, intoMesh = 0, intoPlayer = 0;
		java.util.Map<String, Integer> faces = new java.util.TreeMap<>();
		List<Vec3> dirs = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			double a = Math.PI * 2 * i / 16;
			dirs.add(new Vec3(Math.sin(a), 0, Math.cos(a)));
			dirs.add(new Vec3(Math.sin(a) * 0.866, -0.5, Math.cos(a) * 0.866));
			dirs.add(new Vec3(Math.sin(a) * 0.866, 0.5, Math.cos(a) * 0.866));
		}
		dirs.add(new Vec3(0, -1, 0));
		dirs.add(new Vec3(0, 1, 0));
		net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(mesh.spawn());
		for (Vec3 dir : dirs) {
			Vec3 to = eye.add(dir.scale(4.5));
			net.minecraft.world.phys.BlockHitResult hit = null;
			// Sample cells along the ray in order (fine steps; same order as vanilla's grid walk for our purposes).
			java.util.Set<Long> seen = new java.util.HashSet<>();
			for (int k = 0; k <= 450 && hit == null; k++) {
				net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(eye.add(dir.scale(k / 100.0)));
				if (!seen.add(pos.asLong())) {
					continue;
				}
				VoxelShape shape = cells.geometry(pos.asLong()).outline();
				if (!shape.isEmpty()) {
					hit = shape.clip(eye, to, pos);
				}
			}
			if (hit == null) {
				continue;
			}
			hits++;
			faces.merge(hit.getDirection().getSerializedName(), 1, Integer::sum);
			net.minecraft.core.BlockPos target = hit.getBlockPos().relative(hit.getDirection());
			if (cells.triangles(target.asLong()) != null && !cells.geometry(target.asLong()).mostlyEmpty()
				&& !cells.geometry(target.asLong()).sparseGround()) {
				intoMesh++;
			} else if (target.equals(feet) || target.equals(feet.above())) {
				intoPlayer++;
			} else {
				placeable++;
			}
		}
		System.out.printf("  clicks from the spawn: %d of %d hit mesh; faces %s; placement cell free or replaceable %d, is a solid mesh cell %d, is the player %d%n", hits,
			dirs.size(), faces, placeable, intoMesh, intoPlayer);
	}

	/** Vanilla's Player.maybeBackOffFromEdge (sneaking), with the game's noCollision filter on the mesh shapes. */
	private static Vec3 backOffFromEdge(AABB box, Vec3 move, dev.echoliminal.liminal.LiminalCells cells) {
		float step = 0.6F;
		double d = move.x, e = move.z;
		double h = Math.signum(d) * 0.05, i = Math.signum(e) * 0.05;
		while (d != 0.0 && canFall(box, d, 0.0, step, cells)) {
			if (Math.abs(d) <= 0.05) {
				d = 0.0;
				break;
			}
			d -= h;
		}
		while (e != 0.0 && canFall(box, 0.0, e, step, cells)) {
			if (Math.abs(e) <= 0.05) {
				e = 0.0;
				break;
			}
			e -= i;
		}
		while (d != 0.0 && e != 0.0 && canFall(box, d, e, step, cells)) {
			d = Math.abs(d) <= 0.05 ? 0.0 : d - h;
			e = Math.abs(e) <= 0.05 ? 0.0 : e - i;
		}
		return new Vec3(d, move.y, e);
	}

	private static boolean canFall(AABB box, double dx, double dz, double h, dev.echoliminal.liminal.LiminalCells cells) {
		return shapesIn(cells, new AABB(box.minX + 1.0E-7 + dx, box.minY - h - 1.0E-7, box.minZ + 1.0E-7 + dz, box.maxX - 1.0E-7 + dx, box.minY,
			box.maxZ - 1.0E-7 + dz)).isEmpty();
	}

	/** Grid split check: every triangle inside its owning cell; piece sizes through the item codec. */
	private static void cells(LiminalMesh mesh) {
		dev.echoliminal.liminal.LiminalCells cells = new dev.echoliminal.liminal.LiminalCells(mesh);
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		int outside = 0;
		double worst = 0;
		for (int t = 0; t < mesh.triangleCount(); t++) {
			long cell = cells.cellOf(t);
			int cx = net.minecraft.core.BlockPos.getX(cell), cy = net.minecraft.core.BlockPos.getY(cell), cz = net.minecraft.core.BlockPos.getZ(cell);
			double over = 0;
			for (int v = 0; v < 3; v++) {
				int vi = idx[t * 3 + v] * 3;
				over = Math.max(over, Math.max(cx - p[vi], p[vi] - cx - 1));
				over = Math.max(over, Math.max(cy - p[vi + 1], p[vi + 1] - cy - 1));
				over = Math.max(over, Math.max(cz - p[vi + 2], p[vi + 2] - cz - 1));
			}
			if (over > 1.0E-3) {
				outside++;
			}
			worst = Math.max(worst, over);
		}
		int most = 0;
		long mostCell = 0;
		int bytes = 0, largest = 0;
		int emptyShapes = 0;
		for (long cell : cells.cells()) {
			int n = cells.triangles(cell).length;
			if (n > most) {
				most = n;
				mostCell = cell;
			}
			if (cells.shape(cell, false).isEmpty()) {
				emptyShapes++;
			}
		}
		// Encode a few pieces the way the item component does to see the size.
		io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
		int sampled = 0;
		for (long cell : cells.cells()) {
			if (sampled++ >= 200) {
				break;
			}
			buf.clear();
			dev.echoliminal.liminal.MeshPiece piece = cells.piece(cell, 0);
			dev.echoliminal.liminal.MeshPiece.STREAM_CODEC.encode(buf, piece);
			dev.echoliminal.liminal.MeshPiece back = dev.echoliminal.liminal.MeshPiece.STREAM_CODEC.decode(buf);
			if (!back.equals(piece)) {
				System.out.println("  WARNING: piece does not survive the stream codec");
			}
			if (!piece.rotated(4).equals(piece) || piece.rotated(1).rotated(3).triangles().size() != piece.triangles().size()) {
				System.out.println("  WARNING: rotation is off");
			}
			bytes += buf.writerIndex();
			largest = Math.max(largest, buf.writerIndex());
		}
		System.out.printf("  cells: %d, most triangles in one cell %d (at %s), triangles outside their cell %d (worst %.5f), empty shapes %d%n",
			cells.cells().size(), most, net.minecraft.core.BlockPos.of(mostCell).toShortString(), outside, worst, emptyShapes);
		System.out.printf("  piece network size: avg %d bytes, largest %d bytes (sampled %d)%n", bytes / Math.max(1, Math.min(sampled, 200)), largest,
			Math.min(sampled, 200));
	}

	private static List<VoxelShape> shapesIn(dev.echoliminal.liminal.LiminalCells cells, AABB area) {
		List<VoxelShape> shapes = new ArrayList<>();
		for (int x = Mth.floor(area.minX); x <= Mth.floor(area.maxX); x++) {
			for (int y = Mth.floor(area.minY); y <= Mth.floor(area.maxY); y++) {
				for (int z = Mth.floor(area.minZ); z <= Mth.floor(area.maxZ); z++) {
					VoxelShape shape = cells.geometry(net.minecraft.core.BlockPos.asLong(x, y, z)).collision();
					if (!shape.isEmpty()) {
						VoxelShape moved = shape.move(x, y, z);
						// Same filter as the game's block-collision iterator (exact triangle overlap).
						if (((dev.echoliminal.liminal.MeshTriangleShape) moved).intersects(area)) {
							shapes.add(moved);
						}
					}
				}
			}
		}
		return shapes;
	}
}
