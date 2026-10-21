package dev.blendermc.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.blendermc.endless.client.EndlessZones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Endless dimension: no vanilla sky at all; the sky pass is a clear to the blended zone colour (overcast field, nothing indoors). */
@Mixin(SkyRenderer.class)
public abstract class EndlessSkyRendererMixin {
	@Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
	private void blendermc$endlessSky(int color, CallbackInfo ci) {
		if (EndlessZones.active()) {
			RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), EndlessZones.skyColorArgb());
			ci.cancel();
		}
	}

	@Inject(method = {"renderSunriseAndSunset", "renderSunMoonAndStars", "renderDarkDisc", "renderEndSky", "renderEndFlash"}, at = @At("HEAD"),
		cancellable = true, require = 1)
	private void blendermc$endlessNoVanillaSky(CallbackInfo ci) {
		if (EndlessZones.active()) {
			ci.cancel();
		}
	}
}
