package dev.echoliminal.client.mixin;

import dev.echoliminal.liminal.client.LiminalLightField;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities (and what they hold) in the liminal dimension are lit by the level's light field. */
@Mixin(EntityRenderer.class)
public abstract class LiminalEntityLightMixin {
	@Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
	private void echoliminal$liminalLight(Entity entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
		if (LiminalLightField.isActive()) {
			cir.setReturnValue(LiminalLightField.packed(BlockPos.containing(entity.getLightProbePosition(partialTick)), cir.getReturnValueI()));
		}
	}
}
