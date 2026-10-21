package dev.echoliminal.liminal.client;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.echoliminal.EchoLiminal;
import dev.echoliminal.liminal.LevelSky;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.LiminalMesh;
import dev.echoliminal.liminal.MeshPiece;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link MeshPiece} (cell-local 0..1 geometry) through the submit queue with the same shader and look as
 * the level renderer: vanilla's {@code core/block} shader (lightmap, fog, cutout 0.5, no culling), face shading,
 * the fluorescent tint, the level's sun factor, and — for placed pieces in the liminal dimension — the level's
 * fluorescent light combined with the block light. Triangles are sent as quads with the last vertex repeated.
 * Render thread only.
 */
public final class LiminalPieceDraw {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(EchoLiminal.id("pipeline/liminal_piece"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withCull(false)
		.build();
	private static final Map<Identifier, RenderType> TYPES = new HashMap<>();
	/** Weak identity keys: pieces live in item components and block entities, and are replaced, not mutated. */
	private static final Map<MeshPiece, Baked> CACHE = new MapMaker().weakKeys().makeMap();

	private LiminalPieceDraw() {
	}

	/** One material's triangles: 8 floats per vertex (x, y, z, u, v, nx, ny, nz), 3 vertices per triangle. */
	private record Group(RenderType renderType, float[] color, boolean emissive, float[] vertices) {
	}

	private record Baked(List<Group> groups) {
	}

	/** Submits a held/dropped/GUI piece; {@code light} is the packed light of the item context. */
	public static void submitItem(MeshPiece piece, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		submit(piece, poseStack, collector, null, light, LevelSky.NONE);
	}

	/**
	 * Submits a placed piece at {@code pos}: inside the liminal dimension it is lit like the level (fluorescent
	 * light of the drawn level, its sky light and sun); elsewhere by the block's packed light.
	 */
	public static void submitPlaced(MeshPiece piece, BlockPos pos, PoseStack poseStack, SubmitNodeCollector collector, int packedLight) {
		LevelSky sky = LiminalRenderer.drawnSky();
		submit(piece, poseStack, collector, sky == LevelSky.NONE ? null : pos, packedLight, sky);
	}

	private static void submit(@Nullable MeshPiece piece, PoseStack poseStack, SubmitNodeCollector collector, @Nullable BlockPos levelPos, int packedLight,
			LevelSky sky) {
		if (piece == null || piece.triangles().isEmpty()) {
			return;
		}
		Baked baked = CACHE.computeIfAbsent(piece, LiminalPieceDraw::bake);
		int blockLight = LightTexture.block(packedLight) * 16;
		int skyLight = LightTexture.sky(packedLight) * 16;
		if (levelPos != null) {
			int field = LiminalLightField.packed(levelPos, packedLight);
			blockLight = LightTexture.block(field) * 16;
			skyLight = LightTexture.sky(field) * 16;
		}
		for (Group group : baked.groups) {
			float[] v = group.vertices;
			int count = v.length / 8;
			int[] colors = new int[count];
			int[] lights = new int[count];
			for (int i = 0; i < count; i++) {
				int at = i * 8;
				float nx = v[at + 5], ny = v[at + 6], nz = v[at + 7];
				float[] c = group.color;
				if (group.emissive) {
					colors[i] = ARGB.colorFromFloat(c[3], c[0], c[1], c[2]);
					lights[i] = LightTexture.FULL_BRIGHT;
					continue;
				}
				if (sky.unlit()) {
					colors[i] = ARGB.colorFromFloat(c[3], c[0], c[1], c[2]);
					lights[i] = LightTexture.FULL_BRIGHT;
					continue;
				}
				float shade = LiminalMeshBaker.shade(nx, ny, nz) * sky.sunShade(nx, ny, nz);
				colors[i] = ARGB.colorFromFloat(c[3], c[0] * shade * LiminalMeshBaker.TINT_R, c[1] * shade * LiminalMeshBaker.TINT_G,
					c[2] * shade * LiminalMeshBaker.TINT_B);
				lights[i] = blockLight | skyLight << 16;
			}
			collector.submitCustomGeometry(poseStack, group.renderType, (pose, consumer) -> emit(group, pose, consumer, colors, lights));
		}
	}

	private static void emit(Group group, PoseStack.Pose pose, VertexConsumer consumer, int[] colors, int[] lights) {
		float[] v = group.vertices;
		for (int t = 0; t < v.length / 8; t += 3) {
			for (int k = 0; k < 4; k++) {
				int i = t + Math.min(k, 2);
				int at = i * 8;
				consumer.addVertex(pose, v[at], v[at + 1], v[at + 2])
					.setColor(colors[i])
					.setUv(v[at + 3], v[at + 4])
					.setLight(lights[i])
					.setNormal(pose, v[at + 5], v[at + 6], v[at + 7]);
			}
		}
	}

	static RenderType renderType(Identifier texture) {
		return TYPES.computeIfAbsent(texture, id -> RenderType.create("echoliminal_liminal_piece",
			RenderSetup.builder(PIPELINE)
				.useLightmap()
				.withTexture("Sampler0", id, () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST))
				.affectsCrumbling()
				.createRenderSetup()));
	}

	/**
	 * The PNG of a piece material; pieces may come without it (textures over the piece size limit are dropped), then
	 * the source level's material of the same name supplies it, so the piece does not turn white.
	 */
	private static byte @Nullable [] texture(MeshPiece piece, MeshPiece.PieceMaterial material) {
		if (material.texturePng() != null && material.texturePng().length > 0) {
			return material.texturePng();
		}
		LiminalMesh level = piece.sourceLevel() == null ? null : LiminalLevels.get(piece.sourceLevel());
		if (level != null) {
			for (LiminalMesh.Material m : level.materials()) {
				if (m.name().equals(material.name())) {
					return m.texturePng();
				}
			}
		}
		return null;
	}

	private static Baked bake(MeshPiece piece) {
		List<MeshPiece.PieceMaterial> materials = piece.materials();
		List<Group> groups = new ArrayList<>();
		for (int m = 0; m < materials.size(); m++) {
			int count = 0;
			for (MeshPiece.Tri tri : piece.triangles()) {
				if (tri.material() == m) {
					count++;
				}
			}
			if (count == 0) {
				continue;
			}
			float[] out = new float[count * 24];
			int at = 0;
			for (MeshPiece.Tri tri : piece.triangles()) {
				if (tri.material() != m) {
					continue;
				}
				for (int k = 0; k < 3; k++) {
					out[at++] = tri.positions()[k * 3];
					out[at++] = tri.positions()[k * 3 + 1];
					out[at++] = tri.positions()[k * 3 + 2];
					out[at++] = tri.uvs()[k * 2];
					out[at++] = tri.uvs()[k * 2 + 1];
					out[at++] = tri.normals()[k * 3];
					out[at++] = tri.normals()[k * 3 + 1];
					out[at++] = tri.normals()[k * 3 + 2];
				}
			}
			MeshPiece.PieceMaterial material = materials.get(m);
			float[] c = material.baseColor() != null && material.baseColor().length >= 4 ? material.baseColor() : new float[] {1, 1, 1, 1};
			Identifier texture = LiminalTextures.get(texture(piece, material));
			groups.add(new Group(renderType(texture), c, material.emissive(), out));
		}
		return new Baked(groups);
	}
}
