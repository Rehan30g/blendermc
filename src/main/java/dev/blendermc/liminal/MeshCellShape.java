package dev.blendermc.liminal;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleLists;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A block cell's mesh collision as a 16³ voxel grid (vanilla's CubeVoxelShape, whose constructor is not public). */
final class MeshCellShape extends VoxelShape {
	private static final DoubleList COORDS;

	static {
		DoubleArrayList coords = new DoubleArrayList(MeshCollision.RES + 1);
		for (int i = 0; i <= MeshCollision.RES; i++) {
			coords.add((double) i / MeshCollision.RES);
		}
		COORDS = DoubleLists.unmodifiable(coords);
	}

	MeshCellShape(DiscreteVoxelShape voxels) {
		super(voxels);
	}

	@Override
	public DoubleList getCoords(Direction.Axis axis) {
		return COORDS;
	}

	/** Wraps a 16³ voxel grid. */
	static MeshCellShape of(DiscreteVoxelShape voxels) {
		return new MeshCellShape(voxels);
	}

	/** This shape's voxels or-ed with {@code other} (16³), as a new shape. */
	MeshCellShape or(@org.jspecify.annotations.Nullable DiscreteVoxelShape other) {
		if (other == null) {
			return this;
		}
		net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape out = new net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape(this.shape);
		int n = MeshCollision.RES;
		for (int x = 0; x < n; x++) {
			for (int y = 0; y < n; y++) {
				for (int z = 0; z < n; z++) {
					if (other.isFull(x, y, z)) {
						out.fill(x, y, z);
					}
				}
			}
		}
		return new MeshCellShape(out);
	}

	/** Whether every line through the cell along {@code axis} hits a filled voxel (the cell blocks light that way). */
	boolean covers(Direction.Axis axis) {
		int n = MeshCollision.RES;
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				boolean hit = false;
				for (int k = 0; k < n && !hit; k++) {
					hit = switch (axis) {
						case X -> this.shape.isFull(k, i, j);
						case Y -> this.shape.isFull(i, k, j);
						case Z -> this.shape.isFull(i, j, k);
					};
				}
				if (!hit) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	protected int findIndex(Direction.Axis axis, double d) {
		return Mth.floor(Mth.clamp(d * MeshCollision.RES, -1.0, MeshCollision.RES));
	}
}
