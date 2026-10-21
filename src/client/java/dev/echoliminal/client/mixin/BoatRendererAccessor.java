package dev.echoliminal.client.mixin;

import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boat texture, for the Blender live link. */
@Mixin(BoatRenderer.class)
public interface BoatRendererAccessor {
	@Accessor("texture")
	Identifier echoliminal$texture();
}
