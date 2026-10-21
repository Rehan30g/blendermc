package dev.blendermc.endless.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.blendermc.BlenderMc;
import dev.blendermc.liminal.client.LiminalTextures;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the streamed tiles into the main target after opaque terrain (same hook, vertex format and block shader as the
 * hand made levels, so fog and the lightmap behave the same). Three passes: opaque, decals (pulled towards the camera) and
 * translucent (water, far to near, no depth write). Tiles are culled against the view and the fog's end.
 */
final class EndlessRenderer {
	private static final RenderPipeline OPAQUE = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/endless"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withCull(false)
		.withVertexFormat(EndlessBuilder.FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();
	private static final RenderPipeline DECAL = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/endless_decal"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withCull(false)
		.withDepthBias(-1.0F, -10.0F)
		.withVertexFormat(EndlessBuilder.FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();
	private static final RenderPipeline TRANSLUCENT = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/endless_translucent"))
		.withShaderDefine("ALPHA_CUTOUT", 0.01F)
		.withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
		.withDepthWrite(false)
		.withCull(false)
		.withVertexFormat(EndlessBuilder.FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();
	private static final RenderPipeline[] PIPELINES = {OPAQUE, DECAL, TRANSLUCENT};

	private static com.mojang.blaze3d.textures.GpuTextureView[] textures;
	private static EndlessSource texturesFor;

	private EndlessRenderer() {
	}

	/** Frees nothing on the GPU (the textures are shared for the session); forgets the palette. */
	static void reset() {
		textures = null;
		texturesFor = null;
	}

	private static com.mojang.blaze3d.textures.GpuTextureView[] textures(Minecraft mc, EndlessStreamer streamer) {
		if (textures == null || texturesFor != streamer.source) {
			var list = streamer.materials.list;
			textures = new com.mojang.blaze3d.textures.GpuTextureView[Math.max(1, list.size())];
			for (int m = 0; m < textures.length; m++) {
				byte[] png = m < list.size() ? list.get(m).texturePng() : null;
				textures[m] = EndlessTextures.get(png);
			}
			texturesFor = streamer.source;
		}
		return textures;
	}

	/** Four side planes through the eye, inward normals, from the look basis and a field of view widened by a margin. */
	private static final class View {
		final Vector3f[] normals = new Vector3f[4];

		View(Quaternionf orientation, double fovDegrees, double aspect) {
			Vector3f f = new Vector3f(0, 0, -1).rotate(orientation);
			Vector3f u = new Vector3f(0, 1, 0).rotate(orientation);
			Vector3f l = new Vector3f(-1, 0, 0).rotate(orientation);
			double v = Math.toRadians(Math.min(fovDegrees * 0.5 + 12.0, 85.0));
			double h = Math.min(Math.atan(Math.tan(v) * aspect) + Math.toRadians(4.0), Math.toRadians(88.0));
			this.normals[0] = new Vector3f(f).mul((float) Math.sin(v)).sub(new Vector3f(u).mul((float) Math.cos(v)));
			this.normals[1] = new Vector3f(f).mul((float) Math.sin(v)).add(new Vector3f(u).mul((float) Math.cos(v)));
			this.normals[2] = new Vector3f(f).mul((float) Math.sin(h)).sub(new Vector3f(l).mul((float) Math.cos(h)));
			this.normals[3] = new Vector3f(f).mul((float) Math.sin(h)).add(new Vector3f(l).mul((float) Math.cos(h)));
		}

		/** Box given relative to the eye. */
		boolean sees(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			if (minX <= 0 && maxX >= 0 && minY <= 0 && maxY >= 0 && minZ <= 0 && maxZ >= 0) {
				return true;
			}
			double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
			double hx = (maxX - minX) * 0.5, hy = (maxY - minY) * 0.5, hz = (maxZ - minZ) * 0.5;
			for (Vector3f n : this.normals) {
				double reach = Math.abs(n.x) * hx + Math.abs(n.y) * hy + Math.abs(n.z) * hz;
				if (n.x * cx + n.y * cy + n.z * cz + reach < 0) {
					return false;
				}
			}
			return true;
		}
	}

	static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		EndlessStreamer streamer = EndlessClient.streamer();
		if (streamer == null || mc.level == null || !EndlessZones.isEndless(mc.level)) {
			return;
		}
		List<EndlessGpuTile> tiles = streamer.drawList();
		if (tiles.isEmpty()) {
			return;
		}
		Vec3 cam = context.worldState().cameraRenderState.pos;
		float[] fog = EndlessZones.fog(mc.level);
		double fogEnd = fog == null ? Double.MAX_VALUE : fog[1] + 16.0;
		View view = new View(context.worldState().cameraRenderState.orientation, mc.options.fov().get(),
			(double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()));
		List<EndlessGpuTile> visible = new ArrayList<>(tiles.size());
		for (EndlessGpuTile t : tiles) {
			if (t.isEmpty()) {
				continue;
			}
			double dx = Math.max(Math.max(t.bounds.minX - cam.x, 0), cam.x - t.bounds.maxX);
			double dz = Math.max(Math.max(t.bounds.minZ - cam.z, 0), cam.z - t.bounds.maxZ);
			if (dx * dx + dz * dz > fogEnd * fogEnd) {
				continue;
			}
			if (view.sees(t.bounds.minX - cam.x, t.bounds.minY - cam.y, t.bounds.minZ - cam.z, t.bounds.maxX - cam.x, t.bounds.maxY - cam.y,
				t.bounds.maxZ - cam.z)) {
				visible.add(t);
			}
		}
		if (visible.isEmpty()) {
			return;
		}
		var mats = textures(mc, streamer);
		AbstractTexture white = mc.getTextureManager().getTexture(LiminalTextures.white());
		RenderTarget target = mc.getMainRenderTarget();
		GpuSampler nearest = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
		GpuSampler smooth = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true);
		GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

		// Far to near for the blended pass.
		List<EndlessGpuTile> farToNear = new ArrayList<>(visible);
		farToNear.sort((a, b) -> Double.compare(centerDistance(b, cam), centerDistance(a, cam)));

		// One transform per tile, written before the pass opens.
		java.util.IdentityHashMap<EndlessGpuTile, GpuBufferSlice> slice = new java.util.IdentityHashMap<>();
		for (EndlessGpuTile t : visible) {
			Vector3f offset = new Vector3f((float) (t.anchorX - cam.x), (float) (t.anchorY - cam.y), (float) (t.anchorZ - cam.z));
			slice.put(t, RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), offset,
				new Matrix4f()));
		}

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "blendermc endless world", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(),
					OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			for (int passIndex = 0; passIndex < 3; passIndex++) {
				pass.setPipeline(PIPELINES[passIndex]);
				pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightmapSampler);
				for (EndlessGpuTile t : passIndex == 2 ? farToNear : visible) {
					boolean any = false;
					for (EndlessBuilder.Range r : t.ranges) {
						if (r.pass() == passIndex) {
							any = true;
							break;
						}
					}
					if (!any) {
						continue;
					}
					pass.setUniform("DynamicTransforms", slice.get(t));
					pass.setVertexBuffer(0, t.vertices);
					if (t.indexed) {
						pass.setIndexBuffer(t.indices, VertexFormat.IndexType.INT);
					}
					boolean textured = t.tile.lod() == 0;
					for (EndlessBuilder.Range r : t.ranges) {
						if (r.pass() != passIndex) {
							continue;
						}
						var tex = textured && r.material() < mats.length ? mats[r.material()] : null;
						if (tex != null) {
							pass.bindTexture("Sampler0", tex, smooth);
						} else {
							pass.bindTexture("Sampler0", white.getTextureView(), nearest);
						}
						if (t.indexed) {
							pass.drawIndexed(0, r.first(), r.count(), 1);
						} else {
							pass.draw(r.first(), r.count());
						}
					}
				}
			}
		}
	}

	private static double centerDistance(EndlessGpuTile t, Vec3 cam) {
		double dx = (t.bounds.minX + t.bounds.maxX) * 0.5 - cam.x, dz = (t.bounds.minZ + t.bounds.maxZ) * 0.5 - cam.z;
		return dx * dx + dz * dz;
	}
}
