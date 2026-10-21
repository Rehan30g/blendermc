package dev.echoliminal.endless;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.echoliminal.endless.gen.EndlessGenerator;
import dev.echoliminal.liminal.LiminalBlocks;
import dev.echoliminal.liminal.LiminalStoneBlock;
import dev.echoliminal.liminal.MeshCellBlock;
import dev.echoliminal.liminal.MeshCellBlockEntity;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Chunk generator of the endless dimension: every cell the generator says has triangles becomes a LEVEL mesh_cell
 * ({@link Endless#LEVEL}), every filled interior cell liminal_stone. Runs once per chunk, so player edits are never overwritten.
 */
public final class EndlessChunkGenerator extends ChunkGenerator {
	public static final MapCodec<EndlessChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance.group(Biome.CODEC.fieldOf("biome").forGetter(g -> g.biome)).apply(instance, instance.stable(EndlessChunkGenerator::new)));

	private final Holder<Biome> biome;

	public EndlessChunkGenerator(Holder<Biome> biome) {
		super(new FixedBiomeSource(biome));
		this.biome = biome;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
		EndlessCells cells = EndlessCells.get();
		if (cells == null) {
			return CompletableFuture.completedFuture(chunk);
		}
		EndlessGenerator g = cells.generator();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		int rx = Math.floorDiv(bx, EndlessGenerator.REGION), rz = Math.floorDiv(bz, EndlessGenerator.REGION);
		int ox = rx * EndlessGenerator.REGION, oz = rz * EndlessGenerator.REGION;
		byte[] classes = cells.classes(rx, rz);
		Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState stoneBase = LiminalBlocks.LIMINAL_STONE.defaultBlockState();
		for (int y = EndlessGenerator.MIN_Y; y < EndlessGenerator.MAX_Y; y++) {
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					int x = bx + lx, z = bz + lz;
					int c = classes[((y - EndlessGenerator.MIN_Y) * EndlessGenerator.REGION + z - oz) * EndlessGenerator.REGION + x - ox];
					int kind = c & 3;
					if (kind == EndlessGenerator.AIR) {
						continue;
					}
					pos.set(x, y, z);
					BlockState state;
					if (kind == EndlessGenerator.SURFACE) {
						state = LiminalBlocks.MESH_CELL.defaultBlockState();
						state = state.setValue(MeshCellBlock.OCCLUDE_X, (c & EndlessGenerator.FLAG_OCC_X) != 0)
							.setValue(MeshCellBlock.OCCLUDE_Y, (c & EndlessGenerator.FLAG_OCC_Y) != 0)
							.setValue(MeshCellBlock.OCCLUDE_Z, (c & EndlessGenerator.FLAG_OCC_Z) != 0);
						if ((c & EndlessGenerator.FLAG_EMISSIVE) != 0) {
							state = state.setValue(MeshCellBlock.LIGHT, 15);
						}
					} else {
						state = stoneBase;
						for (Direction d : Direction.values()) {
							int nc = classAt(g, classes, ox, oz, x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
							state = state.setValue(LiminalStoneBlock.INSIDE[d.ordinal()], nc != EndlessGenerator.AIR);
						}
					}
					chunk.setBlockState(pos, state);
					floor.update(lx, y, lz, state);
					surface.update(lx, y, lz, state);
					if (kind == EndlessGenerator.SURFACE) {
						MeshCellBlockEntity be = new MeshCellBlockEntity(pos.immutable(), state);
						be.initLevelCell(Endless.LEVEL);
						chunk.setBlockEntity(be);
					}
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private static int classAt(EndlessGenerator g, byte[] classes, int ox, int oz, int x, int y, int z) {
		if (y < EndlessGenerator.MIN_Y) {
			return EndlessGenerator.SOLID;
		}
		if (y >= EndlessGenerator.MAX_Y) {
			return EndlessGenerator.AIR;
		}
		int lx = x - ox, lz = z - oz;
		if (lx >= 0 && lx < EndlessGenerator.REGION && lz >= 0 && lz < EndlessGenerator.REGION) {
			return classes[((y - EndlessGenerator.MIN_Y) * EndlessGenerator.REGION + lz) * EndlessGenerator.REGION + lx] & 3;
		}
		return g.classify(x, y, z) & 3;
	}

	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
	}

	@Override
	public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getGenDepth() {
		return 256;
	}

	@Override
	public int getSeaLevel() {
		return 0;
	}

	@Override
	public int getMinY() {
		return 0;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
		return EndlessGenerator.FLOOR_Y;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		BlockState[] states = new BlockState[level.getHeight()];
		java.util.Arrays.fill(states, Blocks.AIR.defaultBlockState());
		return new NoiseColumn(level.getMinY(), states);
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
	}
}
