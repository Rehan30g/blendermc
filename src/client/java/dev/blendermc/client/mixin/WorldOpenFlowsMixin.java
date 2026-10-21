package dev.blendermc.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.Lifecycle;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the "experimental settings" warnings: the liminal dimension marks every world experimental, but it opens fine. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	/** On open: treat the world-gen lifecycle as stable so the experimental backup prompt never shows. */
	@ModifyExpressionValue(
		method = "openWorldCheckWorldStemCompatibility",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle;"))
	private Lifecycle blendermc$stableLifecycle(Lifecycle original) {
		return Lifecycle.stable();
	}

	/** On create: skip the experimental/deprecated confirmation dialog. */
	@Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true)
	private static void blendermc$skipCreateWarning(
		net.minecraft.client.Minecraft minecraft,
		net.minecraft.client.gui.screens.worldselection.CreateWorldScreen screen,
		Lifecycle lifecycle, Runnable runnable, boolean bl, CallbackInfo ci) {
		runnable.run();
		ci.cancel();
	}
}
