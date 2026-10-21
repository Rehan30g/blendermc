package dev.blendermc.liminal.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.blendermc.BlenderMc;
import dev.blendermc.liminal.LevelSky;
import dev.blendermc.liminal.LiminalDay;
import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Per-level sky of the liminal dimension: the level's skybox mesh (materials ending "_sky") drawn camera-centred in
 * vanilla's sky pass, just before the sun (no depth, full bright, no fog), and client-side overrides of the
 * environment attributes (sky/fog colour, fog distances, sun angle, sky light) from the level's {@link LevelSky}.
 * Render thread only, except {@link #override} which is a cheap read.
 */
public final class LiminalSky {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/liminal_sky"))
		.withVertexShader("core/position_tex_color")
		.withFragmentShader("core/position_tex_color")
		.withSampler("Sampler0")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
		.build();
	/** The dome is scaled down to this radius so it never reaches the far plane (looks the same camera-centred). */
	private static final float MAX_RADIUS = 200.0F;

	private record Range(AbstractTexture texture, int first, int count) {
	}

	private static @Nullable LiminalMesh builtFor;
	private static @Nullable GpuBuffer vertices;
	private static List<Range> ranges = List.of();

	private LiminalSky() {
	}

	/** True when the client is in the liminal dimension. */
	public static boolean active() {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && LiminalLevels.isLiminal(level);
	}

	/**
	 * The whole sky pass of the liminal dimension, replacing vanilla's: the frame is cleared to the level's sky
	 * colour and the skybox is drawn on top. Vanilla's disc, sunrise band, sun, moon, stars and dark disc are
	 * cancelled by the sky renderer mixin.
	 */
	public static void drawSkyPass() {
		RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
		RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), skyColorArgb());
		draw();
	}

	/** The level's sky colour (ARGB, opaque; black if unset). */
	public static int skyColorArgb() {
		LevelSky sky = LiminalLevels.activeSky();
		int rgb = sky != null && sky.skyColor() >= 0 ? sky.skyColor() : 0;
		LiminalDay.Day day = sky != null && sky.dayCycle() ? LiminalDayClock.now() : null;
		return ARGB.opaque(day == null ? rgb : cycleColor(rgb, day));
	}

	/**
	 * Fog of the liminal dimension, used for the mesh, blocks and entities alike: {start, end, r, g, b} from the
	 * level, or no fog (start = end = MAX) when the level defines none; null outside the liminal dimension.
	 */
	public static float @Nullable [] fog(@Nullable Level level) {
		if (!(level instanceof ClientLevel) || !LiminalLevels.isLiminal(level)) {
			return null;
		}
		LevelSky sky = LiminalLevels.activeSky();
		int color = sky != null && sky.fogColor() >= 0 ? sky.fogColor() : skyColorArgb();
		LiminalDay.Day fogDay = sky != null && sky.dayCycle() && sky.fogColor() >= 0 ? LiminalDayClock.now() : null;
		if (fogDay != null) {
			color = ARGB.opaque(cycleColor(sky.fogColor(), fogDay));
		}
		float start = sky == null || Float.isNaN(sky.fogStart()) ? Float.MAX_VALUE : sky.fogStart();
		float end = sky == null || Float.isNaN(sky.fogEnd()) ? Float.MAX_VALUE : sky.fogEnd();
		if (start == Float.MAX_VALUE || end == Float.MAX_VALUE) {
			start = end = Float.MAX_VALUE;
		}
		return new float[] {start, end, ARGB.redFloat(color), ARGB.greenFloat(color), ARGB.blueFloat(color)};
	}

	/** Draws the active level's skybox into the main target; called from the sky pass. */
	public static void draw() {
		LiminalMesh mesh = active() ? LiminalLevels.activeSkyMesh() : null;
		if (mesh != builtFor) {
			free();
			builtFor = mesh;
			if (mesh != null) {
				build(mesh);
			}
		}
		LiminalDay.Day day = LiminalDayClock.now();
		if (vertices != null && !ranges.isEmpty()) {
			drawSkybox(day);
		}
		LevelSky sky = LiminalLevels.activeSky();
		if (day != null && sky != null) {
			drawCelestials(day, sky);
		}
	}

	private static void drawSkybox(LiminalDay.@Nullable Day day) {
		Minecraft mc = Minecraft.getInstance();
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrix(), skyboxTint(day), new Vector3f(), new Matrix4f());
		RenderTarget target = mc.getMainRenderTarget();
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "blendermc liminal sky", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, vertices);
			for (Range range : ranges) {
				pass.bindTexture("Sampler0", range.texture.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
				pass.draw(range.first, range.count);
			}
		}
	}

	private static void build(LiminalMesh mesh) {
		float[] pos = mesh.positions();
		float[] uvs = mesh.uvs();
		int[] idx = mesh.indices();
		int[] triMat = mesh.triangleMaterial();
		// Raw glTF positions, relative to the eye (the loader does not add ORIGIN to the sky mesh).
		float cx = 0.0F, cy = 0.0F, cz = 0.0F;
		float max = 1.0E-3F;
		for (int v = 0; v < pos.length / 3; v++) {
			float dx = pos[v * 3] - cx, dy = pos[v * 3 + 1] - cy, dz = pos[v * 3 + 2] - cz;
			max = Math.max(max, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		float scale = Math.min(1.0F, MAX_RADIUS / max);
		int triCount = idx.length / 3;
		VertexFormat format = DefaultVertexFormat.POSITION_TEX_COLOR;
		List<Range> built = new ArrayList<>();
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(Math.max(1, triCount * 3) * format.getVertexSize())) {
			BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, format);
			int written = 0;
			for (int m = 0; m < mesh.materials().size(); m++) {
				LiminalMesh.Material material = mesh.materials().get(m);
				int first = written;
				float[] c = material.baseColor() != null && material.baseColor().length >= 4 ? material.baseColor() : new float[] {1, 1, 1, 1};
				int color = ARGB.colorFromFloat(c[3], c[0], c[1], c[2]);
				for (int t = 0; t < triCount; t++) {
					if ((t < triMat.length ? triMat[t] : 0) != m) {
						continue;
					}
					for (int k = 0; k < 3; k++) {
						int v = idx[t * 3 + k];
						builder.addVertex((pos[v * 3] - cx) * scale, (pos[v * 3 + 1] - cy) * scale, (pos[v * 3 + 2] - cz) * scale)
							.setUv(uvs.length > v * 2 + 1 ? uvs[v * 2] : 0.0F, uvs.length > v * 2 + 1 ? uvs[v * 2 + 1] : 0.0F)
							.setColor(color);
						written++;
					}
				}
				if (written > first) {
					AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(LiminalTextures.get(material.texturePng()));
					built.add(new Range(texture, first, written - first));
				}
			}
			MeshData data = builder.build();
			if (data == null) {
				return;
			}
			try (data) {
				vertices = RenderSystem.getDevice().createBuffer(() -> "blendermc liminal sky", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
			}
			ranges = built;
		} catch (RuntimeException e) {
			BlenderMc.LOGGER.error("Could not build the liminal skybox of {}", mesh.name(), e);
			free();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Day cycle: sky colours by time, procedural sun and moon billboards.

	private static final float[] SUN_WARM = {1.0F, 0.86F, 0.62F}, SUN_LOW = {1.0F, 0.42F, 0.18F}, MOON_COLOR = {0.78F, 0.84F, 1.0F};
	/** Sky distance of the billboards (inside the dome radius and the far plane). */
	private static final float CELESTIAL_DISTANCE = 100.0F;
	private static final int TEX_SIZE = 64;
	private static final Identifier SUN_TEXTURE = BlenderMc.id("liminal/sky_sun");
	private static final Identifier MOON_TEXTURE = BlenderMc.id("liminal/sky_moon");
	private static final RenderPipeline CELESTIAL_PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/liminal_celestial"))
		.withVertexShader("core/position_tex_color")
		.withFragmentShader("core/position_tex_color")
		.withSampler("Sampler0")
		.withBlend(BlendFunction.ADDITIVE)
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
		.build();
	private static boolean celestialTextures;
	private static @Nullable GpuBuffer quad;

	/** Colour of the sky or the fog at time {@code d}: the level colour by day, 7..17 percent and bluer at night, a warm glow at twilight. */
	static int cycleColor(int rgb, LiminalDay.Day d) {
		float r = (rgb >> 16 & 255) / 255.0F, g = (rgb >> 8 & 255) / 255.0F, b = (rgb & 255) / 255.0F;
		float t = d.day;
		float w = d.twilight * 0.30F;
		float cr = Mth.lerp(t, r * 0.07F + 0.004F, r) + w * 0.55F;
		float cg = Mth.lerp(t, g * 0.09F + 0.008F, g) + w * 0.20F;
		float cb = Mth.lerp(t, b * 0.17F + 0.028F, b) + w * 0.06F;
		return (Mth.clamp(Math.round(cr * 255.0F), 0, 255) << 16) | (Mth.clamp(Math.round(cg * 255.0F), 0, 255) << 8) | Mth.clamp(Math.round(cb * 255.0F), 0, 255);
	}

	/** Multiplier of the skybox texture: dark blue at night, warm at sunrise and sunset. */
	private static Vector4f skyboxTint(LiminalDay.@Nullable Day d) {
		if (d == null) {
			return new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
		}
		float t = d.day, w = d.twilight * 0.7F;
		float r = Mth.lerp(t, 0.07F, 1.0F), g = Mth.lerp(t, 0.09F, 1.0F), b = Mth.lerp(t, 0.21F, 1.0F);
		return new Vector4f(r, Mth.lerp(w, g, g * 0.72F), Mth.lerp(w, b, b * 0.55F), 1.0F);
	}

	/** Colour the sky light (lightmap) gets: bluish moonlight at night, warm at twilight, white by day (0xRRGGBB). */
	static int skyLightColor(LiminalDay.Day d) {
		float t = d.day, w = d.twilight * 0.5F;
		float r = Mth.lerp(t, 0.50F, 1.0F), g = Mth.lerp(t, 0.62F, 1.0F), b = 1.0F;
		g = Mth.lerp(w, g, g * 0.84F);
		b = Mth.lerp(w, b, b * 0.68F);
		return (Math.round(r * 255.0F) << 16) | (Math.round(g * 255.0F) << 8) | Math.round(b * 255.0F);
	}

	/** Brightness of the sky light: about a fifth at night. */
	static float skyLightFactor(LiminalDay.Day d) {
		return 0.20F + 0.80F * d.day;
	}

	private static void ensureCelestial() {
		if (!celestialTextures) {
			celestialTextures = true;
			register(SUN_TEXTURE, true);
			register(MOON_TEXTURE, false);
		}
		if (quad == null) {
			VertexFormat format = DefaultVertexFormat.POSITION_TEX_COLOR;
			try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(6 * format.getVertexSize())) {
				BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, format);
				float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, -1, 0, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
				for (float[] c : corners) {
					builder.addVertex(c[0], c[1], 0.0F).setUv(c[2], c[3]).setColor(-1);
				}
				try (MeshData data = builder.buildOrThrow()) {
					quad = RenderSystem.getDevice().createBuffer(() -> "blendermc liminal celestial quad", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
				}
			}
		}
	}

	/**
	 * Soft round glow as a texture: grey intensity (the pipeline adds it, tinted by the colour modulator), 64 px sampled
	 * linearly (blurry), with 4x4 ordered dither before quantising to 24 levels for the low-res retro look.
	 */
	private static void register(Identifier id, boolean sun) {
		int[] bayer = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
		NativeImage image = new NativeImage(TEX_SIZE, TEX_SIZE, false);
		for (int y = 0; y < TEX_SIZE; y++) {
			for (int x = 0; x < TEX_SIZE; x++) {
				float dx = (x + 0.5F) / TEX_SIZE * 2.0F - 1.0F, dy = (y + 0.5F) / TEX_SIZE * 2.0F - 1.0F;
				float r = (float) Math.sqrt(dx * dx + dy * dy);
				float edge = 1.0F - smooth(0.75F, 1.0F, r);
				float intensity;
				if (sun) {
					float core = 1.0F - smooth(0.13F, 0.21F, r);
					float glow = 0.60F * (float) Math.exp(-(r / 0.26F) * (r / 0.26F));
					float halo = 0.28F * (float) Math.exp(-(r / 0.55F) * (r / 0.55F));
					intensity = Math.min(1.0F, core + glow + halo) * edge;
				} else {
					float disc = 0.85F * (1.0F - smooth(0.33F, 0.43F, r));
					// Two soft dark seas on the disc.
					float s1 = (float) Math.exp(-(((dx + 0.12F) * (dx + 0.12F) + (dy - 0.08F) * (dy - 0.08F)) / 0.012F));
					float s2 = (float) Math.exp(-(((dx - 0.14F) * (dx - 0.14F) + (dy + 0.12F) * (dy + 0.12F)) / 0.008F));
					disc *= 1.0F - 0.22F * s1 - 0.16F * s2;
					intensity = (disc + 0.10F * (float) Math.exp(-(r / 0.7F) * (r / 0.7F))) * edge;
				}
				float dither = bayer[(y & 3) * 4 + (x & 3)] / 16.0F;
				int v = Mth.clamp(Math.round((float) Math.floor(intensity * 24.0F + dither) / 24.0F * 255.0F), 0, 255);
				image.setPixel(x, y, 0xFF000000 | v << 16 | v << 8 | v);
			}
		}
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
	}

	private static float smooth(float a, float b, float x) {
		float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** Sun and moon of the cycle, drawn over the skybox: the sun a warm blurry disc with a wide glow, the moon smaller and pale, opposite. */
	private static void drawCelestials(LiminalDay.Day d, LevelSky sky) {
		float sunFade = smooth(-0.10F, 0.06F, (float) d.sy);
		float moonFade = smooth(-0.10F, 0.06F, (float) -d.sy);
		if (sunFade < 0.01F && moonFade < 0.01F) {
			return;
		}
		ensureCelestial();
		Minecraft mc = Minecraft.getInstance();
		if (sunFade >= 0.01F && sky.sunVisible()) {
			float warm = smooth(0.0F, 0.40F, (float) d.sy);
			float[] c = {Mth.lerp(warm, SUN_LOW[0], SUN_WARM[0]), Mth.lerp(warm, SUN_LOW[1], SUN_WARM[1]), Mth.lerp(warm, SUN_LOW[2], SUN_WARM[2])};
			float size = Mth.lerp(warm, 1.3F, 1.0F);
			billboard(mc, SUN_TEXTURE, d.sx, d.sy, d.sz, 24.0F * size, c[0] * sunFade, c[1] * sunFade, c[2] * sunFade);
		}
		if (moonFade >= 0.01F) {
			float k = 0.9F * moonFade * (1.0F - d.day);
			billboard(mc, MOON_TEXTURE, -d.sx, -d.sy, -d.sz, 7.5F, MOON_COLOR[0] * k, MOON_COLOR[1] * k, MOON_COLOR[2] * k);
		}
	}

	/** A camera-facing square of half-angle {@code halfAngleDeg} towards (x, y, z), added to the frame in the given colour. */
	private static void billboard(Minecraft mc, Identifier texture, double x, double y, double z, float halfAngleDeg, float r, float g, float b) {
		Vector3f dir = new Vector3f((float) x, (float) y, (float) z).normalize();
		Vector3f right = new Vector3f(0.0F, 1.0F, 0.0F).cross(dir);
		if (right.lengthSquared() < 1.0E-6F) {
			right.set(1.0F, 0.0F, 0.0F);
		}
		right.normalize();
		Vector3f up = new Vector3f(dir).cross(right);
		float half = CELESTIAL_DISTANCE * (float) Math.tan(Math.toRadians(halfAngleDeg));
		Matrix4f model = new Matrix4f(RenderSystem.getModelViewMatrix())
			.translate(dir.x * CELESTIAL_DISTANCE, dir.y * CELESTIAL_DISTANCE, dir.z * CELESTIAL_DISTANCE)
			.mul(new Matrix4f(right.x, right.y, right.z, 0.0F, up.x, up.y, up.z, 0.0F, dir.x, dir.y, dir.z, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F))
			.scale(half, half, 1.0F);
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(model, new Vector4f(r, g, b, 1.0F), new Vector3f(), new Matrix4f());
		RenderTarget target = mc.getMainRenderTarget();
		var view = mc.getTextureManager().getTexture(texture).getTextureView();
		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "blendermc liminal celestial", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(CELESTIAL_PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, quad);
			pass.bindTexture("Sampler0", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(0, 6);
		}
	}

	static void free() {
		if (vertices != null) {
			vertices.close();
			vertices = null;
		}
		ranges = List.of();
		builtFor = null;
		if (quad != null) {
			quad.close();
			quad = null;
		}
	}

	/**
	 * The liminal override for an environment attribute read through a probe in {@code level}, or null for vanilla.
	 * Called very often: cheap when not in the liminal dimension.
	 */
	public static @Nullable Object override(@Nullable Level level, EnvironmentAttribute<?> attribute) {
		if (!(level instanceof ClientLevel) || !LiminalLevels.isLiminal(level)) {
			return null;
		}
		LevelSky sky = LiminalLevels.activeSky();
		if (sky == null || sky == LevelSky.NONE) {
			return null;
		}
		LiminalDay.Day day = sky.dayCycle() ? LiminalDayClock.now() : null;
		if (attribute == EnvironmentAttributes.SKY_COLOR) {
			return sky.skyColor() >= 0 ? ARGB.opaque(day == null ? sky.skyColor() : cycleColor(sky.skyColor(), day)) : null;
		}
		if (attribute == EnvironmentAttributes.FOG_COLOR) {
			return sky.fogColor() >= 0 ? ARGB.opaque(day == null ? sky.fogColor() : cycleColor(sky.fogColor(), day)) : null;
		}
		if (attribute == EnvironmentAttributes.FOG_START_DISTANCE) {
			return Float.isNaN(sky.fogStart()) ? null : sky.fogStart();
		}
		if (attribute == EnvironmentAttributes.FOG_END_DISTANCE) {
			return Float.isNaN(sky.fogEnd()) ? null : sky.fogEnd();
		}
		if (attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR) {
			return !sky.skylight() ? Float.valueOf(0.0F) : day != null ? Float.valueOf(skyLightFactor(day)) : null;
		}
		if (attribute == EnvironmentAttributes.SKY_LIGHT_COLOR) {
			return sky.skylight() && day != null ? Integer.valueOf(skyLightColor(day)) : null;
		}
		if (attribute == EnvironmentAttributes.CLOUD_COLOR) {
			return 0;
		}
		if (!Float.isNaN(sky.timeOfDay())) {
			// Vanilla celestial angle: 0 at noon (6000), the sun rises in the east at 0.
			float sun = (float) (Math.floorMod((long) Math.floor(sky.timeOfDay()) - 6000L, 24000L) / 24000.0 * 360.0);
			if (attribute == EnvironmentAttributes.SUN_ANGLE || attribute == EnvironmentAttributes.STAR_ANGLE) {
				return sun;
			}
			if (attribute == EnvironmentAttributes.MOON_ANGLE) {
				return (sun + 180.0F) % 360.0F;
			}
		}
		return null;
	}
}
