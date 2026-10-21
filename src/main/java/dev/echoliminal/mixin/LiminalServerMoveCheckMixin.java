package dev.echoliminal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.echoliminal.liminal.LiminalPhysics;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The server rejects a move (teleports the player back, camera rotation included, without a log line) when the new box
 * touches a collision shape the old box did not. A mesh_cell looks like a whole cell with a solid slab behind its face to
 * that test, so walking over the back side of a surface from cell to cell (or along a wall from outside) was undone every
 * tick: no movement, frozen view. Only a box that really cuts a triangle counts as new contact.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LiminalServerMoveCheckMixin {
	@WrapOperation(method = "isEntityCollidingWithAnythingNew", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
	private boolean echoliminal$exactNewContact(VoxelShape shape, VoxelShape oldBox, BooleanOp op, Operation<Boolean> original,
		@Local(argsOnly = true) Entity entity, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 1) double y,
		@Local(argsOnly = true, ordinal = 2) double z) {
		AABB newBox = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ()).deflate(1.0E-5F);
		if (LiminalPhysics.harmlessContact(shape, newBox)) {
			return true;
		}
		return original.call(shape, oldBox, op);
	}
}
