package dev.blendermc.liminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** An ordinary block ({@link #contained()}) sitting off the grid: its cube starts at this cell's corner + {@link #offset()}. */
public class OffsetBlockEntity extends BlockEntity {
	private BlockState contained = Blocks.STONE.defaultBlockState();
	private Vec3 offset = Vec3.ZERO;
	private @Nullable VoxelShape shape;

	public OffsetBlockEntity(BlockPos pos, BlockState state) {
		super(LiminalBlocks.OFFSET_BLOCK_ENTITY, pos, state);
	}

	public BlockState contained() {
		return this.contained;
	}

	/** Where the cube's min corner is, relative to this cell's min corner; each component in (-1, 1). */
	public Vec3 offset() {
		return this.offset;
	}

	public void set(BlockState contained, Vec3 offset) {
		this.contained = contained;
		this.offset = new Vec3(clamp(offset.x), clamp(offset.y), clamp(offset.z));
		this.shape = null;
		this.setChanged();
		if (this.level != null) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), this.level.isClientSide() ? 8 : 3);
		}
	}

	/** The full cube moved by the offset (block-local; reaches into neighbour cells). */
	public VoxelShape shape() {
		if (this.shape == null) {
			this.shape = Shapes.block().move(this.offset.x, this.offset.y, this.offset.z);
		}
		return this.shape;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("contained", BlockState.CODEC, this.contained);
		output.store("offset", Vec3.CODEC, this.offset);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.contained = input.read("contained", BlockState.CODEC).orElse(Blocks.STONE.defaultBlockState());
		Vec3 o = input.read("offset", Vec3.CODEC).orElse(Vec3.ZERO);
		this.offset = new Vec3(clamp(o.x), clamp(o.y), clamp(o.z));
		this.shape = null;
		if (this.level != null && this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 8);
		}
	}

	private static double clamp(double v) {
		return Math.max(-0.999, Math.min(0.999, v));
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
