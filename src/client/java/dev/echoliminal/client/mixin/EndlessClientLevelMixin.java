package dev.echoliminal.client.mixin;

import dev.echoliminal.endless.client.EndlessClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Endless world: block changes reach the streamer, so a cell's geometry follows the block in it. */
@Mixin(ClientLevel.class)
public abstract class EndlessClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void echoliminal$endlessCellChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		EndlessClient.blockChanged((ClientLevel) (Object) this, pos);
	}
}
