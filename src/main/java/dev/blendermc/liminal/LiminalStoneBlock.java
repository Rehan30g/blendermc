package dev.blendermc.liminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The stone that fills a level's inside (stone in every way that matters to the player). It stays invisible until the
 * mesh above or beside it is dug away: a face is drawn only towards a neighbour cell that was inside the level when
 * it was filled ({@link #INSIDE}, kept in the state because face culling has no position) and that no longer holds
 * level geometry or more of this stone. So faces never show through the mesh surface, never z-fight with it, and
 * never stick out of the level into the open air.
 */
public class LiminalStoneBlock extends Block {
	/** Per direction (Direction ordinal): whether the neighbour cell that way was inside the level. */
	public static final BooleanProperty[] INSIDE = new BooleanProperty[6];

	static {
		for (Direction d : Direction.values()) {
			INSIDE[d.ordinal()] = BooleanProperty.create("inside_" + d.getSerializedName());
		}
	}

	public LiminalStoneBlock(Properties properties) {
		super(properties);
		BlockState state = this.stateDefinition.any();
		for (BooleanProperty p : INSIDE) {
			state = state.setValue(p, false);
		}
		this.registerDefaultState(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(INSIDE);
	}

	@Override
	protected boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
		if (!state.getValue(INSIDE[direction.ordinal()])) {
			return true;
		}
		return neighbor.is(this) || neighbor.is(LiminalBlocks.MESH_CELL) || neighbor.is(LiminalBlocks.OFFSET_BLOCK)
			|| super.skipRendering(state, neighbor, direction);
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		return new ItemStack(Blocks.STONE);
	}
}
