package dev.blendermc.mixin;

import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block change in the liminal dimension, so a level cell comes back the same tick its replacing block goes. */
@Mixin(LevelChunk.class)
public abstract class LiminalChunkMixin {
	@Shadow
	@Final
	Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void blendermc$liminalBlockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		if (cir.getReturnValue() != null && this.level instanceof ServerLevel server && server.dimension() == LiminalLevels.DIMENSION) {
			LiminalWorld.blockChanged(server, pos, state);
			if (server.dimension() == LiminalLevels.DIMENSION) {
				dev.blendermc.live.LiveLink.blockChanged(server, pos, cir.getReturnValue(), state);
			}
		}
	}
}
