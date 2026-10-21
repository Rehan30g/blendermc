package dev.blendermc.liminal.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.blendermc.liminal.LiminalMesh;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU side of one level: a static vertex buffer (built once, patched in place when light changes), an index buffer
 * in the baked triangle order whose hidden cells' triangles are zeroed in place (a cell appearing or vanishing writes
 * only its own few triangles), one cut-face buffer per 16 block render section (a change rebuilds one small buffer),
 * and one texture per material (shared by content through {@link LiminalTextures}). Render thread only.
 */
final class LiminalGpuMesh implements AutoCloseable {
	/** Cut faces of one render section. */
	record CutBuffer(GpuBuffer buffer, int vertexCount) {
	}

	final LiminalMesh mesh;
	final LiminalMeshBaker.Baked baked;
	final double anchorX, anchorY, anchorZ;
	final GpuBuffer vertices;
	GpuBuffer indices;
	/** Always the baked ranges: hidden triangles are degenerate instead of removed. */
	final List<LiminalMeshBaker.Range> ranges;
	/** Stone cut faces where the level was dug open (see {@link LiminalCellVisibility}), by render section. */
	final Long2ObjectOpenHashMap<CutBuffer> cutSections = new Long2ObjectOpenHashMap<>();
	/** The light field version the cut buffers were lit with. */
	private int cutLightVersion;
	/** Per material: the texture to bind as Sampler0. */
	final AbstractTexture[] materialTextures;
	private final String name;
	private final LiminalLightUpdater updater;
	/** CPU copy of the index buffer as uploaded (hidden triangles zeroed). */
	private final int[] liveIndices;
	/** Per cell: the triangle slots (index positions / 3) of its triangles. */
	private final Long2ObjectOpenHashMap<int[]> cellSlots = new Long2ObjectOpenHashMap<>();
	private boolean closed;

	LiminalGpuMesh(LiminalMeshBaker.Baked baked, LongPredicate visible, LiminalWorldLights world) {
		this.baked = baked;
		this.mesh = baked.mesh;
		this.anchorX = baked.anchorX;
		this.anchorY = baked.anchorY;
		this.anchorZ = baked.anchorZ;
		this.name = sanitize(this.mesh.name());
		this.ranges = baked.ranges;
		this.liveIndices = baked.indices.clone();
		Long2ObjectOpenHashMap<IntArrayList> slots = new Long2ObjectOpenHashMap<>();
		for (int t = 0; t < baked.triangleCell.length; t++) {
			slots.computeIfAbsent(baked.triangleCell[t], k -> new IntArrayList()).add(t);
		}
		for (Long2ObjectMap.Entry<IntArrayList> e : slots.long2ObjectEntrySet()) {
			this.cellSlots.put(e.getLongKey(), e.getValue().toIntArray());
		}
		GpuDevice device = RenderSystem.getDevice();
		this.vertices = device.createBuffer(() -> "blendermc liminal " + this.name + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, baked.vertexData);
		this.setVisible(visible);

		List<LiminalMesh.Material> materials = this.mesh.materials();
		this.materialTextures = new AbstractTexture[Math.max(1, materials.size())];
		for (int m = 0; m < this.materialTextures.length; m++) {
			byte[] png = m < materials.size() ? materials.get(m).texturePng() : null;
			this.materialTextures[m] = Minecraft.getInstance().getTextureManager().getTexture(LiminalTextures.get(png));
		}
		this.updater = new LiminalLightUpdater(baked, this.vertices, world);
	}

	/** Rebuilds the whole index buffer: triangles of cells for which {@code visible} is false are zeroed. */
	void setVisible(LongPredicate visible) {
		if (this.closed) {
			return;
		}
		int[] src = this.baked.indices;
		java.nio.ByteBuffer data = MemoryUtil.memAlloc(Math.max(4, src.length * 4)).order(java.nio.ByteOrder.nativeOrder());
		try {
			for (int t = 0; t < src.length / 3; t++) {
				boolean show = visible.test(this.baked.triangleCell[t]);
				for (int k = 0; k < 3; k++) {
					int v = show ? src[t * 3 + k] : 0;
					this.liveIndices[t * 3 + k] = v;
					data.putInt((t * 3 + k) * 4, v);
				}
			}
			data.limit(Math.max(4, src.length * 4));
			GpuBuffer old = this.indices;
			this.indices = RenderSystem.getDevice().createBuffer(() -> "blendermc liminal " + this.name + " indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, data);
			if (old != null) {
				old.close();
			}
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/** Shows or hides the triangles of just these cells, patching the index buffer in place. */
	void updateVisible(LongArrayList cells, LongPredicate visible) {
		if (this.closed || cells.isEmpty()) {
			return;
		}
		int[] src = this.baked.indices;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		java.nio.ByteBuffer tmp = MemoryUtil.memAlloc(4096 * 12).order(java.nio.ByteOrder.nativeOrder());
		try {
			for (int c = 0; c < cells.size(); c++) {
				long cell = cells.getLong(c);
				int[] slots = this.cellSlots.get(cell);
				if (slots == null) {
					continue;
				}
				boolean show = visible.test(cell);
				// Slots are ascending; write contiguous runs.
				int i = 0;
				while (i < slots.length) {
					int j = i;
					while (j + 1 < slots.length && slots[j + 1] == slots[j] + 1 && j + 1 - i < 4095) {
						j++;
					}
					int n = j - i + 1;
					for (int k = 0; k < n; k++) {
						for (int e = 0; e < 3; e++) {
							int v = show ? src[(slots[i] + k) * 3 + e] : 0;
							this.liveIndices[(slots[i] + k) * 3 + e] = v;
							tmp.putInt((k * 3 + e) * 4, v);
						}
					}
					tmp.position(0).limit(n * 12);
					encoder.writeToBuffer(this.indices.slice((long) slots[i] * 12, (long) n * 12), tmp);
					tmp.clear();
					i = j + 1;
				}
			}
		} finally {
			MemoryUtil.memFree(tmp);
		}
	}

	boolean hasCuts() {
		return !this.cutSections.isEmpty();
	}

	/** Marks the cut buffers as lit with the current light field. */
	void cutsLit() {
		this.cutLightVersion = LiminalLightField.version();
	}

	/**
	 * Rebuilds the cut-face buffer of one render section: two triangles per quad, stone textured in world space, lit
	 * by the light field in front of the face, shaded like vanilla faces with the sun factor.
	 */
	void rebuildCuts(long section, Iterable<float[]> cutsPerCell) {
		if (this.closed) {
			return;
		}
		int quads = 0;
		for (float[] q : cutsPerCell) {
			quads += q.length / 13;
		}
		CutBuffer old = quads == 0 ? this.cutSections.remove(section) : this.cutSections.get(section);
		if (quads == 0) {
			if (old != null) {
				old.buffer().close();
			}
			return;
		}
		int stride = LiminalMeshBaker.STRIDE;
		java.nio.ByteBuffer data = MemoryUtil.memAlloc(quads * 6 * stride).order(java.nio.ByteOrder.nativeOrder());
		int v = 0;
		BlockPos.MutableBlockPos front = new BlockPos.MutableBlockPos();
		try {
			for (float[] q : cutsPerCell) {
				for (int at = 0; at + 12 < q.length; at += 13) {
					net.minecraft.core.Direction d = net.minecraft.core.Direction.values()[(int) q[at + 12]];
					float nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
					float cx = (q[at] + q[at + 3] + q[at + 6] + q[at + 9]) / 4, cy = (q[at + 1] + q[at + 4] + q[at + 7] + q[at + 10]) / 4,
						cz = (q[at + 2] + q[at + 5] + q[at + 8] + q[at + 11]) / 4;
					front.set(net.minecraft.util.Mth.floor(cx + nx * 0.5F), net.minecraft.util.Mth.floor(cy + ny * 0.5F), net.minecraft.util.Mth.floor(cz + nz * 0.5F));
					int packed = LiminalLightField.packed(front, 0);
					short light = (short) (net.minecraft.client.renderer.LightTexture.block(packed) * 16);
					short sky = (short) (net.minecraft.client.renderer.LightTexture.sky(packed) * 16);
					float shade = LiminalMeshBaker.shade(nx, ny, nz) * this.baked.sky.sunShade(nx, ny, nz);
					if (this.baked.sky.unlit()) {
						// Full bright but dimmed to the room's average, so holes do not glow.
						light = LiminalMeshBaker.FULL;
						sky = LiminalMeshBaker.FULL;
						shade = LiminalUnlit.luminance(this.mesh);
					}
					byte grey = LiminalMeshBaker.unit(shade);
					int[] order = {0, 1, 2, 0, 2, 3};
					for (int k : order) {
						float x = q[at + k * 3], y = q[at + k * 3 + 1], z = q[at + k * 3 + 2];
						float u = d.getAxis() == net.minecraft.core.Direction.Axis.X ? z : x;
						float w = d.getAxis() == net.minecraft.core.Direction.Axis.Y ? z : -y;
						int o = v * stride;
						data.putFloat(o, (float) (x - this.anchorX));
						data.putFloat(o + 4, (float) (y - this.anchorY));
						data.putFloat(o + 8, (float) (z - this.anchorZ));
						data.put(o + LiminalMeshBaker.OFFSET_COLOR, grey);
						data.put(o + LiminalMeshBaker.OFFSET_COLOR + 1, grey);
						data.put(o + LiminalMeshBaker.OFFSET_COLOR + 2, grey);
						data.put(o + LiminalMeshBaker.OFFSET_COLOR + 3, (byte) -1);
						data.putFloat(o + LiminalMeshBaker.OFFSET_UV0, u);
						data.putFloat(o + LiminalMeshBaker.OFFSET_UV0 + 4, w);
						data.putShort(o + LiminalMeshBaker.OFFSET_UV2, light);
						data.putShort(o + LiminalMeshBaker.OFFSET_UV2 + 2, sky);
						data.put(o + LiminalMeshBaker.OFFSET_NORMAL, (byte) (nx * 127));
						data.put(o + LiminalMeshBaker.OFFSET_NORMAL + 1, (byte) (ny * 127));
						data.put(o + LiminalMeshBaker.OFFSET_NORMAL + 2, (byte) (nz * 127));
						data.put(o + LiminalMeshBaker.OFFSET_NORMAL + 3, (byte) 0);
						v++;
					}
				}
			}
			data.limit(v * stride);
			CutBuffer made = new CutBuffer(RenderSystem.getDevice().createBuffer(() -> "blendermc liminal " + this.name + " cuts", GpuBuffer.USAGE_VERTEX, data), v);
			this.cutSections.put(section, made);
			if (old != null) {
				old.buffer().close();
			}
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/** True if the light field changed since the cut buffer was lit. */
	boolean cutLightStale(ClientLevel level) {
		return !this.cutSections.isEmpty() && LiminalLightField.version() != this.cutLightVersion;
	}

	/** Applies finished light re-bakes and starts new ones (see {@link LiminalLightUpdater}). */
	void tickLight(ClientLevel level) {
		if (!this.closed) {
			this.updater.tick(Minecraft.getInstance(), level);
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.vertices.close();
		if (this.indices != null) {
			this.indices.close();
		}
		for (CutBuffer cut : this.cutSections.values()) {
			cut.buffer().close();
		}
		this.cutSections.clear();
		this.updater.close();
	}

	private static String sanitize(String name) {
		String s = (name == null ? "level" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
		return s.isEmpty() ? "level" : s;
	}
}
