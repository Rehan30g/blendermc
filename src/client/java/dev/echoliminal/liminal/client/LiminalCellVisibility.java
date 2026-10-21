package dev.echoliminal.liminal.client;

import dev.echoliminal.liminal.CutFaces;
import dev.echoliminal.liminal.LiminalBlocks;
import dev.echoliminal.liminal.LiminalBroken;
import dev.echoliminal.liminal.LiminalCells;
import dev.echoliminal.liminal.LiminalLevels;
import dev.echoliminal.liminal.MeshCellBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Which cells of the drawn level are visible, following the world: a cell is drawn while its block is that level's
 * LEVEL mesh_cell, or while a player block replaced it (non-air, not a mesh_cell, cell not broken). Mined,
 * exploded or air cells (and unloaded chunks) are hidden. Block changes and chunk loads mark cells dirty; they are
 * re-checked once per tick. Render thread only.
 *
 * <p>Also tracks the cut faces: a visible cell is solid behind its surface (under the sand), and nothing else draws
 * that solid part, so where a neighbour is open (dug out, air) the cell's cross-section on that face is drawn as a
 * stone wall ({@link #cuts()}), for floors, ceilings, slopes and walls alike (see {@link #computeCuts}).
 */
final class LiminalCellVisibility {
	private final String levelName;
	private final LongOpenHashSet visible = new LongOpenHashSet();
	/** Cells per chunk (ChunkPos long). */
	private final Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet allCells = new LongOpenHashSet();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final LiminalCells cells;
	private final boolean stoneFill;
	/** A live (Blender) level: broken cells are plain holes, never capped with cut faces. */
	private final boolean hollow;
	/** Cut faces per cell: quads of 12 floats (4 corners, world coordinates) + the face's Direction ordinal. */
	private final Long2ObjectOpenHashMap<float[]> cuts = new Long2ObjectOpenHashMap<>();
	/** Cells with cut faces per render section ({@link SectionPos#asLong}), and the sections whose cuts changed. */
	private final Long2ObjectOpenHashMap<LongOpenHashSet> cutCells = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirtyCutSections = new LongOpenHashSet();
	/** Cells whose visibility flipped since {@link #takeFlipped}. */
	private final LongArrayList flipped = new LongArrayList();
	private boolean allFlipped;

	LiminalCellVisibility(LiminalCells cells, String levelName) {
		this.cells = cells;
		this.levelName = levelName;
		this.stoneFill = LiminalLevels.sky(levelName).stoneFill();
		this.hollow = dev.echoliminal.live.LiveScene.isLive(levelName);
		LongIterator it = cells.cells().iterator();
		while (it.hasNext()) {
			long cell = it.nextLong();
			this.allCells.add(cell);
			this.byChunk.computeIfAbsent(ChunkPos.asLong(SectionPos.blockToSectionCoord(BlockPos.getX(cell)), SectionPos.blockToSectionCoord(BlockPos.getZ(cell))),
				k -> new LongArrayList()).add(cell);
		}
		this.dirty.addAll(this.allCells);
	}

	String levelName() {
		return this.levelName;
	}

	boolean isVisible(long cell) {
		return this.visible.contains(cell);
	}

	void blockChanged(BlockPos pos) {
		long cell = pos.asLong();
		if (this.allCells.contains(cell)) {
			this.dirty.add(cell);
		}
		for (Direction d : Direction.values()) {
			long next = BlockPos.offset(cell, d);
			if (this.allCells.contains(next)) {
				this.dirty.add(next);
			}
		}
	}

	/** All current cut faces (see the class comment), keyed by cell. */
	Long2ObjectOpenHashMap<float[]> cuts() {
		return this.cuts;
	}

	/** The cut faces of the cells in one render section, as quad arrays (see {@link #cuts()}). */
	java.util.List<float[]> cutsOfSection(long section) {
		LongOpenHashSet cells = this.cutCells.get(section);
		java.util.List<float[]> out = new java.util.ArrayList<>(cells == null ? 0 : cells.size());
		if (cells != null) {
			LongIterator it = cells.iterator();
			while (it.hasNext()) {
				float[] quads = this.cuts.get(it.nextLong());
				if (quads != null) {
					out.add(quads);
				}
			}
		}
		return out;
	}

	/** Sections whose cut faces changed since the last call; clears them. */
	LongArrayList takeDirtyCutSections() {
		LongArrayList out = new LongArrayList(this.dirtyCutSections);
		this.dirtyCutSections.clear();
		return out;
	}

	/** Every section that has cut faces (for relighting them all). */
	java.util.Collection<Long> cutSections() {
		return this.cutCells.keySet();
	}

	/** Cells whose visibility flipped since the last call, or null when too many / unknown (rebuild everything). Clears. */
	LongArrayList takeFlipped() {
		if (this.allFlipped) {
			this.allFlipped = false;
			this.flipped.clear();
			return null;
		}
		LongArrayList out = new LongArrayList(this.flipped);
		this.flipped.clear();
		return out;
	}

	/** A cell's broken state changed (from {@link dev.echoliminal.liminal.LiminalBroken}). */
	void brokenChanged(long cell) {
		this.blockChanged(BlockPos.of(cell));
	}

	void chunkChanged(int chunkX, int chunkZ) {
		// Neighbouring chunks too: their border cells read this chunk's blocks to decide which faces are open.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				LongArrayList cells = this.byChunk.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
				if (cells != null) {
					this.dirty.addAll(cells);
				}
			}
		}
	}

	void markAllDirty() {
		this.dirty.addAll(this.allCells);
		this.allFlipped = true;
	}

	/** Re-checks dirty cells; true if any cell changed visibility. */
	boolean update(ClientLevel level) {
		if (this.dirty.isEmpty()) {
			return false;
		}
		boolean changed = false;
		LongOpenHashSet recut = new LongOpenHashSet(this.dirty);
		LongIterator it = this.dirty.iterator();
		while (it.hasNext()) {
			long cell = it.nextLong();
			for (Direction d : Direction.values()) {
				long next = BlockPos.offset(cell, d);
				if (this.allCells.contains(next)) {
					recut.add(next);
				}
			}
			boolean now = this.compute(level, cell);
			if (now ? this.visible.add(cell) : this.visible.remove(cell)) {
				changed = true;
				if (this.flipped.size() > 512) {
					this.allFlipped = true;
				} else {
					this.flipped.add(cell);
				}
				for (Direction d : Direction.values()) {
					long next = BlockPos.offset(cell, d);
					if (this.allCells.contains(next)) {
						recut.add(next);
					}
				}
			}
		}
		this.dirty.clear();
		LongIterator cutIt = recut.iterator();
		while (cutIt.hasNext()) {
			long cell = cutIt.nextLong();
			float[] quads = this.visible.contains(cell) ? this.computeCuts(level, cell) : null;
			float[] old = quads == null ? this.cuts.remove(cell) : this.cuts.put(cell, quads);
			if (!java.util.Arrays.equals(old, quads)) {
				long section = SectionPos.asLong(BlockPos.getX(cell) >> 4, BlockPos.getY(cell) >> 4, BlockPos.getZ(cell) >> 4);
				this.dirtyCutSections.add(section);
				if (quads == null) {
					LongOpenHashSet set = this.cutCells.get(section);
					if (set != null && set.remove(cell) && set.isEmpty()) {
						this.cutCells.remove(section);
					}
				} else {
					this.cutCells.computeIfAbsent(section, k -> new LongOpenHashSet()).add(cell);
				}
			}
		}
		return changed;
	}

	/**
	 * True if {@code pos} was dug: a cell of this level (it has triangles, or it is inside the level volume) whose
	 * block is now air or non-occluding because it was broken or its stone was mined. Original air (outside the
	 * volume, open rooms), undug hollow cells and unloaded chunks are never open.
	 */
	private boolean open(ClientLevel level, long pos) {
		this.cursor.set(pos);
		if (!level.hasChunkAt(this.cursor)) {
			return false;
		}
		BlockState state = level.getBlockState(this.cursor);
		if (state.canOcclude()) {
			return false;
		}
		if (this.allCells.contains(pos)) {
			return !this.visible.contains(pos) && (!state.isAir() || LiminalBroken.isBroken(this.levelName, pos));
		}
		if (!this.cells.volume().inside(pos)) {
			return false;
		}
		return this.stoneFill || !state.isAir() || LiminalBroken.isBroken(this.levelName, pos);
	}

	/** The cut faces of one visible cell (see {@link CutFaces}); null if none. */
	private float @Nullable [] computeCuts(ClientLevel level, long cell) {
		if (this.hollow) {
			return null;
		}
		return CutFaces.compute(this.cells, cell, d -> this.open(level, BlockPos.offset(cell, d)));
	}


	private boolean compute(ClientLevel level, long cell) {
		this.cursor.set(cell);
		if (!level.hasChunkAt(this.cursor)) {
			return false;
		}
		BlockState state = level.getBlockState(this.cursor);
		if (state.isAir()) {
			return false;
		}
		if (state.is(LiminalBlocks.MESH_CELL)) {
			return level.getBlockEntity(this.cursor) instanceof MeshCellBlockEntity entity && entity.isLevelCell()
				&& this.levelName.equals(entity.levelName());
		}
		return !LiminalBroken.isBroken(this.levelName, cell);
	}
}
