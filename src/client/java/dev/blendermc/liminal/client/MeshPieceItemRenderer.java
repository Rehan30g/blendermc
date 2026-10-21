package dev.blendermc.liminal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import dev.blendermc.liminal.LiminalBlocks;
import dev.blendermc.liminal.MeshPiece;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Item model {@code blendermc:mesh_piece} (a {@code minecraft:special} model, see items/mesh_piece.json): draws the
 * stack's piece geometry inside the unit cube, so the item looks like the bit of wall or carpet it was.
 */
public final class MeshPieceItemRenderer implements SpecialModelRenderer<MeshPiece> {
	@Override
	public void submit(@Nullable MeshPiece piece, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
			boolean foil, int outlineColor) {
		LiminalPieceDraw.submitItem(piece, poseStack, collector, light);
	}

	@Override
	public void getExtents(Consumer<Vector3fc> consumer) {
		for (int i = 0; i < 8; i++) {
			consumer.accept(new Vector3f(i & 1, (i >> 1) & 1, (i >> 2) & 1));
		}
	}

	@Override
	public @Nullable MeshPiece extractArgument(ItemStack stack) {
		return stack.get(LiminalBlocks.MESH_PIECE_COMPONENT);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new MeshPieceItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
