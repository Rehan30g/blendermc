package dev.echoliminal.liminal.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.echoliminal.EchoLiminal;
import dev.echoliminal.liminal.LiminalDay;
import dev.echoliminal.liminal.light.LightChange;
import dev.echoliminal.liminal.light.LightSources;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a drawn level's baked light current when the placed world changes: pending {@link LightChange}s from
 * {@link LiminalWorldLights} are re-lit on a background thread (only the vertices they can reach), one job at a
 * time; the render thread then patches the CPU vertex copy and the changed byte runs of the GPU buffer (bounded per
 * tick), swaps the sources into the {@link LiminalLightField} and re-meshes the vanilla sections it touched
 * (bounded per tick). Owns the {@link LiminalMeshBaker.Baked} memory once handed over. Render thread only.
 */
final class LiminalLightUpdater {
	/** Vertices patched and uploaded per tick. */
	private static final int VERTICES_PER_TICK = 20000;
	private static final int SECTIONS_PER_TICK = 32;
	/** Neighbouring changed vertices closer than this share one upload. */
	private static final int RUN_GAP = 48;
	private static final int MAX_RUNS = 300;
	/** A day-cycle pass over all vertices is spread over this many ticks; a new one starts after this much day time passed. */
	private static final int DAY_PASS_TICKS = 20;
	private static final float DAY_PASS_DELTA = 40.0F;
	/** Horizontal radius (in sections) re-meshed when the field's clock steps. */
	private static final int DAY_REMESH_RADIUS = 10;

	private record Result(LiminalMeshBaker.Patch patch, LightSources sources, List<LightChange> changes, long nanos) {
	}

	private final LiminalMeshBaker.Baked baked;
	private final GpuBuffer vertices;
	private final LiminalWorldLights world;
	private @Nullable CompletableFuture<Result> job;
	private @Nullable Result applying;
	private int cursor;
	private boolean closed;
	private final LongArrayList sections = new LongArrayList();
	private boolean remeshAll;
	/** Day cycle: the next vertex to rewrite in the running cycle (>= vertex count: idle), the state it is written for, and the last finished one. */
	private int dayCursor = Integer.MAX_VALUE;
	private LiminalDay.@Nullable Day dayTarget, dayDone;

	LiminalLightUpdater(LiminalMeshBaker.Baked baked, GpuBuffer vertices, LiminalWorldLights world) {
		this.baked = baked;
		this.vertices = vertices;
		this.world = world;
	}

	void tick(Minecraft mc, ClientLevel level) {
		if (this.closed) {
			return;
		}
		if (this.baked.sunRaw != null) {
			this.tickDay(mc, level);
		}
		if (this.job != null && this.job.isDone()) {
			CompletableFuture<Result> done = this.job;
			this.job = null;
			try {
				this.applying = done.join();
				this.cursor = 0;
			} catch (RuntimeException e) {
				EchoLiminal.LOGGER.error("Liminal light update failed", e);
			}
		}
		if (this.applying != null) {
			LiminalMeshBaker.Patch patch = this.applying.patch();
			int end = Math.min(patch.vertices().length, this.cursor + VERTICES_PER_TICK);
			this.baked.apply(patch, this.cursor, end);
			this.upload(patch.vertices(), this.cursor, end);
			this.cursor = end;
			if (this.cursor >= patch.vertices().length) {
				Result result = this.applying;
				this.applying = null;
				LongOpenHashSet touched = LiminalLightField.update(result.sources(), result.changes());
				if (touched == null) {
					this.remeshAll = true;
				} else {
					this.sections.addAll(touched);
				}
				EchoLiminal.LOGGER.debug("Liminal light update: {} changes, {} vertices relit in {} ms, {} sections to re-mesh", result.changes().size(),
					patch.vertices().length, result.nanos() / 1_000_000, this.sections.size());
			}
		}
		if (this.job == null && this.applying == null && this.world.hasChanges()) {
			List<LightChange> changes = this.world.takeChanges();
			LightSources sources = this.world.snapshot();
			LiminalMeshBaker.Baked baked = this.baked;
			this.job = CompletableFuture.supplyAsync(() -> {
				long start = System.nanoTime();
				LiminalMeshBaker.Patch patch = baked.recompute(sources, changes);
				return new Result(patch, sources, changes, System.nanoTime() - start);
			});
		}
		if (this.remeshAll) {
			this.remeshAll = false;
			this.sections.clear();
			LiminalRenderer.remesh(mc, this.baked.mesh.bounds());
		}
		for (int n = 0; n < SECTIONS_PER_TICK && !this.sections.isEmpty(); n++) {
			long s = this.sections.removeLong(this.sections.size() - 1);
			mc.levelRenderer.setSectionDirty(SectionPos.x(s), SectionPos.y(s), SectionPos.z(s));
		}
	}

	/**
	 * Day cycle: steps the light field's clock (re-meshing the vanilla sections around the player when it does), and rewrites
	 * the mesh vertices for the current time, 1/{@value #DAY_PASS_TICKS} of them per tick.
	 */
	private void tickDay(Minecraft mc, ClientLevel level) {
		LiminalDay.Day now = LiminalDayClock.now();
		if (LiminalLightField.tickDay(now, level.getGameTime()) && mc.player != null) {
			this.queueSections(mc);
		}
		int n = this.baked.vertexCount;
		if (this.dayCursor >= n) {
			if (now == null) {
				return;
			}
			LiminalDay.Day last = this.dayDone;
			if (last != null) {
				float delta = Math.abs(now.time - last.time);
				if (Math.min(delta, LiminalDay.DAY_TICKS - delta) < DAY_PASS_DELTA) {
					return;
				}
			}
			this.dayTarget = now;
			this.baked.dayState = now;
			this.dayCursor = 0;
		}
		int end = Math.min(n, this.dayCursor + (n + DAY_PASS_TICKS - 1) / DAY_PASS_TICKS);
		this.baked.cycle(this.dayCursor, end, this.dayTarget);
		int stride = LiminalMeshBaker.STRIDE;
		ByteBuffer part = this.baked.vertexData.duplicate().order(ByteOrder.nativeOrder());
		part.position(this.dayCursor * stride).limit(end * stride);
		RenderSystem.getDevice().createCommandEncoder().writeToBuffer(this.vertices.slice((long) this.dayCursor * stride, (long) (end - this.dayCursor) * stride),
			part.slice().order(ByteOrder.nativeOrder()));
		this.dayCursor = end;
		if (end >= n) {
			this.dayDone = this.dayTarget;
		}
	}

	/** Queues the render sections around the player (nearest last, so they pop first) for a re-mesh. */
	private void queueSections(Minecraft mc) {
		var bounds = this.baked.mesh.bounds();
		int px = SectionPos.blockToSectionCoord(mc.player.getBlockX()), pz = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
		int radius = Math.min(DAY_REMESH_RADIUS, mc.options.getEffectiveRenderDistance());
		int y0 = SectionPos.blockToSectionCoord((int) Math.floor(bounds.minY)) - 1, y1 = SectionPos.blockToSectionCoord((int) Math.ceil(bounds.maxY)) + 1;
		this.sections.clear();
		java.util.List<long[]> all = new java.util.ArrayList<>();
		for (int x = px - radius; x <= px + radius; x++) {
			for (int z = pz - radius; z <= pz + radius; z++) {
				for (int y = y0; y <= y1; y++) {
					all.add(new long[] {SectionPos.asLong(x, y, z), (long) (x - px) * (x - px) + (long) (z - pz) * (z - pz)});
				}
			}
		}
		all.sort((a, b) -> Long.compare(b[1], a[1]));
		for (long[] e : all) {
			this.sections.add(e[0]);
		}
	}

	/** Uploads the vertices {@code vs[from..to)} (ascending), merged into runs. */
	private void upload(int[] vs, int from, int to) {
		if (from >= to) {
			return;
		}
		int stride = LiminalMeshBaker.STRIDE;
		ByteBuffer data = this.baked.vertexData;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		int runs = 1;
		for (int i = from + 1; i < to; i++) {
			if (vs[i] - vs[i - 1] > RUN_GAP) {
				runs++;
			}
		}
		if (runs > MAX_RUNS) {
			ByteBuffer whole = data.duplicate().order(ByteOrder.nativeOrder());
			whole.position(0).limit(this.baked.vertexCount * stride);
			encoder.writeToBuffer(this.vertices.slice(), whole);
			return;
		}
		int i = from;
		while (i < to) {
			int first = vs[i];
			int last = first;
			while (i < to && vs[i] - last <= RUN_GAP) {
				last = vs[i];
				i++;
			}
			ByteBuffer part = data.duplicate().order(ByteOrder.nativeOrder());
			part.position(first * stride).limit((last + 1) * stride);
			encoder.writeToBuffer(this.vertices.slice((long) first * stride, (long) (last - first + 1) * stride), part.slice().order(ByteOrder.nativeOrder()));
		}
	}

	/** Stops updating; frees the vertex copy once a running re-bake no longer reads it. */
	void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		CompletableFuture<Result> running = this.job;
		this.job = null;
		if (running != null && !running.isDone()) {
			running.whenComplete((r, e) -> this.baked.free());
		} else {
			this.baked.free();
		}
	}
}
