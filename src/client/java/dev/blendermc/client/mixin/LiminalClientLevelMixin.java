package dev.blendermc.client.mixin;

import dev.blendermc.liminal.client.LiminalLightField;
import dev.blendermc.liminal.client.LiminalRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Liminal dimension: block changes reach the level renderer (a cell's geometry follows the block in it), and block
 * face shading uses the level mesh's shade with the sun factor instead of the fixed per-face values.
 */
@Mixin(ClientLevel.class)
public abstract class LiminalClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void blendermc$liminalCellChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		LiminalRenderer.blockChanged((ClientLevel) (Object) this, pos);
	}

	@Inject(method = "getShade", at = @At("HEAD"), cancellable = true)
	private void blendermc$liminalShade(Direction direction, boolean shaded, CallbackInfoReturnable<Float> cir) {
		float shade = LiminalLightField.shade(direction, shaded);
		if (!Float.isNaN(shade)) {
			cir.setReturnValue(shade);
		}
	}
}
