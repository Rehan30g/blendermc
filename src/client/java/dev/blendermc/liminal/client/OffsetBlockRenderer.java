package dev.blendermc.liminal.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.blendermc.liminal.LevelSky;
import dev.blendermc.liminal.OffsetBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Draws {@code blendermc:offset_block}: the contained block's model moved by the block entity's offset, through the
 * same block shader as the level mesh. Lit flat (no AO) by the light at the cube's centre, which in the liminal
 * dimension is the level's light field; faces get the mesh's face shade times the level's sun factor (vanilla face
 * shade elsewhere). No crack overlay (the outline and break particles still work).
 */
public final class OffsetBlockRenderer implements BlockEntityRenderer<OffsetBlockEntity, OffsetBlockRenderer.State> {
	private static final Direction[] FACES_AND_NULL = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};

	public static final class State extends BlockEntityRenderState {
		BlockState contained = Blocks.AIR.defaultBlockState();
		Vec3 offset = Vec3.ZERO;
		int light;
		long seed;
		/** Tint colours by tint index (0..3), resolved at the cube's centre. */
		final int[] tints = new int[4];
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public void extractRenderState(OffsetBlockEntity entity, State state, float partialTick, Vec3 camera, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);
		state.contained = entity.contained();
		state.offset = entity.offset();
		BlockPos centre = BlockPos.containing(entity.getBlockPos().getX() + state.offset.x + 0.5, entity.getBlockPos().getY() + state.offset.y + 0.5,
			entity.getBlockPos().getZ() + state.offset.z + 0.5);
		state.light = entity.getLevel() != null ? LevelRenderer.getLightColor(entity.getLevel(), centre) : LightTexture.FULL_BRIGHT;
		state.seed = state.contained.getSeed(entity.getBlockPos());
		for (int i = 0; i < state.tints.length; i++) {
			state.tints[i] = Minecraft.getInstance().getBlockColors().getColor(state.contained, entity.getLevel(), centre, i);
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.contained.isAir()) {
			return;
		}
		List<BlockModelPart> parts = new ArrayList<>();
		Minecraft.getInstance().getBlockRenderer().getBlockModel(state.contained).collectParts(RandomSource.create(state.seed), parts);
		List<BakedQuad> quads = new ArrayList<>();
		for (BlockModelPart part : parts) {
			for (Direction face : FACES_AND_NULL) {
				quads.addAll(part.getQuads(face));
			}
		}
		if (quads.isEmpty()) {
			return;
		}
		LevelSky sky = LiminalRenderer.drawnSky();
		boolean liminal = LiminalLightField.isActive();
		int light = state.light;
		int[] tints = state.tints.clone();
		RenderType type = LiminalPieceDraw.renderType(TextureAtlas.LOCATION_BLOCKS);
		poseStack.pushPose();
		poseStack.translate(state.offset.x, state.offset.y, state.offset.z);
		collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
			for (BakedQuad quad : quads) {
				emit(quad, pose, consumer, light, tints, sky, liminal);
			}
		});
		poseStack.popPose();
	}

	private static void emit(BakedQuad quad, PoseStack.Pose pose, VertexConsumer consumer, int light, int[] tints, LevelSky sky, boolean liminal) {
		Direction d = quad.direction();
		float nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
		float shade = 1.0F;
		if (quad.shade()) {
			shade = LiminalMeshBaker.shade(nx, ny, nz) * (liminal ? sky.sunShade(nx, ny, nz) : 1.0F);
		}
		int tint = quad.isTinted() && quad.tintIndex() < tints.length ? tints[quad.tintIndex()] : -1;
		int color = ARGB.colorFromFloat(1.0F, ARGB.redFloat(tint) * shade, ARGB.greenFloat(tint) * shade, ARGB.blueFloat(tint) * shade);
		int quadLight = quad.lightEmission() > 0 ? LightTexture.lightCoordsWithEmission(light, quad.lightEmission()) : light;
		for (int i = 0; i < 4; i++) {
			Vector3fc p = quad.position(i);
			long uv = quad.packedUV(i);
			consumer.addVertex(pose, p.x(), p.y(), p.z())
				.setColor(color)
				.setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
				.setLight(quadLight)
				.setNormal(pose, nx, ny, nz);
		}
	}
}
