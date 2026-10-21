package dev.blendermc.liminal;

import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * What a mesh_cell holds: either a LEVEL cell (geometry from the loaded level {@link #levelName()} at this position)
 * or a PLACED piece (its own {@link MeshPiece}). Both are drawn by the block model through {@link #getRenderData()}.
 */
public class MeshCellBlockEntity extends BlockEntity implements RenderDataBlockEntity {
	/** What the block model draws: a level cell ({@code levelName}) or a placed piece ({@code piece}). */
	public record RenderData(@Nullable String levelName, @Nullable MeshPiece piece) {
	}

	private @Nullable String levelName;
	private @Nullable MeshPiece piece;
	private @Nullable CellGeometry pieceGeometry;
	private @Nullable RenderData renderData;

	public MeshCellBlockEntity(BlockPos pos, BlockState state) {
		super(LiminalBlocks.MESH_CELL_ENTITY, pos, state);
	}

	public boolean isLevelCell() {
		return this.piece == null && this.levelName != null;
	}

	public @Nullable String levelName() {
		return this.levelName;
	}

	public @Nullable MeshPiece piece() {
		return this.piece;
	}

	/** Makes this a LEVEL cell of {@code level}. */
	public void setLevelCell(String level) {
		this.levelName = level;
		this.piece = null;
		this.changed();
	}

	/** Makes this a LEVEL cell without telling anyone (chunk generation, before the chunk is in a level). */
	public void initLevelCell(String level) {
		this.levelName = level;
		this.piece = null;
		this.pieceGeometry = null;
		this.renderData = null;
	}

	/** Makes this a PLACED piece. */
	public void setPiece(MeshPiece piece) {
		this.piece = piece;
		this.levelName = null;
		this.changed();
		if (this.level instanceof net.minecraft.server.level.ServerLevel server) {
			dev.blendermc.live.LiveLink.piecePlaced(server, this.worldPosition, piece);
		}
	}

	private void changed() {
		this.pieceGeometry = null;
		this.renderData = null;
		this.setChanged();
		if (this.level != null) {
			// Server: send the new data; client: re-mesh the section so the model shows it.
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), this.level.isClientSide() ? 8 : 3);
		}
	}

	/** The cell's shapes, support faces and light occlusion. */
	public CellGeometry geometry() {
		if (this.piece != null) {
			if (this.pieceGeometry == null) {
				this.pieceGeometry = this.piece.geometry();
			}
			return this.pieceGeometry;
		}
		CellSource cells = LiminalLevels.cellSource(this.levelName);
		return cells == null ? CellGeometry.EMPTY : cells.geometry(this.worldPosition.asLong());
	}

	/** The item this cell drops: its piece, or the level cell's triangles as a piece; null if the level is missing. */
	public @Nullable MeshPiece toPiece(int facing) {
		if (this.piece != null) {
			return this.piece;
		}
		CellSource cells = LiminalLevels.cellSource(this.levelName);
		return cells == null || !cells.hasCell(this.worldPosition.asLong()) ? null : cells.piece(this.worldPosition.asLong(), facing);
	}

	@Override
	public RenderData getRenderData() {
		if (this.renderData == null) {
			this.renderData = new RenderData(this.piece == null ? this.levelName : null, this.piece);
		}
		return this.renderData;
	}

	/**
	 * Any removal of a level cell that is not the fill itself (TNT, /setblock, /fill, a block placed into a sparse cell,
	 * other mods) is settled at the end of the tick: broken if the cell is left empty, "replaced" if a block took it.
	 * Chunk unloading does not come through here.
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (this.level instanceof ServerLevel && this.isLevelCell() && !LiminalWorld.filling()) {
			LiminalWorld.removed(this.levelName, pos);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.piece != null) {
			output.store("piece", MeshPiece.CODEC, this.piece);
		} else if (this.levelName != null) {
			output.putString("level", this.levelName);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.piece = input.read("piece", MeshPiece.CODEC).orElse(null);
		this.levelName = this.piece == null ? input.getString("level").orElse(null) : null;
		this.pieceGeometry = null;
		this.renderData = null;
		if (this.level != null && this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 8);
		}
	}

	@Override
	public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		return this.saveWithoutMetadata(provider);
	}
}
