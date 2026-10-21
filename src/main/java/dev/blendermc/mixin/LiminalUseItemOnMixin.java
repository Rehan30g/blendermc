package dev.blendermc.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.blendermc.liminal.LiminalBlocks;
import dev.blendermc.liminal.LiminalLevels;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Off-grid blocks (old saves) reach up to a block into their neighbours, so clicks on them may be twice vanilla's
 * one block from the clicked cell's centre. In the liminal dimension, every block click is also logged (temporary,
 * to find out why some clicks do nothing): the checks vanilla drops a click on without saying anything.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LiminalUseItemOnMixin {
	@Shadow
	public ServerPlayer player;

	@Shadow
	private @Nullable Vec3 awaitingPositionFromClient;

	@ModifyExpressionValue(method = "handleUseItemOn", at = @At(value = "CONSTANT", args = "doubleValue=1.0000001"))
	private double blendermc$offGridReach(double original, @Local(argsOnly = true) ServerboundUseItemOnPacket packet) {
		return this.player.level().getBlockState(packet.getHitResult().getBlockPos()).is(LiminalBlocks.OFFSET_BLOCK) ? 2.0000001 : original;
	}

	@Inject(method = "handleUseItemOn", at = @At("HEAD"))
	private void blendermc$logLiminalClick(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		if (!this.player.level().getServer().isSameThread() || !LiminalLevels.isLiminal(this.player.level())) {
			return;
		}
		BlockHitResult hit = packet.getHitResult();
		Vec3 fromCentre = hit.getLocation().subtract(Vec3.atCenterOf(hit.getBlockPos()));
		LiminalLevels.LOGGER.info("liminal click: {} at {} face {} point {} (from centre {}), in reach {}, waiting for teleport ack {}",
			this.player.level().getBlockState(hit.getBlockPos()), hit.getBlockPos().toShortString(), hit.getDirection(), hit.getLocation(), fromCentre,
			this.player.isWithinBlockInteractionRange(hit.getBlockPos(), 1.0), this.awaitingPositionFromClient != null);
	}
}
