package dev.blendermc.liminal.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.blendermc.BlenderMc;
import dev.blendermc.liminal.LevelSky;
import dev.blendermc.liminal.LiminalBlocks;
import dev.blendermc.liminal.LiminalBroken;
import dev.blendermc.liminal.LiminalCells;
import dev.blendermc.liminal.LiminalLevels;
import dev.blendermc.liminal.LiminalMesh;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Draws the active liminal level's mesh while the client is in {@code blendermc:liminal}, with its own look (baked
 * fluorescent light, emissive full bright, sampled block light, static sun factor) instead of Minecraft's lighting.
 *
 * <p>Hook: Fabric's {@link WorldRenderEvents#BEFORE_ENTITIES}, right after opaque terrain, into the main target with
 * depth test and write, so blocks and the mesh hide each other. Buffers are built once per mesh (baking runs
 * off-thread); each cell's triangles are drawn only while the world still holds that cell
 * ({@link LiminalCellVisibility}), and the index buffer is rebuilt in the tick after a change. Pipeline: vanilla
 * {@code core/block} shader (lightmap, vanilla fog), alpha cutout 0.5, no culling; decals with a depth bias.
 * Also wires the per-level sky, the surface outline, placed-piece and item rendering.
 */
public final class LiminalRenderer {
	/** {@code -Dblendermc.anywhere=true}: render in every dimension (testing). */
	private static final boolean ANYWHERE = Boolean.getBoolean("blendermc.anywhere");

	static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/liminal"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withCull(false)
		.withVertexFormat(LiminalMeshBaker.FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();
	/** Same, pulled towards the camera: decals lying on a surface (non-colliding materials) win the depth test. */
	static final RenderPipeline DECAL_PIPELINE = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
		.withLocation(BlenderMc.id("pipeline/liminal_decal"))
		.withShaderDefine("ALPHA_CUTOUT", 0.5F)
		.withCull(false)
		.withDepthBias(-1.0F, -10.0F)
		.withVertexFormat(LiminalMeshBaker.FORMAT, VertexFormat.Mode.TRIANGLES)
		.build();

	/** Texture of the cut faces where the level is dug open (the same stone as the fill). */
	private static final net.minecraft.resources.Identifier CUT_TEXTURE = net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/stone.png");

	private static final net.minecraft.resources.Identifier CUT_STONE = BlenderMc.id("liminal/cut_stone");
	private static boolean cutStoneLoaded;

	/** Stone as an own mip-less texture: the stock stone.png texture has mip levels that are never uploaded, so it sampled as noise. */
	private static net.minecraft.resources.Identifier cutStone(Minecraft mc) {
		if (!cutStoneLoaded) {
			cutStoneLoaded = true;
			try (java.io.InputStream in = mc.getResourceManager().open(CUT_TEXTURE)) {
				var image = com.mojang.blaze3d.platform.NativeImage.read(in);
				mc.getTextureManager().register(CUT_STONE, new net.minecraft.client.renderer.texture.DynamicTexture(CUT_STONE::toString, image));
			} catch (Exception e) {
				BlenderMc.LOGGER.warn("Cannot load stone for liminal cut faces: {}", e.toString());
				return LiminalTextures.white();
			}
		}
		return CUT_STONE;
	}

	private static @Nullable LiminalMesh wanted;
	private static @Nullable CompletableFuture<LiminalMeshBaker.Baked> pending;
	private static @Nullable LiminalGpuMesh current;
	private static @Nullable LiminalCellVisibility visibility;
	private static int brokenVersion = -1;
	private static int cutLightTicks;
	private static final int CUT_SECTIONS_PER_TICK = 8;
	private static final LongArrayList cutRelight = new LongArrayList();
	private static @Nullable LiminalWorldLights lightWorld;
	private static dev.blendermc.liminal.light.@Nullable LightSources bakeSources;
	/** Light tracking for the mesh being baked while the old version of the same level stays on screen (live edits). */
	private static @Nullable LiminalWorldLights swapLightWorld;
	private static net.minecraft.world.phys.@Nullable AABB currentBounds;

	private LiminalRenderer() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(LiminalRenderer::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(LiminalRenderer::render);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> chunkChanged(chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> chunkChanged(chunk.getPos().x, chunk.getPos().z));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			LiminalBroken.clear();
			client.execute(LiminalSky::free);
		});
		BlockEntityRenderers.register(LiminalBlocks.MESH_CELL_ENTITY, context -> new MeshCellRenderer());
		BlockEntityRenderers.register(LiminalBlocks.OFFSET_BLOCK_ENTITY, context -> new OffsetBlockRenderer());
		SpecialModelRenderers.ID_MAPPER.put(BlenderMc.id("mesh_piece"), MeshPieceItemRenderer.Unbaked.MAP_CODEC);
		LiminalOutline.register();
	}

	/**
	 * Re-meshes the vanilla chunk sections around {@code bounds} (their light comes from the level). Marked dirty instead
	 * of {@code allChanged()}: the old sections stay on screen until the new ones are built, so nothing flickers.
	 */
	static void remesh(Minecraft mc, net.minecraft.world.phys.AABB bounds) {
		net.minecraft.world.phys.AABB box = bounds.inflate(16.0);
		mc.levelRenderer.setSectionRangeDirty(net.minecraft.core.SectionPos.blockToSectionCoord(box.minX),
			net.minecraft.core.SectionPos.blockToSectionCoord(box.minY), net.minecraft.core.SectionPos.blockToSectionCoord(box.minZ),
			net.minecraft.core.SectionPos.blockToSectionCoord(box.maxX), net.minecraft.core.SectionPos.blockToSectionCoord(box.maxY),
			net.minecraft.core.SectionPos.blockToSectionCoord(box.maxZ));
	}

	private static boolean inLiminal(Minecraft mc) {
		return mc.level != null && !dev.blendermc.endless.client.EndlessZones.isEndless(mc.level) && (ANYWHERE || LiminalLevels.isLiminal(mc.level));
	}

	/** From the client level mixin: a block changed (render thread). */
	public static void blockChanged(ClientLevel level, BlockPos pos) {
		if (visibility != null && level == Minecraft.getInstance().level) {
			visibility.blockChanged(pos);
		}
		if (lightWorld != null && level == Minecraft.getInstance().level) {
			lightWorld.blockChanged(level, pos);
		}
	}

	private static void chunkChanged(int x, int z) {
		if (visibility != null) {
			visibility.chunkChanged(x, z);
		}
		if (lightWorld != null) {
			lightWorld.chunkChanged(x, z);
		}
	}

	private static void tick(Minecraft mc) {
		LiminalMesh want = null;
		if (inLiminal(mc)) {
			if (mc.player != null) {
				// Each level has its own region: draw the one the player is in.
				LiminalLevels.updateView(mc.player.getX(), mc.player.getZ());
			}
			try {
				want = LiminalLevels.active();
			} catch (RuntimeException e) {
				BlenderMc.LOGGER.warn("Liminal level source failed", e);
			}
		}
		if (want != wanted) {
			// A new build of the level on screen (Blender live link): keep drawing the old one until the new one is baked.
			boolean swap = want != null && wanted != null && current != null && want.name().equals(wanted.name());
			wanted = want;
			if (pending != null) {
				pending.thenAccept(LiminalMeshBaker.Baked::free);
				pending = null;
			}
			swapLightWorld = null;
			if (current != null && !swap) {
				net.minecraft.world.phys.AABB gone = currentBounds;
				current.close();
				current = null;
				LiminalLightField.set(null, null);
				if (gone != null) {
					remesh(mc, gone);
				}
			}
			if (!swap) {
				visibility = null;
				lightWorld = null;
				cutRelight.clear();
			}
			if (want != null) {
				LiminalMesh mesh = want;
				LiminalWorldLights world = new LiminalWorldLights(mesh.bounds(), dev.blendermc.liminal.light.LightModel.proxyCells(mesh));
				if (mc.level != null) {
					world.scanAll(mc.level);
				}
				if (swap) {
					swapLightWorld = world;
				} else {
					lightWorld = world;
				}
				dev.blendermc.liminal.light.LightSources sources = world.snapshot();
				bakeSources = sources;
				pending = CompletableFuture.supplyAsync(() -> LiminalMeshBaker.bake(mesh, sources));
			}
		}
		if (pending != null && pending.isDone()) {
			CompletableFuture<LiminalMeshBaker.Baked> done = pending;
			pending = null;
			try {
				LiminalMeshBaker.Baked baked = done.join();
				LiminalCells cells = LiminalLevels.cells(baked.mesh.name());
				LiminalCellVisibility tracker = new LiminalCellVisibility(cells != null && cells.mesh() == baked.mesh ? cells : new LiminalCells(baked.mesh),
					baked.mesh.name());
				if (mc.level != null) {
					tracker.update(mc.level);
				}
				if (swapLightWorld != null) {
					if (current != null) {
						current.close();
						current = null;
					}
					lightWorld = swapLightWorld;
					swapLightWorld = null;
					cutRelight.clear();
				}
				try {
					current = new LiminalGpuMesh(baked, tracker::isVisible, lightWorld);
					currentBounds = baked.mesh.bounds();
					visibility = tracker;
					brokenVersion = LiminalBroken.version();
					LiminalBroken.drain((level, cell) -> { });
					tracker.takeFlipped();
					if (baked.model.scene.applyVisibility(tracker::isVisible) > 0 && lightWorld != null) {
						lightWorld.everythingChanged();
					}
cutRelight.clear();
					LiminalLightField.set(baked, bakeSources);
					remesh(mc, baked.mesh.bounds());
				} catch (RuntimeException e) {
					baked.free();
					throw e;
				}
			} catch (RuntimeException e) {
				BlenderMc.LOGGER.error("Could not build liminal level {}", wanted == null ? "?" : wanted.name(), e);
			}
		}
		if (lightWorld != null && mc.level != null) {
			lightWorld.tick(mc.level);
		}
		if (current != null && visibility != null && mc.level != null) {
			long started = System.nanoTime();
			if (brokenVersion != LiminalBroken.version()) {
				brokenVersion = LiminalBroken.version();
				LiminalCellVisibility tracker = visibility;
				if (LiminalBroken.drain((level, cell) -> {
					if (level.equals(tracker.levelName())) {
						tracker.brokenChanged(cell);
					}
				})) {
					tracker.markAllDirty();
				}
			}
			boolean flips = visibility.update(mc.level);
			long updated = System.nanoTime();
			if (flips) {
				LongArrayList flipped = visibility.takeFlipped();
				var scene = current.baked.model.scene;
				if (flipped == null) {
					current.setVisible(visibility::isVisible);
					scene.applyVisibility(visibility::isVisible);
					if (lightWorld != null) {
						lightWorld.everythingChanged();
					}
				} else {
					current.updateVisible(flipped, visibility::isVisible);
					for (int i = 0; i < flipped.size(); i++) {
						scene.setCellEnabled(flipped.getLong(i), visibility.isVisible(flipped.getLong(i)));
						if (lightWorld != null) {
							lightWorld.cellChanged(flipped.getLong(i));
						}
					}
				}
			}
			long indexed = System.nanoTime();
			if (++cutLightTicks % 5 == 0 && current.cutLightStale(mc.level)) {
				current.cutsLit();
				cutRelight.addAll(visibility.cutSections());
			}
			LongArrayList dirtyCuts = visibility.takeDirtyCutSections();
			for (int i = 0; i < dirtyCuts.size(); i++) {
				current.rebuildCuts(dirtyCuts.getLong(i), visibility.cutsOfSection(dirtyCuts.getLong(i)));
			}
			// Relighting every cut section is spread over ticks.
			for (int n = 0; n < CUT_SECTIONS_PER_TICK && !cutRelight.isEmpty(); n++) {
				long section = cutRelight.removeLong(cutRelight.size() - 1);
				current.rebuildCuts(section, visibility.cutsOfSection(section));
			}
			long done = System.nanoTime();
			if (flips || !dirtyCuts.isEmpty()) {
				BlenderMc.LOGGER.debug("Liminal cell change: visibility {} us, index patch {} us, cuts ({} sections) {} us", (updated - started) / 1000,
					(indexed - updated) / 1000, dirtyCuts.size(), (done - indexed) / 1000);
			}
			current.tickLight(mc.level);
		}
	}

	/** The sky of the level being drawn (NONE outside the liminal dimension). */
	static LevelSky drawnSky() {
		LiminalGpuMesh mesh = current;
		return mesh == null ? LevelSky.NONE : mesh.baked.sky;
	}

	private static void render(WorldRenderContext context) {
		LiminalGpuMesh mesh = current;
		Minecraft mc = Minecraft.getInstance();
		if (mesh == null || !inLiminal(mc) || mesh.ranges.isEmpty()) {
			return;
		}
		Vec3 cam = context.worldState().cameraRenderState.pos;
		Vector3f offset = new Vector3f((float) (mesh.anchorX - cam.x), (float) (mesh.anchorY - cam.y), (float) (mesh.anchorZ - cam.z));
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), offset, new Matrix4f());
		RenderTarget target = mc.getMainRenderTarget();
		GpuSampler nearest = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
		GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		// Fetched before the pass opens: the first fetch uploads the texture, which is not allowed inside a pass.
		var cutTexture = mesh.hasCuts() ? mc.getTextureManager().getTexture(cutStone(mc)).getTextureView() : null;

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "blendermc liminal level", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightmapSampler);
			pass.setVertexBuffer(0, mesh.vertices);
			pass.setIndexBuffer(mesh.indices, VertexFormat.IndexType.INT);
			for (int i = 0; i < mesh.ranges.size(); i++) {
				LiminalMeshBaker.Range range = mesh.ranges.get(i);
				if (range.decal() && (i == 0 || !mesh.ranges.get(i - 1).decal())) {
					pass.setPipeline(DECAL_PIPELINE);
					pass.setUniform("DynamicTransforms", transforms);
					pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightmapSampler);
				}
				pass.bindTexture("Sampler0", mesh.materialTextures[range.material()].getTextureView(), nearest);
				pass.drawIndexed(0, range.firstIndex(), range.indexCount(), 1);
			}
			if (cutTexture != null) {
				pass.setPipeline(PIPELINE);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightmapSampler);
				pass.bindTexture("Sampler0", cutTexture, nearest);
				for (LiminalGpuMesh.CutBuffer cut : mesh.cutSections.values()) {
					pass.setVertexBuffer(0, cut.buffer());
					pass.draw(0, cut.vertexCount());
				}
			}
		}
	}
}
