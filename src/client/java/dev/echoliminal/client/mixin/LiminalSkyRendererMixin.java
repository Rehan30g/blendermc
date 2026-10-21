package dev.echoliminal.client.mixin;

import dev.echoliminal.liminal.client.LiminalSky;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nothing of Minecraft's sky in the liminal dimension: the sky pass becomes a clear to the level's sky colour plus
 * its skybox; the sky disc, sunrise band, sun, moon, stars, dark disc and end sky are skipped.
 */
@Mixin(SkyRenderer.class)
public abstract class LiminalSkyRendererMixin {
	@Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
	private void echoliminal$liminalSky(int color, CallbackInfo ci) {
		if (LiminalSky.active()) {
			LiminalSky.drawSkyPass();
			ci.cancel();
		}
	}

	@Inject(method = {"renderSunriseAndSunset", "renderSunMoonAndStars", "renderDarkDisc", "renderEndSky", "renderEndFlash"}, at = @At("HEAD"),
		cancellable = true, require = 1)
	private void echoliminal$liminalNoVanillaSky(CallbackInfo ci) {
		if (LiminalSky.active()) {
			ci.cancel();
		}
	}
}
