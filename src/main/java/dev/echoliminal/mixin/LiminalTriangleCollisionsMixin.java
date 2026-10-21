package dev.echoliminal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.echoliminal.liminal.MeshTriangleShape;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The block-collision iterator keeps a block's shape only if it overlaps the queried box. A mesh_cell's triangle shape
 * looks like a whole cell to vanilla's voxel join, so test its triangles exactly instead: otherwise standing next to
 * (or on a slope inside) a cell would count as colliding with it (noCollision, the server's move check).
 */
@Mixin(BlockCollisions.class)
public abstract class LiminalTriangleCollisionsMixin {
	@Shadow
	@Final
	private AABB box;

	@WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
	private boolean echoliminal$exactTriangles(VoxelShape shape, VoxelShape entityShape, BooleanOp op, Operation<Boolean> original) {
		if (shape instanceof MeshTriangleShape triangles) {
			return triangles.intersects(this.box);
		}
		return original.call(shape, entityShape, op);
	}
}
