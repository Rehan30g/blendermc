package dev.blendermc.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.blendermc.liminal.client.LiminalSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.ARGB;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Liminal dimension: the level's own fog replaces vanilla's (no render-distance fog, no sky blending, no water or
 * rain changes) for everything that uses the world fog — level mesh, blocks, entities. The frame is cleared to the
 * level's sky colour.
 *
 * <p>The values are patched into vanilla's own fog data right before it is written to the fog buffer, instead of
 * replacing setupFog: Sodium captures that same data (its chunk shader does not read the buffer) in an injection at the
 * buffer write, which a replaced setupFog skipped, leaving vanilla terrain unfogged.
 */
@Mixin(FogRenderer.class)
public abstract class LiminalFogRendererMixin {
	@Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;"))
	private void blendermc$liminalFog(CallbackInfoReturnable<Vector4f> cir, @Local(argsOnly = true) ClientLevel level, @Local FogData fogData,
			@Local Vector4f color) {
		float[] fog = LiminalSky.fog(level);
		if (fog == null) {
			return;
		}
		color.set(fog[2], fog[3], fog[4], 1.0F);
		fogData.environmentalStart = fog[0];
		fogData.environmentalEnd = fog[1];
		fogData.renderDistanceStart = Float.MAX_VALUE;
		fogData.renderDistanceEnd = Float.MAX_VALUE;
		fogData.skyEnd = Float.MAX_VALUE;
		fogData.cloudEnd = Float.MAX_VALUE;
	}

	/** The frame clear colour is the sky, not the fog. */
	@Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
	private void blendermc$liminalClear(CallbackInfoReturnable<Vector4f> cir, @Local(argsOnly = true) ClientLevel level) {
		if (LiminalSky.fog(level) != null) {
			int sky = LiminalSky.skyColorArgb();
			cir.setReturnValue(new Vector4f(ARGB.redFloat(sky), ARGB.greenFloat(sky), ARGB.blueFloat(sky), 1.0F));
		}
	}
}
