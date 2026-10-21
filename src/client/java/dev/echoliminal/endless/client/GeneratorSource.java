package dev.echoliminal.endless.client;

import dev.echoliminal.endless.gen.EndlessGenerator;
import dev.echoliminal.endless.gen.Mats;
import dev.echoliminal.endless.gen.Region;
import dev.echoliminal.liminal.LiminalMesh;
import java.util.ArrayList;
import java.util.List;

/** The real generator, shared code with the server: same seed, same world. */
final class GeneratorSource implements EndlessSource {
	private final EndlessGenerator generator;
	private final List<LiminalMesh.Material> materials = new ArrayList<>();

	GeneratorSource(long seed) {
		this.generator = new EndlessGenerator(seed);
		for (Mats.Mat m : Mats.ALL) {
			this.materials.add(new LiminalMesh.Material(m.name(), m.baseColor(), m.png(), m.emissive(), m.collides()));
		}
	}

	@Override
	public List<LiminalMesh.Material> materials() {
		return this.materials;
	}

	@Override
	public EndlessMesh region(int rx, int rz, int lod) {
		Region r = this.generator.generate(rx, rz, lod);
		return new EndlessMesh(r.positions, r.normals, r.uvs, r.indices, r.triMaterial);
	}

	@Override
	public int zone(int x, int z) {
		return this.generator.zoneAt(x, z).ordinal();
	}
}
