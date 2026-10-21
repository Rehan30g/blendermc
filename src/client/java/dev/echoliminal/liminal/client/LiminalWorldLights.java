package dev.echoliminal.liminal.client;

import dev.echoliminal.liminal.LiminalBlocks;
import dev.echoliminal.liminal.light.LightChange;
import dev.echoliminal.liminal.light.LightSources;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the placed world contributes to the drawn level's lighting, tracked on the render thread: block light
 * sources (anything with a light emission, except the invisible light blocks that stand in for the mesh's own
 * emissive faces) and vanilla blocks that cast shadows (collision shape; the level's own cells and stone fill are
 * left out, the mesh already is that geometry). Scanned per chunk section (cheap palette check first), kept up to
 * date from block changes and chunk loads, and turned into {@link LightChange}s for the incremental re-bake.
 */
final class LiminalWorldLights {
	/** More pending changes than this and everything is re-lit instead. */
	private static final int MAX_CHANGES = 40;
	private static final int CHUNKS_PER_TICK = 3;

	private final double minX, minY, minZ, maxX, maxY, maxZ;
	private final LongOpenHashSet proxies;
	private final Long2ObjectOpenHashMap<LightSources.ChunkData> chunks = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
	private final List<LightChange> changes = new ArrayList<>();
	private boolean everything;
	private LightSources snapshot = LightSources.EMPTY;
	private boolean stale = true;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	/** {@code bounds}: the level's mesh bounds; sources up to a light range beyond count. */
	LiminalWorldLights(AABB bounds, LongOpenHashSet proxies) {
		double margin = 20.0;
		this.minX = bounds.minX - margin;
		this.minY = bounds.minY - margin;
		this.minZ = bounds.minZ - margin;
		this.maxX = bounds.maxX + margin;
		this.maxY = bounds.maxY + margin;
		this.maxZ = bounds.maxZ + margin;
		this.proxies = proxies;
	}

	private boolean inRange(int x, int y, int z) {
		return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
	}

	/** Scans every loaded chunk in range, without reporting changes (the first bake reads the snapshot). */
	void scanAll(ClientLevel level) {
		for (int cx = Mth.floor(this.minX) >> 4; cx <= Mth.floor(this.maxX) >> 4; cx++) {
			for (int cz = Mth.floor(this.minZ) >> 4; cz <= Mth.floor(this.maxZ) >> 4; cz++) {
				LightSources.ChunkData data = this.scan(level, cx, cz);
				if (data != null && !data.isEmpty()) {
					this.chunks.put(ChunkPos.asLong(cx, cz), data);
				}
			}
		}
		this.stale = true;
	}

	/** A chunk loaded or unloaded: rescan it in the next ticks. */
	void chunkChanged(int cx, int cz) {
		if (cx * 16 + 15 >= this.minX && cx * 16 <= this.maxX && cz * 16 + 15 >= this.minZ && cz * 16 <= this.maxZ) {
			this.dirtyChunks.add(ChunkPos.asLong(cx, cz));
		}
	}

	/** Rescans a few dirty chunks per tick. */
	void tick(ClientLevel level) {
		if (this.dirtyChunks.isEmpty()) {
			return;
		}
		LongIterator it = this.dirtyChunks.iterator();
		for (int n = 0; n < CHUNKS_PER_TICK && it.hasNext(); n++) {
			long chunk = it.nextLong();
			it.remove();
			LightSources.ChunkData fresh = this.scan(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
			LightSources.ChunkData old = fresh == null ? this.chunks.get(chunk) : this.chunks.get(chunk);
			if (fresh == null) {
				// Unloaded: what it held stops counting.
				if (old != null) {
					this.chunks.remove(chunk);
					this.diff(old, new LightSources.ChunkData());
				}
				continue;
			}
			this.diff(old == null ? new LightSources.ChunkData() : old, fresh);
			if (fresh.isEmpty()) {
				this.chunks.remove(chunk);
			} else {
				this.chunks.put(chunk, fresh);
			}
		}
	}

	/** A single block changed (render thread, from the client level mixin). */
	void blockChanged(ClientLevel level, BlockPos pos) {
		if (!this.inRange(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		long key = pos.asLong();
		long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
		if (this.dirtyChunks.contains(chunk)) {
			return;
		}
		LightSources.ChunkData data = this.chunks.get(chunk);
		int oldLight = data == null ? 0 : data.lights.get(key);
		boolean oldFull = data != null && data.full.contains(key);
		float[] oldPartial = data == null ? null : data.partial.get(key);
		LightSources.ChunkData into = data == null ? new LightSources.ChunkData() : data;
		into.lights.remove(key);
		into.full.remove(key);
		into.partial.remove(key);
		this.classify(level, level.getBlockState(pos), pos, into);
		int newLight = into.lights.get(key);
		boolean newFull = into.full.contains(key);
		float[] newPartial = into.partial.get(key);
		if (data == null && !into.isEmpty()) {
			this.chunks.put(chunk, into);
		}
		if (oldLight != newLight) {
			this.add(LightChange.light(key, Math.max(oldLight, newLight)));
		}
		if (oldFull != newFull || !java.util.Arrays.equals(oldPartial, newPartial)) {
			this.add(LightChange.block(key));
		}
	}

	private void add(LightChange change) {
		this.stale = true;
		if (this.changes.size() >= MAX_CHANGES) {
			this.everything = true;
		} else {
			this.changes.add(change);
		}
	}

	/** Changes between two versions of a chunk's share. */
	private void diff(LightSources.ChunkData old, LightSources.ChunkData fresh) {
		for (Long2ByteMap.Entry e : old.lights.long2ByteEntrySet()) {
			if (fresh.lights.get(e.getLongKey()) != e.getByteValue()) {
				this.add(LightChange.light(e.getLongKey(), Math.max(e.getByteValue(), fresh.lights.get(e.getLongKey()))));
			}
		}
		for (Long2ByteMap.Entry e : fresh.lights.long2ByteEntrySet()) {
			if (!old.lights.containsKey(e.getLongKey())) {
				this.add(LightChange.light(e.getLongKey(), e.getByteValue()));
			}
		}
		LongIterator it = old.full.iterator();
		while (it.hasNext()) {
			long k = it.nextLong();
			if (!fresh.full.contains(k)) {
				this.add(LightChange.block(k));
			}
		}
		it = fresh.full.iterator();
		while (it.hasNext()) {
			long k = it.nextLong();
			if (!old.full.contains(k)) {
				this.add(LightChange.block(k));
			}
		}
		for (Long2ObjectMap.Entry<float[]> e : old.partial.long2ObjectEntrySet()) {
			if (!java.util.Arrays.equals(e.getValue(), fresh.partial.get(e.getLongKey()))) {
				this.add(LightChange.block(e.getLongKey()));
			}
		}
		for (Long2ObjectMap.Entry<float[]> e : fresh.partial.long2ObjectEntrySet()) {
			if (!old.partial.containsKey(e.getLongKey())) {
				this.add(LightChange.block(e.getLongKey()));
			}
		}
	}

	/** A level cell was hidden or shown: its triangles started or stopped occluding. */
	void cellChanged(long cell) {
		this.add(LightChange.block(cell));
	}

	/** Re-light everything (many cells changed at once). */
	void everythingChanged() {
		this.stale = true;
		this.everything = true;
	}

	boolean hasChanges() {
		return this.everything || !this.changes.isEmpty();
	}

	/** The pending changes (a single huge one when too many piled up); clears them. */
	List<LightChange> takeChanges() {
		List<LightChange> out;
		if (this.everything) {
			double cx = (this.minX + this.maxX) / 2, cy = (this.minY + this.maxY) / 2, cz = (this.minZ + this.maxZ) / 2;
			out = List.of(new LightChange(cx, cy, cz, 1.0E6F, true));
		} else {
			out = new ArrayList<>(this.changes);
		}
		this.changes.clear();
		this.everything = false;
		return out;
	}

	/** An immutable view of the current sources. */
	LightSources snapshot() {
		if (this.stale) {
			this.snapshot = new LightSources(this.chunks.values());
			this.stale = false;
		}
		return this.snapshot;
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static boolean occluderState(BlockState state) {
		return !state.isAir() && state.canOcclude() && !state.is(LiminalBlocks.MESH_CELL) && !state.is(LiminalBlocks.OFFSET_BLOCK)
			&& !state.is(LiminalBlocks.LIMINAL_STONE) && !state.is(Blocks.LIGHT);
	}

	private static boolean candidate(BlockState state) {
		return state.getLightEmission() > 0 || occluderState(state);
	}

	private LightSources.ChunkData scan(ClientLevel level, int cx, int cz) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) {
			return null;
		}
		LightSources.ChunkData data = new LightSources.ChunkData();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			int sectionY = chunk.getMinSectionY() + i;
			if (sectionY * 16 + 15 < this.minY || sectionY * 16 > this.maxY) {
				continue;
			}
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(LiminalWorldLights::candidate)) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!state.isAir() && candidate(state)) {
							int wx = cx * 16 + x, wy = sectionY * 16 + y, wz = cz * 16 + z;
							if (this.inRange(wx, wy, wz)) {
								this.classify(level, state, this.cursor.set(wx, wy, wz), data);
							}
						}
					}
				}
			}
		}
		return data;
	}

	private void classify(ClientLevel level, BlockState state, BlockPos pos, LightSources.ChunkData into) {
		long key = pos.asLong();
		int emission = state.getLightEmission();
		if (emission > 0 && !this.proxies.contains(key)) {
			into.lights.put(key, (byte) emission);
		}
		if (occluderState(state)) {
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (shape.isEmpty()) {
				return;
			}
			AABB b = shape.bounds();
			if (b.minX <= 0.01 && b.minY <= 0.01 && b.minZ <= 0.01 && b.maxX >= 0.99 && b.maxY >= 0.99 && b.maxZ >= 0.99) {
				into.full.add(key);
			} else {
				into.partial.put(key, new float[] {(float) (pos.getX() + b.minX), (float) (pos.getY() + b.minY), (float) (pos.getZ() + b.minZ),
					(float) (pos.getX() + b.maxX), (float) (pos.getY() + b.maxY), (float) (pos.getZ() + b.maxZ)});
			}
		}
	}
}
