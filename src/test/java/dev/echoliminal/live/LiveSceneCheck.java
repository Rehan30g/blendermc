package dev.echoliminal.live;

import dev.echoliminal.liminal.GlbLoader;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.LiminalMesh;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Dev tool: builds a level from per-object .glb files the way the Blender live link does (obj_*.glb + scene.glb in a
 * folder) and compares it with a reference .glb of the same scene. Args: folder, reference.glb.
 */
public final class LiveSceneCheck {
	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		LiveScene scene = LiveScene.open("check", LiminalLevels.ORIGIN);
		long start = System.nanoTime();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.sorted().toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith("obj_")) {
					scene.putObject(name, Files.readAllBytes(file));
				} else if (name.equals("scene.glb")) {
					scene.putScene(Files.readAllBytes(file));
				}
			}
		}
		LiveScene.Built built = scene.build();
		long ms = (System.nanoTime() - start) / 1_000_000;
		print("live", built.mesh(), built.sky());
		System.out.println("  settings " + built.settings() + ", built in " + ms + " ms");
		GlbLoader.Level reference = GlbLoader.loadLevel("reference", Files.readAllBytes(Path.of(args[1])));
		print("reference", reference.mesh(), reference.sky());
		System.out.println("  settings " + reference.settings());
	}

	private static void print(String label, LiminalMesh mesh, LiminalMesh sky) {
		System.out.printf("%s: %d triangles, %d vertices, %d materials %s, spawn %s yaw %.1f, bounds %s, sky %s%n", label, mesh.triangleCount(),
			mesh.positions().length / 3, mesh.materials().size(), mesh.materials().stream().map(m -> m.name() + (m.texturePng() != null ? "+tex" : "")
				+ (m.emissive() ? "*" : "")).toList(), mesh.spawn(), mesh.spawnYaw(), mesh.bounds(), sky == null ? "none" : sky.triangleCount());
	}
}
