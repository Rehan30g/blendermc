package dev.blendermc.liminal;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Everything a mesh_cell needs from its triangles (cell-local, 9 floats each), computed once per cell or piece:
 * the exact outline and collision shapes, which faces can hold torches and the like, which axes block light, and
 * whether the cell is sparse ground a placed block may sink into.
 */
public final class CellGeometry {
	public static final CellGeometry EMPTY = new CellGeometry(new float[0], new float[0]);
	/** Surfaces whose normal points this much along a face direction support blocks on that face. */
	private static final double SUPPORT_DOT = 0.3;
	private static final VoxelShape[] SUPPORT_SHAPES = new VoxelShape[64];

	static {
		for (int mask = 0; mask < 64; mask++) {
			VoxelShape shape = Shapes.empty();
			for (Direction d : Direction.values()) {
				if ((mask & (1 << d.ordinal())) != 0) {
					shape = Shapes.or(shape, faceSlab(d));
				}
			}
			SUPPORT_SHAPES[mask] = shape.optimize();
		}
	}

	private final float[] all;
	private final float[] solid;
	private final float[] volume;
	private final VoxelShape outline;
	private final VoxelShape collision;
	private final int supportMask;
	private final boolean occludeX, occludeY, occludeZ;
	private final boolean sparseGround;
	private final CellInterior.@org.jspecify.annotations.Nullable Fallback fallback;
	private volatile @org.jspecify.annotations.Nullable VoxelShape rayVolume;

	public CellGeometry(float[] all, float[] solid) {
		this(all, solid, null);
	}

	/**
	 * {@code fallback} says whether a cell-local point is solid when the cell's own triangles do not tell (level cells
	 * ask the level's volume; null = empty).
	 */
	public CellGeometry(float[] all, float[] solid, CellInterior.@org.jspecify.annotations.Nullable Fallback fallback) {
		this(all, solid, solid, fallback);
	}

	/** {@code volume}: the triangles that give a solid region behind them (not thin, see {@link LiminalMesh.Material#thin()}). */
	public CellGeometry(float[] all, float[] solid, float[] volume, CellInterior.@org.jspecify.annotations.Nullable Fallback fallback) {
		this(all, solid, volume, fallback, false);
	}

	/** {@code stoneFill}: the level fills its volume with stone, so the space behind a surface inside it is solid and holds things up in full depth; elsewhere only a thin slab (2/16) does. */
	public CellGeometry(float[] all, float[] solid, float[] volume, CellInterior.@org.jspecify.annotations.Nullable Fallback fallback, boolean stoneFill) {
		this.all = all;
		this.volume = volume;
		this.solid = solid;
		net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape inside = CellInterior.build(volume, fallback);
		this.fallback = fallback;
		this.outline = all.length == 0 ? Shapes.empty() : new MeshTriangleShape(all, Shapes.empty(), this::rayVolume);
		net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape holding = CellInterior.buildThin(volume, stoneFill ? fallback : null);
		this.collision = solid.length == 0 ? Shapes.empty() : new MeshTriangleShape(solid, holding == null ? Shapes.empty() : MeshCellShape.of(holding));
		if (this.collision instanceof MeshTriangleShape mesh) {
			mesh.pushable = volume;
		}
		VoxelShape voxels = MeshCollision.voxelizeLocal(volume);
		MeshCellShape cube = voxels instanceof MeshCellShape c ? c.or(inside) : inside != null ? MeshCellShape.of(inside) : null;
		this.occludeX = cube != null && cube.covers(Direction.Axis.X);
		this.occludeY = cube != null && cube.covers(Direction.Axis.Y);
		this.occludeZ = cube != null && cube.covers(Direction.Axis.Z);

		int mask = 0;
		double area = 0, normalY = 0, height = 0;
		for (int t = 0; t < solid.length; t += 9) {
			double ux = solid[t + 3] - solid[t], uy = solid[t + 4] - solid[t + 1], uz = solid[t + 5] - solid[t + 2];
			double vx = solid[t + 6] - solid[t], vy = solid[t + 7] - solid[t + 1], vz = solid[t + 8] - solid[t + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1.0E-8) {
				continue;
			}
			double a = len / 2;
			nx /= len;
			ny /= len;
			nz /= len;
			for (Direction d : Direction.values()) {
				if (nx * d.getStepX() + ny * d.getStepY() + nz * d.getStepZ() > SUPPORT_DOT) {
					mask |= 1 << d.ordinal();
				}
			}
			area += a;
			normalY += ny * a;
			height += (solid[t + 1] + solid[t + 4] + solid[t + 7]) / 3.0 * a;
		}
		this.supportMask = mask;
		// Ground-like (faces mostly up) with the surface low in the cell: a placed block may take the cell over.
		this.sparseGround = area > 1.0E-6 && normalY / area > 0.4 && height / area < 0.5;
	}

	/** Whether a cell-local point is inside the solid (public face of the package-private interior fallback). */
	@FunctionalInterface
	public interface PointTest {
		boolean solid(double x, double y, double z);
	}

	/** Geometry for a cell whose triangles (cell-local, 9 floats each) come from outside the loader; see the 5-argument constructor. */
	public static CellGeometry of(float[] all, float[] solid, float[] volume, PointTest inside, boolean stoneFill) {
		return new CellGeometry(all, solid, volume, inside::solid, stoneFill);
	}

	/**
	 * What rays hit besides the exact triangles: the solid behind the surface right up to it, plus every surface
	 * extruded 2/16 behind its front face (thin walls and single planes get a hittable side). Built on first use.
	 */
	public VoxelShape rayVolume() {
		VoxelShape v = this.rayVolume;
		if (v == null) {
			net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape inside = CellInterior.build(this.volume, this.fallback, 0.0);
			VoxelShape extruded = Shapes.empty();
			float[] moved = new float[this.solid.length];
			for (int step = 0; step <= 2; step++) {
				double depth = step / 16.0;
				for (int t = 0; t < this.solid.length; t += 9) {
					double ux = this.solid[t + 3] - this.solid[t], uy = this.solid[t + 4] - this.solid[t + 1], uz = this.solid[t + 5] - this.solid[t + 2];
					double vx = this.solid[t + 6] - this.solid[t], vy = this.solid[t + 7] - this.solid[t + 1], vz = this.solid[t + 8] - this.solid[t + 2];
					double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
					double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len < 1.0E-12) {
						len = 1;
					}
					for (int k = 0; k < 9; k += 3) {
						moved[t + k] = (float) Math.max(0, Math.min(1, this.solid[t + k] - nx / len * depth));
						moved[t + k + 1] = (float) Math.max(0, Math.min(1, this.solid[t + k + 1] - ny / len * depth));
						moved[t + k + 2] = (float) Math.max(0, Math.min(1, this.solid[t + k + 2] - nz / len * depth));
					}
				}
				VoxelShape layer = MeshCollision.voxelizeLocal(moved);
				extruded = extruded.isEmpty() ? layer : layer.isEmpty() ? extruded : Shapes.or(extruded, layer);
			}
			v = inside == null ? extruded : extruded.isEmpty() ? MeshCellShape.of(inside) : Shapes.or(extruded, MeshCellShape.of(inside));
			v = v.isEmpty() ? v : v.optimize();
			this.rayVolume = v;
		}
		return v;
	}

	private volatile int mostlyEmpty = -1;

	/** Less than half of the cell is solid (its ray volume): a placed block may take the cell over. */
	public boolean mostlyEmpty() {
		int m = this.mostlyEmpty;
		if (m < 0) {
			double volume = 0;
			for (net.minecraft.world.phys.AABB box : this.rayVolume().toAabbs()) {
				volume += box.getXsize() * box.getYsize() * box.getZsize();
			}
			m = volume < 0.5 ? 1 : 0;
			this.mostlyEmpty = m;
		}
		return m == 1;
	}

	private static VoxelShape faceSlab(Direction d) {
		double t = 1.0 / 16.0;
		return switch (d) {
			case DOWN -> Shapes.box(0, 0, 0, 1, t, 1);
			case UP -> Shapes.box(0, 1 - t, 0, 1, 1, 1);
			case NORTH -> Shapes.box(0, 0, 0, 1, 1, t);
			case SOUTH -> Shapes.box(0, 0, 1 - t, 1, 1, 1);
			case WEST -> Shapes.box(0, 0, 0, t, 1, 1);
			case EAST -> Shapes.box(1 - t, 0, 0, 1, 1, 1);
		};
	}

	/** All triangles (outline, rays, rendering), cell-local, 9 floats each. */
	public float[] triangles() {
		return this.all;
	}

	/** Colliding triangles only (no "_nocollide" decals). */
	public float[] solidTriangles() {
		return this.solid;
	}

	/** Exact shape of every triangle (outline and ray hits). */
	public VoxelShape outline() {
		return this.outline;
	}

	/** Exact shape of the colliding triangles. */
	public VoxelShape collision() {
		return this.collision;
	}

	/** Full face slabs on the faces that have a surface facing out of them (torches, carpets, fire...). */
	public VoxelShape supportShape() {
		return SUPPORT_SHAPES[this.supportMask];
	}

	public boolean occludesX() {
		return this.occludeX;
	}

	public boolean occludesY() {
		return this.occludeY;
	}

	public boolean occludesZ() {
		return this.occludeZ;
	}

	/** A floor-like surface low in the cell: block placement may replace the cell. */
	public boolean sparseGround() {
		return this.sparseGround;
	}
}
