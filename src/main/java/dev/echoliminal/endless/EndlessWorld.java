package dev.echoliminal.endless;

import dev.echoliminal.liminal.LiminalBlocks;
import dev.echoliminal.liminal.LiminalCommands;
import dev.echoliminal.liminal.LiminalWorld;
import dev.echoliminal.liminal.MeshCellBlock;
import dev.echoliminal.liminal.MeshCellBlockEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Server-side upkeep of the endless dimension beyond chunk generation. */
public final class EndlessWorld {
	private EndlessWorld() {
	}

	/** Puts back every broken cell in loaded chunks that is empty now (cells holding a player's block stay broken). Returns how many. */
	public static int restore(ServerLevel world) {
		EndlessCells cells = EndlessCells.get();
		if (cells == null) {
			return 0;
		}
		LongOpenHashSet broken = LiminalCommands.broken(world.getServer(), Endless.LEVEL);
		int placed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long key : broken.toLongArray()) {
			pos.set(key);
			if (!world.isLoaded(pos)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (!state.isAir() && !state.is(Blocks.LIGHT)) {
				continue;
			}
			LiminalWorld.markBroken(world.getServer(), Endless.LEVEL, pos, false);
			if ((cells.generator().classify(pos.getX(), pos.getY(), pos.getZ()) & 3) != 1) {
				continue;
			}
			BlockState cell = MeshCellBlock.withGeometry(LiminalBlocks.MESH_CELL.defaultBlockState(), cells.geometry(key));
			world.setBlock(pos, cell, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
			if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity be) {
				be.setLevelCell(Endless.LEVEL);
				placed++;
			}
		}
		return placed;
	}
}
