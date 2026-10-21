package dev.echoliminal.client.mixin;

import net.minecraft.client.renderer.entity.RaftRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The raft texture, for the Blender live link. */
@Mixin(RaftRenderer.class)
public interface RaftRendererAccessor {
	@Accessor("texture")
	Identifier echoliminal$texture();
}
