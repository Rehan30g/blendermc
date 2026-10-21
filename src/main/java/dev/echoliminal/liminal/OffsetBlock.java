package dev.echoliminal.liminal;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A plain building block placed exactly on mesh geometry, off the block grid. The cell
 * holds an {@link OffsetBlockEntity} with the real block state and the offset; shapes are that block's cube moved by
 * the offset; mining speed, tool, drops and break effects are the contained block's. Drawn by a block entity renderer.
 */
public class OffsetBlock extends Block implements EntityBlock {
	public OffsetBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new OffsetBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	private static @Nullable OffsetBlockEntity entity(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof OffsetBlockEntity e ? e : null;
	}

	private static BlockState contained(BlockGetter level, BlockPos pos) {
		OffsetBlockEntity e = entity(level, pos);
		return e == null ? Blocks.STONE.defaultBlockState() : e.contained();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		OffsetBlockEntity e = entity(level, pos);
		return e == null ? Shapes.block() : e.shape();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.getShape(state, level, pos, context);
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
		return contained(level, pos).getDestroyProgress(player, level, pos);
	}

	/** The contained block's break particles and sound. */
	@Override
	protected void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
		level.levelEvent(player, 2001, pos, Block.getId(contained(level, pos)));
	}

	/** Drops nothing without the contained block's tool (like the real block); otherwise its loot. */
	@Override
	public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
		BlockState inner = blockEntity instanceof OffsetBlockEntity e ? e.contained() : Blocks.STONE.defaultBlockState();
		if (player.hasCorrectToolForDrops(inner)) {
			super.playerDestroy(level, player, pos, state, blockEntity, tool);
		} else {
			player.causeFoodExhaustion(0.005F);
		}
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		BlockState inner = blockEntity instanceof OffsetBlockEntity e ? e.contained() : Blocks.STONE.defaultBlockState();
		return inner.getDrops(builder);
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		return new ItemStack(contained(level, pos).getBlock());
	}
}
