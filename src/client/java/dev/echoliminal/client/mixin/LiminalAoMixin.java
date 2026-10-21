package dev.echoliminal.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.echoliminal.liminal.client.LiminalLightField;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No ambient occlusion / smooth-light darkening in the liminal dimension: blocks are lit flat like the level mesh. */
@Mixin(ModelBlockRenderer.class)
public abstract class LiminalAoMixin {
	@ModifyExpressionValue(method = "tesselateBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;useAmbientOcclusion()Z"))
	private boolean echoliminal$liminalNoAo(boolean original) {
		return original && !LiminalLightField.isActive();
	}
}
