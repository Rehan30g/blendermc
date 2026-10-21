package dev.blendermc.liminal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * One block cell of liminal geometry. Outline, collision and ray shapes are the exact triangles
 * ({@link MeshTriangleShape}); faces with a surface on them support torches, carpets, fire and the like; light is
 * blocked along the axes the cell covers completely ({@link #OCCLUDE_X}/Y/Z, kept in the state because the light
 * engine has no position). Never drawn as a block: the level renderer draws level cells, a block entity renderer draws placed pieces. {@link #LIGHT} lets a cell stand in for a lamp's light block.
 */
public class MeshCellBlock extends Block implements EntityBlock {
	public static final IntegerProperty LIGHT = BlockStateProperties.LEVEL;
	public static final BooleanProperty OCCLUDE_X = BooleanProperty.create("occlude_x");
	public static final BooleanProperty OCCLUDE_Y = BooleanProperty.create("occlude_y");
	public static final BooleanProperty OCCLUDE_Z = BooleanProperty.create("occlude_z");
	private static final VoxelShape[] OCCLUSION = new VoxelShape[8];

	static {
		double t = 1.0 / 16.0;
		for (int i = 0; i < 8; i++) {
			VoxelShape shape = Shapes.empty();
			if ((i & 1) != 0) {
				shape = Shapes.or(shape, Shapes.box(0, 0, 0, t, 1, 1), Shapes.box(1 - t, 0, 0, 1, 1, 1));
			}
			if ((i & 2) != 0) {
				shape = Shapes.or(shape, Shapes.box(0, 0, 0, 1, t, 1), Shapes.box(0, 1 - t, 0, 1, 1, 1));
			}
			if ((i & 4) != 0) {
				shape = Shapes.or(shape, Shapes.box(0, 0, 0, 1, 1, t), Shapes.box(0, 0, 1 - t, 1, 1, 1));
			}
			OCCLUSION[i] = shape.optimize();
		}
	}

	public MeshCellBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIGHT, 0).setValue(OCCLUDE_X, false).setValue(OCCLUDE_Y, false)
			.setValue(OCCLUDE_Z, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIGHT, OCCLUDE_X, OCCLUDE_Y, OCCLUDE_Z);
	}

	/** {@code state} with the occlusion flags of {@code geometry}. */
	public static BlockState withGeometry(BlockState state, CellGeometry geometry) {
		return state.setValue(OCCLUDE_X, geometry.occludesX()).setValue(OCCLUDE_Y, geometry.occludesY()).setValue(OCCLUDE_Z, geometry.occludesZ());
	}

	/** Brings the block state's occlusion flags in line with the block entity's geometry (server). */
	public static void refreshState(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.is(LiminalBlocks.MESH_CELL) && level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell) {
			BlockState wanted = withGeometry(state, cell.geometry());
			if (wanted != state) {
				level.setBlock(pos, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MeshCellBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	private static CellGeometry geometry(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell ? cell.geometry() : CellGeometry.EMPTY;
	}

	/** The triangles of the mesh_cell at {@code pos} in world coordinates (9 floats each), or null if there is none. */
	public static float @Nullable [] worldTriangles(BlockGetter level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell)) {
			return null;
		}
		float[] local = cell.geometry().triangles();
		float[] out = new float[local.length];
		for (int i = 0; i < local.length; i += 3) {
			out[i] = local[i] + pos.getX();
			out[i + 1] = local[i + 1] + pos.getY();
			out[i + 2] = local[i + 2] + pos.getZ();
		}
		return out;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return geometry(level, pos).outline();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return geometry(level, pos).collision();
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return geometry(level, pos).collision();
	}

	@Override
	protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
		return geometry(level, pos).supportShape();
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return OCCLUSION[(state.getValue(OCCLUDE_X) ? 1 : 0) | (state.getValue(OCCLUDE_Y) ? 2 : 0) | (state.getValue(OCCLUDE_Z) ? 4 : 0)];
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return !state.getValue(OCCLUDE_Y);
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	/**
	 * A level cell that is only a low floor-like surface, or is mostly empty (trim, a thin edge of a wall), can be taken
	 * over by a placed block (which then sits in the
	 * ground instead of floating a cell above it); the cell counts as "replaced": its geometry stays visible and comes
	 * back as a cell when the block is removed. Placed pieces never; nor when the player
	 * sneaks with a mesh_piece (to stack pieces on purpose).
	 */
	@Override
	protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
		if (context.getItemInHand().is(LiminalBlocks.MESH_PIECE) && context.getPlayer() != null && context.getPlayer().isSecondaryUseActive()) {
			return false;
		}
		return context.getLevel().getBlockEntity(context.getClickedPos()) instanceof MeshCellBlockEntity cell && cell.isLevelCell()
			&& (cell.geometry().sparseGround() || cell.geometry().mostlyEmpty());
	}

	/** Placing a piece: it turns with the placer, so it faces them the way it faced whoever broke it off. */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		MeshPiece piece = stack.get(LiminalBlocks.MESH_PIECE_COMPONENT);
		if (piece != null && level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell) {
			int facing = placer == null ? piece.facing() : placer.getDirection().get2DDataValue();
			cell.setPiece(piece.rotated(facing - piece.facing()));
			if (!level.isClientSide()) {
				refreshState(level, pos);
			}
		}
	}

	/** Breaking a level cell (any game mode) marks it broken for good. */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell && cell.isLevelCell()) {
			LiminalWorld.markBroken(server.getServer(), cell.levelName(), pos, true);
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	/** Survival break: drops the cell as a mesh_piece (creative gets nothing; explosions drop nothing). */
	@Override
	public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
		super.playerDestroy(level, player, pos, state, blockEntity, tool);
		if (!level.isClientSide() && blockEntity instanceof MeshCellBlockEntity cell) {
			MeshPiece piece = cell.toPiece(player.getDirection().get2DDataValue());
			if (piece != null && !piece.triangles().isEmpty()) {
				popResource(level, pos, LiminalBlocks.pieceStack(piece));
			}
		}
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		if (level.getBlockEntity(pos) instanceof MeshCellBlockEntity cell) {
			MeshPiece piece = cell.toPiece(0);
			if (piece != null) {
				return LiminalBlocks.pieceStack(piece);
			}
		}
		return ItemStack.EMPTY;
	}
}
