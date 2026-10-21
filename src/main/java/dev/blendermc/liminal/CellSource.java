package dev.blendermc.liminal;

import org.jspecify.annotations.Nullable;

/**
 * Where the geometry of LEVEL cells comes from (a loaded level, {@link LiminalCells}). Cells are keyed by {@link net.minecraft.core.BlockPos#asLong}.
 */
public interface CellSource {
	/** Whether the level owns triangles in this cell. */
	boolean hasCell(long cell);

	/** The cell's exact shapes, support faces and light occlusion; {@link CellGeometry#EMPTY} if it owns nothing. */
	CellGeometry geometry(long cell);

	/** The cell's triangles as a piece (for the item a broken cell drops); null if the cell owns nothing. */
	@Nullable MeshPiece piece(long cell, int facing);
}
