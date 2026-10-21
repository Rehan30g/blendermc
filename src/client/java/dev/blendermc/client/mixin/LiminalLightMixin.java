package dev.blendermc.client.mixin;

import dev.blendermc.liminal.client.LiminalLightField;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In the liminal dimension, block faces, particles and block entities take their light from the level's light field. */
@Mixin(LevelRenderer.class)
public abstract class LiminalLightMixin {
	@Inject(method = "getLightColor(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
		at = @At("RETURN"), cancellable = true)
	private static void blendermc$liminalLight(LevelRenderer.BrightnessGetter getter, BlockAndTintGetter level, BlockState state, BlockPos pos,
			CallbackInfoReturnable<Integer> cir) {
		if (LiminalLightField.isActive()) {
			cir.setReturnValue(LiminalLightField.packed(pos, cir.getReturnValueI()));
		}
	}
}
