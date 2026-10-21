package dev.echoliminal.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The body transforms of a living entity's render, so the Blender live link can pose its model the same way. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
	@Invoker("setupRotations")
	void echoliminal$setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale);

	@Invoker("scale")
	void echoliminal$scale(LivingEntityRenderState state, PoseStack poseStack);
}
