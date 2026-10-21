package dev.echoliminal.client.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A boat's model, so the Blender live link can pose and capture it like a mob's. */
@Mixin(AbstractBoatRenderer.class)
public interface AbstractBoatRendererInvoker {
	@Invoker("model")
	EntityModel<BoatRenderState> echoliminal$model();
}
