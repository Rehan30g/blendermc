package dev.echoliminal.endless;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.echoliminal.endless.gen.CellTris;
import dev.echoliminal.endless.gen.EndlessGenerator;
import dev.echoliminal.endless.gen.Mats;
import dev.echoliminal.liminal.CellGeometry;
import dev.echoliminal.liminal.CellSource;
import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.MeshPiece;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The endless world as a {@link CellSource}: geometry of any cell on demand from the generator (cached). */
public final class EndlessCells implements CellSource {
	private static volatile @Nullable EndlessCells instance;
	private static final List<LiminalMesh.Material> MATERIALS = new ArrayList<>();

	static {
		for (Mats.Mat m : Mats.ALL) {
			MATERIALS.add(new LiminalMesh.Material(m.name(), m.baseColor(), m.png(), m.emissive(), m.collides()));
		}
	}

	private final EndlessGenerator generator;
	private final Cache<Long, CellGeometry> geometry = CacheBuilder.newBuilder().maximumSize(60_000).build();
	private final Cache<Long, byte[]> classes = CacheBuilder.newBuilder().maximumSize(24).build();

	private EndlessCells(EndlessGenerator generator) {
		this.generator = generator;
	}

	/** The source for the current seed; null until the seed is known. */
	public static @Nullable EndlessCells get() {
		EndlessGenerator g = Endless.generator();
		EndlessCells current = instance;
		if (g == null) {
			return null;
		}
		if (current == null || current.generator != g) {
			synchronized (EndlessCells.class) {
				current = instance;
				if (current == null || current.generator != g) {
					current = new EndlessCells(g);
					instance = current;
				}
			}
		}
		return current;
	}

	static void reset() {
		instance = null;
	}

	/** Class bytes of region (rx, rz) at LOD 0 (see {@link dev.echoliminal.endless.gen.Region#cells}); cached. */
	public byte[] classes(int rx, int rz) {
		long key = (long) rx << 32 ^ (rz & 0xFFFFFFFFL);
		byte[] cells = this.classes.getIfPresent(key);
		if (cells == null) {
			cells = this.generator.generate(rx, rz, 0).cells;
			this.classes.put(key, cells);
		}
		return cells;
	}

	public EndlessGenerator generator() {
		return this.generator;
	}

	@Override
	public boolean hasCell(long cell) {
		return (this.generator.classify(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell)) & 3) == EndlessGenerator.SURFACE;
	}

	@Override
	public CellGeometry geometry(long cell) {
		CellGeometry cached = this.geometry.getIfPresent(cell);
		if (cached != null) {
			return cached;
		}
		int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
		CellTris tris = this.generator.cellTriangles(x, y, z);
		CellGeometry result;
		if (tris.isEmpty()) {
			result = CellGeometry.EMPTY;
		} else {
			float[] all = new float[tris.pos().length], solid = new float[all.length], volume = new float[all.length];
			int s = 0, v = 0;
			for (int t = 0; t < tris.triangleCount(); t++) {
				Mats.Mat m = Mats.get(tris.mat()[t]);
				for (int k = 0; k < 9; k++) {
					all[t * 9 + k] = tris.pos()[t * 9 + k] - (k % 3 == 0 ? x : k % 3 == 1 ? y : z);
				}
				if (m.collides()) {
					System.arraycopy(all, t * 9, solid, s, 9);
					s += 9;
				}
				if (!m.thin()) {
					System.arraycopy(all, t * 9, volume, v, 9);
					v += 9;
				}
			}
			result = CellGeometry.of(all, Arrays.copyOf(solid, s), Arrays.copyOf(volume, v),
				(lx, ly, lz) -> this.generator.solidAt(x + lx, y + ly, z + lz), this.generator.fillAt(x, y, z));
		}
		this.geometry.put(cell, result);
		return result;
	}

	@Override
	public @Nullable MeshPiece piece(long cell, int facing) {
		int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
		CellTris tris = this.generator.cellTriangles(x, y, z);
		if (tris.isEmpty()) {
			return null;
		}
		int[] all = new int[tris.triangleCount()];
		for (int i = 0; i < all.length; i++) {
			all[i] = i;
		}
		return MeshPiece.fromCell(this.mesh(tris, x, y, z), x, y, z, all, facing);
	}

	/** A one-cell {@link LiminalMesh} of the cell (for code that works on meshes, e.g. cut faces). */
	public LiminalMesh mesh(long cell) {
		int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
		return this.mesh(this.generator.cellTriangles(x, y, z), x, y, z);
	}

	private LiminalMesh mesh(CellTris tris, int x, int y, int z) {
		int[] indices = new int[tris.triangleCount() * 3];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = i;
		}
		return new LiminalMesh(Endless.LEVEL, tris.pos(), tris.nrm(), tris.uv(), indices, tris.mat(), MATERIALS, Vec3.ZERO, 0.0F, new AABB(x, y, z, x + 1, y + 1, z + 1));
	}
}
