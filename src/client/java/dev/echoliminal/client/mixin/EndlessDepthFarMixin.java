package dev.echoliminal.client.mixin;

import dev.echoliminal.endless.client.EndlessZones;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Endless dimension: the far plane reaches the outermost level of detail ring (vanilla stops at four times the render distance). */
@Mixin(GameRenderer.class)
public abstract class EndlessDepthFarMixin {
	@Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
	private void echoliminal$endlessFar(CallbackInfoReturnable<Float> cir) {
		if (EndlessZones.active()) {
			cir.setReturnValue(Math.max(cir.getReturnValueF(), EndlessZones.FAR_PLANE));
		}
	}
}
