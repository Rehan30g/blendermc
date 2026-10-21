package dev.blendermc.liminal;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Server side of the breakable level: fills the liminal dimension with LEVEL mesh_cell blocks for the active level,
 * and keeps the per-level set of broken cells (saved in blendermc.json, mirrored to clients).
 */
public final class LiminalWorld {
	/** No neighbour shape updates, and no "removed" side effects (so the fill itself never counts as breaking). */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
	private static boolean filling;

	private LiminalWorld() {
	}

	/**
	 * Puts a LEVEL mesh_cell in every cell of {@code name} that is not broken, removes LEVEL cells that no longer belong
	 * (another level, or geometry gone after a reload) inside {@code previous} and the level's bounds. A cell holding
	 * anything else the player placed (a block or a placed piece) is left alone and counted as broken.
	 * Returns how many cells were (re)placed.
	 */
	public static int fill(ServerLevel world, String name, @Nullable AABB previous) {
		return fill(world, name, previous, null, true);
	}

	/**
	 * {@link #fill(ServerLevel, String, AABB)} limited to {@code dirty} (null = the whole level), for live edits from
	 * Blender; {@code solid} false skips the stone pass (live levels are hollow, it only clears stray stone).
	 */
	public static int fill(ServerLevel world, String name, @Nullable AABB previous, @Nullable AABB dirty, boolean solid) {
		filling = true;
		try {
			return fillCells(world, name, previous, dirty, solid);
		} finally {
			filling = false;
		}
	}

	/** True while {@link #fill} changes blocks (their removal is not a break). */
	public static boolean filling() {
		return filling;
	}

	private static int fillCells(ServerLevel world, String name, @Nullable AABB previous, @Nullable AABB dirty, boolean solid) {
		LiminalCells cells = LiminalLevels.cells(name);
		if (cells == null) {
			return 0;
		}
		MinecraftServer server = world.getServer();
		LongOpenHashSet broken = LiminalCommands.broken(server, name);
		LongSet owned = cells.cells();
		AABB area = cells.mesh().bounds().inflate(1.0);
		if (previous != null) {
			area = area.minmax(previous.inflate(1.0));
		}
		AABB only = dirty == null ? null : dirty.inflate(1.0);
		if (only != null) {
			area = only;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(area.minX); x <= Mth.floor(area.maxX); x++) {
			for (int z = Mth.floor(area.minZ); z <= Mth.floor(area.maxZ); z++) {
				for (int y = Math.max(world.getMinY(), Mth.floor(area.minY)); y <= Math.min(world.getMaxY(), Mth.floor(area.maxY)); y++) {
					pos.set(x, y, z);
					if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell && cell.isLevelCell()
						&& (!name.equals(cell.levelName()) || !owned.contains(pos.asLong()) || broken.contains(pos.asLong()))) {
						world.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
					}
				}
			}
		}
		int placed = 0;
		LongOpenHashSet replaced = LiminalCommands.replaced(server, name);
		boolean replacedChanged = false;
		for (long key : owned) {
			if (broken.contains(key)) {
				continue;
			}
			pos.set(key);
			if (pos.getY() < world.getMinY() || pos.getY() > world.getMaxY()
				|| only != null && !only.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (state.is(LiminalBlocks.MESH_CELL)) {
				if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell && cell.isLevelCell() && name.equals(cell.levelName())) {
					// Geometry may have changed on reload: keep the light-occlusion flags right.
					BlockState wanted = MeshCellBlock.withGeometry(state, cells.geometry(key));
					if (wanted != state) {
						world.setBlock(pos, wanted, FLAGS);
					}
					continue;
				}
				if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell && cell.piece() != null) {
					replacedChanged |= replaced.add(key);
					continue;
				}
			} else if (!isFree(state)) {
				// A player's block took the cell: leave it, the geometry stays visible and comes back when the block goes.
				replacedChanged |= replaced.add(key);
				continue;
			}
			replacedChanged |= replaced.remove(key);
			world.setBlock(pos, MeshCellBlock.withGeometry(LiminalBlocks.MESH_CELL.defaultBlockState(), cells.geometry(key)), FLAGS);
			if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell) {
				cell.setLevelCell(name);
				placed++;
			}
		}
		if (replacedChanged) {
			LiminalCommands.saveReplaced(name);
		}
		if (solid) {
			fillSolid(world, name, cells);
		}
		broadcast(server, name);
		return placed;
	}

	/**
	 * Puts stone into every cell inside the level (see {@link LiminalCells.Volume}), once per geometry: a later enter
	 * does not refill stone the player dug out. Only air (or a light block) is replaced; nothing is recorded. Blocks go
	 * straight into the chunk (light and clients are updated, neighbours are not), which keeps big levels fast.
	 */
	private static void fillSolid(ServerLevel world, String name, LiminalCells cells) {
		MinecraftServer server = world.getServer();
		LiminalMesh mesh = cells.mesh();
		// Bumped when the fill changes (2: liminal_stone instead of stone; 3: clear stone left by other levels).
		// A level whose .glb sets stone_fill false stays hollow: its stone is cleared and none is placed.
		boolean stoneFill = LiminalLevels.sky(name).stoneFill();
		long signature = 31L * mesh.triangleCount() + mesh.bounds().hashCode() + 3 + (stoneFill ? 0 : 1000);
		if (LiminalCommands.solidFilled(server, name) == signature) {
			return;
		}
		long start = System.nanoTime();
		LiminalCells.Volume volume = cells.volume();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Filler stone inside these bounds that is not this level's (left by a level that shared the region before
		// levels had their own regions) would z-fight with this level's floors: clear it.
		int cleared = 0;
		net.minecraft.world.phys.AABB box = mesh.bounds();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
				for (int y = Math.max(world.getMinY(), Mth.floor(box.minY) - 2); y <= Math.min(world.getMaxY(), Mth.floor(box.maxY)); y++) {
					pos.set(x, y, z);
					net.minecraft.world.level.chunk.LevelChunk chunk = world.getChunkAt(pos);
					if (chunk.getBlockState(pos).is(LiminalBlocks.LIMINAL_STONE) && !(stoneFill && volume.solidCells().contains(pos.asLong()))
						&& chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0) != null) {
						world.getChunkSource().blockChanged(pos);
						cleared++;
					}
				}
			}
		}
		int placed = 0;
		for (long key : stoneFill ? volume.solidCells() : it.unimi.dsi.fastutil.longs.LongSet.of()) {
			pos.set(key);
			if (pos.getY() < world.getMinY() || pos.getY() > world.getMaxY()) {
				continue;
			}
			net.minecraft.world.level.chunk.LevelChunk chunk = world.getChunkAt(pos);
			BlockState state = chunk.getBlockState(pos);
			// Earlier fills used plain stone in these same cells: convert it.
			if (!state.isAir() && !state.is(Blocks.LIGHT) && !state.is(Blocks.STONE) && !state.is(LiminalBlocks.LIMINAL_STONE)) {
				continue;
			}
			BlockState stone = LiminalBlocks.LIMINAL_STONE.defaultBlockState();
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				stone = stone.setValue(LiminalStoneBlock.INSIDE[d.ordinal()], volume.inside(BlockPos.offset(key, d)));
			}
			if (state != stone && chunk.setBlockState(pos, stone, 0) != null) {
				world.getChunkSource().blockChanged(pos);
				placed++;
			}
		}
		LiminalCommands.setSolidFilled(server, name, signature);
		LiminalLevels.LOGGER.info("liminal level '{}': {} solid cells, {} stone placed, {} foreign stone cleared in {} ms", name,
			cells.volume().solidCells().size(), placed, cleared, (System.nanoTime() - start) / 1_000_000);
	}

	/** Air, a light block or something replaceable (fire, water, grass): a level cell may go there. */
	private static boolean isFree(BlockState state) {
		return state.isAir() || state.is(Blocks.LIGHT) || state.canBeReplaced() && !state.is(LiminalBlocks.MESH_CELL);
	}

	private record Removal(String level, BlockPos pos) {
	}

	private static final java.util.List<Removal> REMOVALS = new java.util.ArrayList<>();
	private static int ticks;

	/**
	 * A level cell was removed by something other than the fill or a player mining it. Decided at the end of the tick:
	 * a block in its place (a player placed into the cell, /setblock stone) makes it "replaced", nothing there (TNT,
	 * /setblock air) makes it broken.
	 */
	static void removed(String level, BlockPos pos) {
		REMOVALS.add(new Removal(level, pos.immutable()));
	}

	private static final LongOpenHashSet FREED = new LongOpenHashSet();

	/**
	 * A block changed in the liminal dimension (server). If it freed a "replaced" cell of the active level, the level
	 * cell goes back at the end of this tick.
	 */
	public static void blockChanged(ServerLevel world, BlockPos pos, BlockState state) {
		if (filling || !isFree(state)) {
			return;
		}
		String level = LiminalLevels.levelAt(pos);
		if (level == null) {
			return;
		}
		LongOpenHashSet replaced = LiminalCommands.replaced(world.getServer(), level);
		if (replaced.contains(pos.asLong())) {
			FREED.add(pos.asLong());
		}
	}

	/** Server tick: settles removals, puts level cells back where a replacing block has gone (scan once a second as a safety net). */
	public static void tick(MinecraftServer server) {
		ServerLevel world = server.getLevel(LiminalLevels.DIMENSION);
		boolean freed = !FREED.isEmpty();
		FREED.clear();
		if (!REMOVALS.isEmpty()) {
			for (Removal removal : List.copyOf(REMOVALS)) {
				ServerLevel where = world;
				if (where == null) {
					continue;
				}
				BlockState state = where.getBlockState(removal.pos);
				if (state.is(LiminalBlocks.MESH_CELL) && world.getBlockEntity(removal.pos) instanceof MeshCellBlockEntity cell && cell.isLevelCell()) {
					continue;
				}
				if (isFree(state)) {
					markBroken(server, removal.level, removal.pos, true);
				} else {
					LongOpenHashSet replaced = LiminalCommands.replaced(server, removal.level);
					if (replaced.add(removal.pos.asLong())) {
						LiminalCommands.saveReplaced(removal.level);
					}
				}
			}
			REMOVALS.clear();
		}
		if (++ticks % 20 != 0 && !freed) {
			return;
		}
		if (world != null) {
			for (String level : LiminalLevels.slots().keySet()) {
				putBackReplaced(server, world, level);
			}
		}
	}

	/** Puts level cells of {@code name} back where the block that replaced them has gone. */
	private static void putBackReplaced(MinecraftServer server, ServerLevel world, String name) {
		LongOpenHashSet replaced = LiminalCommands.replaced(server, name);
		if (replaced.isEmpty()) {
			return;
		}
		CellSource cells = LiminalLevels.cellSource(name);
		LongOpenHashSet broken = LiminalCommands.broken(server, name);
		boolean changed = false;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (long key : replaced.toLongArray()) {
			pos.set(key);
			if (!world.isLoaded(pos)) {
				continue;
			}
			BlockState state = world.getBlockState(pos);
			if (!isFree(state)) {
				continue;
			}
			replaced.remove(key);
			changed = true;
			if (cells == null || !cells.hasCell(key) || broken.contains(key)) {
				continue;
			}
			filling = true;
			try {
				world.setBlock(pos, MeshCellBlock.withGeometry(LiminalBlocks.MESH_CELL.defaultBlockState(), cells.geometry(key)), FLAGS);
				if (world.getBlockEntity(pos) instanceof MeshCellBlockEntity cell) {
					cell.setLevelCell(name);
				}
			} finally {
				filling = false;
			}
		}
		if (changed) {
			LiminalCommands.saveReplaced(name);
		}
	}

	/**
	 * Forgets every broken cell of {@code name} and fills them again (cells with player blocks stay broken), and puts
	 * back the stone the player dug out of the level's volume.
	 */
	public static int restore(ServerLevel world, String name) {
		LiminalCommands.broken(world.getServer(), name).clear();
		LiminalCommands.saveBroken(name);
		// Forget that the stone was filled, so the fill below refills every dug-out solid cell (player blocks stay).
		LiminalCommands.setSolidFilled(world.getServer(), name, 0L);
		return fill(world, name, null);
	}

	/** Records cell {@code pos} of level {@code name} as broken (or whole), saves and tells every client. */
	public static void markBroken(MinecraftServer server, @Nullable String name, BlockPos pos, boolean broken) {
		if (name == null) {
			return;
		}
		LongOpenHashSet set = LiminalCommands.broken(server, name);
		boolean changed = broken ? set.add(pos.asLong()) : set.remove(pos.asLong());
		if (!changed) {
			return;
		}
		LiminalCommands.saveBroken(name);
		dev.blendermc.live.LiveLink.cellBroken(name, pos, broken);
		LiminalBrokenDeltaPayload payload = new LiminalBrokenDeltaPayload(name, pos.asLong(), broken);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Sends the full broken set of {@code name} to one player. */
	public static void send(ServerPlayer player, String name) {
		ServerPlayNetworking.send(player, new LiminalBrokenPayload(name, LiminalCommands.broken(player.level().getServer(), name).toLongArray()));
	}

	/** Sends the full broken set of {@code name} to everyone. */
	public static void broadcast(MinecraftServer server, String name) {
		LiminalBrokenPayload payload = new LiminalBrokenPayload(name, LiminalCommands.broken(server, name).toLongArray());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
		dev.blendermc.live.LiveLink.holesChanged(server, name);
	}
}
