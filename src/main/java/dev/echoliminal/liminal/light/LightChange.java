package dev.echoliminal.liminal.light;

import java.util.ArrayList;
import java.util.List;

/**
 * A change in the placed world that alters lighting around a point: a light source appearing, vanishing or changing
 * level (reaches {@code radius} blocks), or a block that can cast shadows (reaches the light radius, and along the
 * sun's direction as far as {@link #SUN_REACH}). Used to pick which vertices and cells need re-baking.
 */
public record LightChange(double x, double y, double z, float radius, boolean occluder) {
	/** How far a placed block's sun shadow is followed. */
	public static final double SUN_REACH = 64.0;
	/** Half width of the shadow corridor of a block (blocks, soft edges included). */
	private static final double SUN_WIDTH = 2.5;
	/** Reach of an occluder: the largest light range plus a block. */
	public static final float OCCLUDER_RADIUS = LightModel.RADIUS + 1.5F;

	public static LightChange light(long pos, int level) {
		return new LightChange(net.minecraft.core.BlockPos.getX(pos) + 0.5, net.minecraft.core.BlockPos.getY(pos) + 0.5,
			net.minecraft.core.BlockPos.getZ(pos) + 0.5, level + 1.5F, false);
	}

	public static LightChange block(long pos) {
		return new LightChange(net.minecraft.core.BlockPos.getX(pos) + 0.5, net.minecraft.core.BlockPos.getY(pos) + 0.5,
			net.minecraft.core.BlockPos.getZ(pos) + 0.5, OCCLUDER_RADIUS, true);
	}

	/** Whether a surface or air point at (px, py, pz) can have different light because of this change. */
	public boolean affects(LightModel model, double px, double py, double pz) {
		double dx = px - this.x, dy = py - this.y, dz = pz - this.z;
		if (dx * dx + dy * dy + dz * dz <= (double) this.radius * this.radius) {
			return true;
		}
		if (this.occluder && model.hasSun()) {
			// The shadow falls along -toSun from the block.
			double t = -(dx * model.sunX() + dy * model.sunY() + dz * model.sunZ());
			if (t >= -1.5 && t <= SUN_REACH) {
				return dx * dx + dy * dy + dz * dz - t * t <= SUN_WIDTH * SUN_WIDTH;
			}
		}
		return false;
	}

	/** Boxes (minX, minY, minZ, maxX, maxY, maxZ) that contain everything {@link #affects} can accept. */
	public List<double[]> boxes(LightModel model) {
		List<double[]> out = new ArrayList<>(2);
		double r = this.radius;
		out.add(new double[] {this.x - r, this.y - r, this.z - r, this.x + r, this.y + r, this.z + r});
		if (this.occluder && model.hasSun()) {
			double ex = this.x - model.sunX() * SUN_REACH, ey = this.y - model.sunY() * SUN_REACH, ez = this.z - model.sunZ() * SUN_REACH;
			double w = SUN_WIDTH + 1;
			out.add(new double[] {Math.min(this.x, ex) - w, Math.min(this.y, ey) - w, Math.min(this.z, ez) - w, Math.max(this.x, ex) + w,
				Math.max(this.y, ey) + w, Math.max(this.z, ez) + w});
		}
		return out;
	}
}
