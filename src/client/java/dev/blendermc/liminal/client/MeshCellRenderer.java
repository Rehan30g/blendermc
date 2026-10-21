package dev.blendermc.liminal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.blendermc.liminal.MeshCellBlockEntity;
import dev.blendermc.liminal.MeshPiece;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws placed pieces ({@code mesh_cell} blocks carrying their own {@link MeshPiece}) in any dimension, lit like
 * the level ({@link LiminalPieceDraw#submitPlaced}). Level cells draw nothing here: the level renderer draws them.
 */
public final class MeshCellRenderer implements BlockEntityRenderer<MeshCellBlockEntity, MeshCellRenderer.State> {
	public static final class State extends BlockEntityRenderState {
		@Nullable MeshPiece piece;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/** Level cells (thousands per level) are skipped before extraction. */
	@Override
	public boolean shouldRender(MeshCellBlockEntity entity, Vec3 camera) {
		return !entity.isLevelCell() && entity.piece() != null && BlockEntityRenderer.super.shouldRender(entity, camera);
	}

	@Override
	public void extractRenderState(MeshCellBlockEntity entity, State state, float partialTick, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);
		state.piece = entity.isLevelCell() ? null : entity.piece();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.piece != null) {
			LiminalPieceDraw.submitPlaced(state.piece, state.blockPos, poseStack, collector, state.lightCoords);
		}
	}
}
