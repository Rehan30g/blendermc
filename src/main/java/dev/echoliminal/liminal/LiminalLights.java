package dev.echoliminal.liminal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The mesh's lamps only glow in the renderer; real light comes from invisible light blocks put in the air just in
 * front of every emissive face, so the player, mobs and placed blocks are lit like the walls around them.
 */
public final class LiminalLights {
	private static final int LEVEL = 15;

	private LiminalLights() {
	}

	/** Clears the light blocks of whatever level was there and lights {@code mesh}. Returns how many were placed. */
	public static int apply(ServerLevel level, LiminalMesh mesh, AABB previous) {
		return apply(level, mesh, previous, null);
	}

	/** {@link #apply(ServerLevel, LiminalMesh, AABB)} touching only {@code dirty} (null = everything), for live edits. */
	public static int apply(ServerLevel level, LiminalMesh mesh, AABB previous, @org.jspecify.annotations.Nullable AABB dirty) {
		AABB only = dirty == null ? null : dirty.inflate(1.0);
		if (only != null) {
			clear(level, only);
		} else {
			if (previous != null) {
				clear(level, previous);
			}
			clear(level, mesh.bounds());
		}
		float[] p = mesh.positions();
		int[] idx = mesh.indices();
		// Cell -> light level: in front of emissive faces 15, at a live level's lamps (Blender lights) their own level.
		java.util.Map<BlockPos, Integer> cells = new java.util.HashMap<>();
		for (int t = 0; t < mesh.triangleCount(); t++) {
			if (!mesh.materials().get(mesh.triangleMaterial()[t]).emissive()) {
				continue;
			}
			int a = idx[3 * t] * 3;
			int b = idx[3 * t + 1] * 3;
			int c = idx[3 * t + 2] * 3;
			double cx = (p[a] + p[b] + p[c]) / 3.0;
			double cy = (p[a + 1] + p[b + 1] + p[c + 1]) / 3.0;
			double cz = (p[a + 2] + p[b + 2] + p[c + 2]) / 3.0;
			// Face normal from the winding, flipped to the side the stored vertex normals point to.
			double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
			double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			float[] n = mesh.normals();
			if (nx * n[a] + ny * n[a + 1] + nz * n[a + 2] < 0) {
				nx = -nx;
				ny = -ny;
				nz = -nz;
			}
			double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0E-9) {
				continue;
			}
			cells.put(BlockPos.containing(cx + nx / length * 0.5, cy + ny / length * 0.5, cz + nz / length * 0.5), LEVEL);
		}
		int placed = 0;
		// Lamps of a live level become real light blocks too, so they light the world and the mesh bake.
		AABB box = mesh.bounds();
		for (GlbLoader.LevelLight light : dev.echoliminal.live.LiveScene.lights(mesh.name())) {
			BlockPos cell = BlockPos.containing(light.position());
			if (cell.getX() >= Mth.floor(box.minX) && cell.getX() <= Mth.floor(box.maxX) && cell.getY() >= Mth.floor(box.minY)
				&& cell.getY() <= Mth.floor(box.maxY) && cell.getZ() >= Mth.floor(box.minZ) && cell.getZ() <= Mth.floor(box.maxZ)) {
				cells.merge(cell, light.level(), Math::max);
			}
		}
		for (java.util.Map.Entry<BlockPos, Integer> entry : cells.entrySet()) {
			BlockPos cell = entry.getKey();
			int light = entry.getValue();
			if (only != null && !only.contains(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5)) {
				continue;
			}
			BlockState state = level.getBlockState(cell);
			if (state.isAir() || state.is(Blocks.LIGHT)) {
				level.setBlock(cell, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, light), 2);
				placed++;
			} else if (state.is(LiminalBlocks.MESH_CELL)) {
				// The cell in front is geometry itself: let that mesh_cell give the light.
				level.setBlock(cell, state.setValue(MeshCellBlock.LIGHT, light), 2);
				placed++;
			}
		}
		return placed;
	}

	private static void clear(ServerLevel level, AABB box) {
		BlockPos.betweenClosedStream(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
				Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))
			.forEach(pos -> {
				BlockState state = level.getBlockState(pos);
				if (state.is(Blocks.LIGHT)) {
					level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 2);
				} else if (state.is(LiminalBlocks.MESH_CELL) && state.getValue(MeshCellBlock.LIGHT) > 0) {
					level.setBlock(pos.immutable(), state.setValue(MeshCellBlock.LIGHT, 0), 2);
				}
			});
	}
}
