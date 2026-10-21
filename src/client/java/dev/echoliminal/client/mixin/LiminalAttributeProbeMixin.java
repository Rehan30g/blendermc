package dev.echoliminal.client.mixin;

import dev.echoliminal.liminal.client.LiminalSky;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-level sky of the liminal dimension: sky/fog colour, fog distances, sun angle and sky light come from the level file. */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class LiminalAttributeProbeMixin {
	@Shadow
	@Nullable Level level;

	@SuppressWarnings("unchecked")
	@Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
	private <V> void echoliminal$liminalSky(EnvironmentAttribute<V> attribute, float partialTick, CallbackInfoReturnable<V> cir) {
		Object value = LiminalSky.override(this.level, attribute);
		if (value != null) {
			cir.setReturnValue((V) value);
		}
	}
}
