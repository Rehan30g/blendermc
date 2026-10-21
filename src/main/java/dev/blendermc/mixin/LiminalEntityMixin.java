package dev.blendermc.mixin;

import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalPhysics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Near mesh geometry (and anywhere in the liminal dimension), movement uses the slope-aware step-up of {@link LiminalPhysics#collide}. */
@Mixin(Entity.class)
public abstract class LiminalEntityMixin {
	@Shadow
	public abstract Level level();

	/** An entity that sank behind a surface (primed TNT placed into a floor cell) is lifted out before it moves. */
	@Inject(method = "move", at = @At("HEAD"))
	private void blendermc$depenetrate(net.minecraft.world.entity.MoverType type, Vec3 move, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		Level level = self.level();
		if (level.isClientSide() || self instanceof net.minecraft.world.entity.player.Player || self.isSpectator() || self.noPhysics) {
			return;
		}
		if (!LiminalLevels.hasMeshWorld(level) && !LiminalPhysics.applies(self, new Vec3(0.0, 0.01, 0.0))) {
			return;
		}
		net.minecraft.world.phys.AABB box = self.getBoundingBox();
		Vec3 push = LiminalPhysics.depenetrate(box, Entity.collectAllColliders(self, level, box.inflate(0.0, 1.0, 0.0)));
		if (push != null) {
			self.setPos(self.getX() + push.x, self.getY() + push.y, self.getZ() + push.z);
		}
	}

	@Inject(method = "collide", at = @At("HEAD"), cancellable = true)
	private void blendermc$liminalCollide(Vec3 move, CallbackInfoReturnable<Vec3> cir) {
		if (LiminalPhysics.applies((Entity) (Object) this, move)) {
			cir.setReturnValue(LiminalPhysics.collide((Entity) (Object) this, move));
		}
	}
}
