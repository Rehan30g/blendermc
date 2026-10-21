package dev.blendermc.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.blendermc.endless.client.EndlessZones;
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
 * Endless dimension: the blended zone fog replaces vanilla fog (no render distance fog) for terrain, blocks, entities and
 * the streamed world alike, and the frame is cleared to the same colour, so the far rings melt into the sky.
 */
@Mixin(FogRenderer.class)
public abstract class EndlessFogRendererMixin {
	@Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;"))
	private void blendermc$endlessFog(CallbackInfoReturnable<Vector4f> cir, @Local(argsOnly = true) ClientLevel level, @Local FogData fogData,
			@Local Vector4f color) {
		float[] fog = EndlessZones.fog(level);
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

	@Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
	private void blendermc$endlessClear(CallbackInfoReturnable<Vector4f> cir, @Local(argsOnly = true) ClientLevel level) {
		if (EndlessZones.fog(level) != null) {
			int sky = EndlessZones.skyColorArgb();
			cir.setReturnValue(new Vector4f(ARGB.redFloat(sky), ARGB.greenFloat(sky), ARGB.blueFloat(sky), 1.0F));
		}
	}
}
