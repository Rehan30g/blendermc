package dev.blendermc.liminal;

import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import it.unimi.dsi.fastutil.floats.FloatSet;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Movement on liminal geometry: step-up that climbs the exact triangle slopes of mesh_cell blocks smoothly. */
public final class LiminalPhysics {
	/** Hard cap on the step-up candidates tried per move. */
	private static final int MAX_HEIGHTS = 32;

	private LiminalPhysics() {
	}

	/**
	 * Whether the slope-aware movement should replace vanilla's for {@code entity}: always in the liminal dimension,
	 * elsewhere only for entities that step (players, mobs) next to a mesh_cell (placed pieces).
	 */
	public static boolean applies(Entity entity, Vec3 move) {
		Level level = entity.level();
		if (LiminalLevels.hasMeshWorld(level)) {
			return true;
		}
		if (entity.maxUpStep() <= 0.0F || move.lengthSqr() == 0.0) {
			return false;
		}
		AABB area = entity.getBoundingBox().expandTowards(move).inflate(0.0, entity.maxUpStep(), 0.0);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(area.minX); x <= Mth.floor(area.maxX); x++) {
			for (int y = Mth.floor(area.minY) - 1; y <= Mth.floor(area.maxY); y++) {
				for (int z = Mth.floor(area.minZ); z <= Mth.floor(area.maxZ); z++) {
					if (level.getBlockState(pos.set(x, y, z)).is(LiminalBlocks.MESH_CELL)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Replacement for {@code Entity.collide} in the liminal dimension. Same as vanilla, except step-up picks the
	 * candidate height that gets furthest (lowest of equals) instead of the first that gets anywhere, then settles back
	 * down onto the surface; and a grounded entity walking down a slope stays on it. On the 1/16 voxel stairs of a
	 * mesh slope, vanilla's rule would crawl one voxel per tick and hop.
	 */
	public static Vec3 collide(Entity entity, Vec3 move) {
		Level level = entity.level();
		return collide(entity.getBoundingBox(), move, entity.maxUpStep(), entity.onGround(), area -> Entity.collectAllColliders(entity, level, area));
	}

	/** {@link #collide(Entity, Vec3)} without an entity: {@code colliders} gives every shape touching an area. */
	public static Vec3 collide(AABB box, Vec3 move, float step, boolean onGround, Function<AABB, List<VoxelShape>> colliders) {
		boolean escaping = false;
		if (move.lengthSqr() != 0.0) {
			for (VoxelShape shape : colliders.apply(box.expandTowards(move))) {
				if (shape instanceof MeshTriangleShape triangles && triangles.overlapsInterior(box)) {
					escaping = true;
					break;
				}
			}
		}
		boolean before = MeshTriangleShape.setEscaping(escaping);
		try {
			return collideInner(box, move, step, onGround, colliders);
		} finally {
			MeshTriangleShape.setEscaping(before);
		}
	}

	/**
	 * The server's "did this move newly collide with something" test, per shape: a mesh_cell the new box does not
	 * actually cut a triangle of is no new contact (its cell-wide overlap, slab and interior tests are too generous).
	 */
	public static boolean harmlessContact(VoxelShape shape, AABB newBox) {
		return shape instanceof MeshTriangleShape triangles && !triangles.touchesSurface(newBox);
	}

	/**
	 * Offset that lifts a box out of a surface it sank behind (see {@link MeshTriangleShape#pushOut}), or null. Players are
	 * left alone by the caller.
	 */
	public static @org.jspecify.annotations.Nullable Vec3 depenetrate(AABB box, List<VoxelShape> shapes) {
		Vec3 best = null;
		for (VoxelShape shape : shapes) {
			if (shape instanceof MeshTriangleShape triangles) {
				Vec3 push = triangles.pushOut(box);
				if (push != null && (best == null || push.lengthSqr() > best.lengthSqr())) {
					best = push;
				}
			}
		}
		return best;
	}

	private static Vec3 collideInner(AABB box, Vec3 move, float step, boolean onGround, Function<AABB, List<VoxelShape>> colliders) {
		Vec3 base = move.lengthSqr() == 0.0 ? move : collideWithShapes(move, box, colliders.apply(box.expandTowards(move)));
		boolean blockedX = move.x != base.x;
		boolean blockedY = move.y != base.y;
		boolean blockedZ = move.z != base.z;
		boolean landed = blockedY && move.y < 0.0;
		if (step > 0.0F && (landed || onGround) && (blockedX || blockedZ)) {
			AABB start = landed ? box.move(0.0, base.y, 0.0) : box;
			AABB reach = start.expandTowards(move.x, step, move.z);
			if (!landed) {
				reach = reach.expandTowards(0.0, -1.0E-5F, 0.0);
			}
			List<VoxelShape> shapes = colliders.apply(reach);
			float current = landed ? 0.0F : (float) base.y;
			Vec3 best = null;
			double bestDistance = base.horizontalDistanceSqr() + 1.0E-7;
			for (float height : stepHeights(start, shapes, step, current, true)) {
				Vec3 attempt = collideWithShapes(new Vec3(move.x, height, move.z), start, shapes);
				if (attempt.horizontalDistanceSqr() > bestDistance + 1.0E-9) {
					best = attempt;
					bestDistance = attempt.horizontalDistanceSqr();
				}
			}
			if (best != null) {
				if (move.y <= 0.0 && best.y > 0.0) {
					double down = Shapes.collide(Direction.Axis.Y, start.move(best), shapes, -best.y);
					best = best.add(0.0, down, 0.0);
				}
				return best.subtract(0.0, box.minY - start.minY, 0.0);
			}
		}
		if (step > 0.0F && onGround && move.y < 0.0 && !blockedY && base.horizontalDistanceSqr() > 1.0E-8) {
			double reachDown = Math.min(step, Math.sqrt(base.horizontalDistanceSqr()) * 1.2);
			AABB after = box.move(base);
			List<VoxelShape> shapes = colliders.apply(after.expandTowards(0.0, -reachDown, 0.0));
			double down = Shapes.collide(Direction.Axis.Y, after, shapes, -reachDown);
			if (down > -reachDown + 1.0E-7) {
				return base.add(0.0, down, 0.0);
			}
		}
		return base;
	}

	private static float[] stepHeights(AABB box, List<VoxelShape> shapes, float max, float current, boolean slopes) {
		FloatSet heights = new FloatArraySet(8);
		for (VoxelShape shape : shapes) {
			if (slopes && shape instanceof MeshTriangleShape) {
				// Triangle cells only report their cell bounds; try even steps up to the full step height too.
				for (int k = 1; k <= 6; k++) {
					heights.add(max * k / 6.0F);
				}
				slopes = false;
			}
			for (double y : shape.getCoords(Direction.Axis.Y)) {
				float height = (float) (y - box.minY);
				if (height < 0.0F || height == current) {
					continue;
				}
				if (height > max) {
					break;
				}
				heights.add(height);
			}
		}
		float[] sorted = heights.toFloatArray();
		FloatArrays.unstableSort(sorted);
		return sorted.length > MAX_HEIGHTS ? java.util.Arrays.copyOf(sorted, MAX_HEIGHTS) : sorted;
	}

	private static Vec3 collideWithShapes(Vec3 move, AABB box, List<VoxelShape> shapes) {
		if (shapes.isEmpty()) {
			return move;
		}
		Vec3 done = Vec3.ZERO;
		for (Direction.Axis axis : Direction.axisStepOrder(move)) {
			double amount = move.get(axis);
			if (amount != 0.0) {
				done = done.with(axis, Shapes.collide(axis, box.move(done), shapes, amount));
			}
		}
		return done;
	}
}
