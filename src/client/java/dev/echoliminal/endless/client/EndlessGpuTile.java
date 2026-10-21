package dev.echoliminal.endless.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.echoliminal.liminal.LiminalBlocks;
import dev.echoliminal.liminal.LiminalBroken;
import dev.echoliminal.liminal.MeshCellBlockEntity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lwjgl.system.MemoryUtil;

/**
 * A tile on the GPU: static vertex buffer, and for level of detail 0 an index buffer whose triangles of cells that no
 * longer exist in the world (mined, exploded) are zeroed in place, exactly like the hand made levels do. Render thread only.
 */
final class EndlessGpuTile implements AutoCloseable {
	final Tile tile;
	final EndlessBuilder.Built built;
	final double anchorX, anchorY, anchorZ;
	final AABB bounds;
	final List<EndlessBuilder.Range> ranges;
	final GpuBuffer vertices;
	final GpuBuffer indices;
	final boolean indexed;
	/** Render tick of the last time this tile was wanted or used as a stand-in. */
	long lastUsed;

	// Level of detail 0 only.
	private final int[] source;
	private final Long2ObjectOpenHashMap<int[]> cellSlots;
	private final Long2ObjectOpenHashMap<LongArrayList> cellsByChunk;
	private final LongOpenHashSet hidden = new LongOpenHashSet();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
	private boolean closed;

	EndlessGpuTile(EndlessBuilder.Built built, ClientLevel level) {
		this.tile = built.tile;
		this.built = built;
		this.anchorX = built.anchorX;
		this.anchorY = built.anchorY;
		this.anchorZ = built.anchorZ;
		this.bounds = new AABB(built.minX, built.minY, built.minZ, built.maxX, built.maxY, built.maxZ);
		this.ranges = built.ranges;
		this.indexed = built.indices != null;
		String name = "echoliminal endless " + this.tile;
		if (built.vertices != null && built.vertexCount > 0) {
			this.vertices = RenderSystem.getDevice().createBuffer(() -> name + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, built.vertices);
		} else {
			this.vertices = null;
		}
		if (this.indexed && this.vertices != null) {
			this.source = built.indices;
			this.cellSlots = new Long2ObjectOpenHashMap<>();
			this.cellsByChunk = new Long2ObjectOpenHashMap<>();
			Long2ObjectOpenHashMap<IntArrayList> slots = new Long2ObjectOpenHashMap<>();
			for (int t = 0; t < built.triangleCell.length; t++) {
				slots.computeIfAbsent(built.triangleCell[t], k -> new IntArrayList()).add(t);
			}
			for (Long2ObjectMap.Entry<IntArrayList> e : slots.long2ObjectEntrySet()) {
				long cell = e.getLongKey();
				this.cellSlots.put(cell, e.getValue().toIntArray());
				this.cellsByChunk.computeIfAbsent(ChunkPos.asLong(BlockPos.getX(cell) >> 4, BlockPos.getZ(cell) >> 4), k -> new LongArrayList()).add(cell);
			}
			// Cells whose block is already gone are left out of the very first upload.
			LongIterator it = this.cellSlots.keySet().iterator();
			while (it.hasNext()) {
				long cell = it.nextLong();
				if (!isVisible(level, cell)) {
					this.hidden.add(cell);
				}
			}
			ByteBuffer data = MemoryUtil.memAlloc(Math.max(4, this.source.length * 4)).order(ByteOrder.nativeOrder());
			try {
				for (int t = 0; t < this.source.length / 3; t++) {
					boolean show = !this.hidden.contains(built.triangleCell[t]);
					for (int k = 0; k < 3; k++) {
						data.putInt((t * 3 + k) * 4, show ? this.source[t * 3 + k] : 0);
					}
				}
				data.limit(Math.max(4, this.source.length * 4));
				this.indices = RenderSystem.getDevice().createBuffer(() -> name + " indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, data);
			} finally {
				MemoryUtil.memFree(data);
			}
		} else {
			this.source = null;
			this.cellSlots = null;
			this.cellsByChunk = null;
			this.indices = null;
		}
		built.free();
	}

	boolean isEmpty() {
		return this.vertices == null;
	}

	/** The cell is drawn while its block is an endless level cell, or a player block replaced it (and it is not broken). Unloaded: pristine. */
	static boolean isVisible(ClientLevel level, long cell) {
		BlockPos pos = BlockPos.of(cell);
		if (!level.hasChunkAt(pos)) {
			return !LiminalBroken.isBroken(EndlessClient.LEVEL_NAME, cell);
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		if (state.is(LiminalBlocks.MESH_CELL)) {
			return !(level.getBlockEntity(pos) instanceof MeshCellBlockEntity entity) || !entity.isLevelCell() || EndlessClient.LEVEL_NAME.equals(entity.levelName());
		}
		return !LiminalBroken.isBroken(EndlessClient.LEVEL_NAME, cell);
	}

	void cellChanged(long cell) {
		if (this.cellSlots != null && this.cellSlots.containsKey(cell)) {
			this.dirty.add(cell);
		}
	}

	void chunkChanged(int chunkX, int chunkZ) {
		if (this.cellsByChunk != null && this.cellsByChunk.containsKey(ChunkPos.asLong(chunkX, chunkZ))) {
			this.dirtyChunks.add(ChunkPos.asLong(chunkX, chunkZ));
		}
	}

	/** Re-checks changed cells and patches their triangles into the index buffer. */
	void update(ClientLevel level) {
		if (this.closed || this.cellSlots == null || this.indices == null || this.dirty.isEmpty() && this.dirtyChunks.isEmpty()) {
			return;
		}
		LongArrayList flips = new LongArrayList();
		LongIterator it = this.dirtyChunks.iterator();
		while (it.hasNext()) {
			LongArrayList cells = this.cellsByChunk.get(it.nextLong());
			if (cells != null) {
				for (int i = 0; i < cells.size(); i++) {
					this.dirty.add(cells.getLong(i));
				}
			}
		}
		this.dirtyChunks.clear();
		it = this.dirty.iterator();
		while (it.hasNext()) {
			long cell = it.nextLong();
			boolean show = isVisible(level, cell);
			if (show ? this.hidden.remove(cell) : this.hidden.add(cell)) {
				flips.add(cell);
			}
		}
		this.dirty.clear();
		if (!flips.isEmpty()) {
			patch(flips);
		}
	}

	private void patch(LongArrayList cells) {
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		ByteBuffer tmp = MemoryUtil.memAlloc(4096 * 12).order(ByteOrder.nativeOrder());
		try {
			for (int c = 0; c < cells.size(); c++) {
				long cell = cells.getLong(c);
				int[] slots = this.cellSlots.get(cell);
				if (slots == null) {
					continue;
				}
				boolean show = !this.hidden.contains(cell);
				int i = 0;
				while (i < slots.length) {
					int j = i;
					while (j + 1 < slots.length && slots[j + 1] == slots[j] + 1 && j + 1 - i < 4095) {
						j++;
					}
					int n = j - i + 1;
					for (int k = 0; k < n; k++) {
						for (int e = 0; e < 3; e++) {
							tmp.putInt((k * 3 + e) * 4, show ? this.source[(slots[i] + k) * 3 + e] : 0);
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

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		if (this.vertices != null) {
			this.vertices.close();
		}
		if (this.indices != null) {
			this.indices.close();
		}
		this.built.free();
	}
}
