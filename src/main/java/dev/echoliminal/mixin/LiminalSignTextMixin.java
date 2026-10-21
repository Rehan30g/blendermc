package dev.echoliminal.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A sign's text changed (edited, dyed, glow ink): the Blender live link mirrors it. */
@Mixin(SignBlockEntity.class)
public abstract class LiminalSignTextMixin {
	@Inject(method = "setText", at = @At("RETURN"))
	private void echoliminal$signChanged(SignText text, boolean front, CallbackInfoReturnable<Boolean> cir) {
		SignBlockEntity self = (SignBlockEntity) (Object) this;
		if (cir.getReturnValueZ() && self.getLevel() instanceof ServerLevel world) {
			dev.echoliminal.live.LiveLink.signChanged(world, self.getBlockPos());
		}
	}
}
