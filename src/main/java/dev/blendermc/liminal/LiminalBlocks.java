package dev.blendermc.liminal;

import dev.blendermc.BlenderMc;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** The liminal geometry as blocks: {@code mesh_cell} (one cell of a level, or a placed piece) and the {@code mesh_piece} item. */
public final class LiminalBlocks {
	public static final ResourceKey<Block> MESH_CELL_KEY = ResourceKey.create(Registries.BLOCK, BlenderMc.id("mesh_cell"));
	public static final ResourceKey<Item> MESH_PIECE_KEY = ResourceKey.create(Registries.ITEM, BlenderMc.id("mesh_piece"));

	public static final DataComponentType<MeshPiece> MESH_PIECE_COMPONENT = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
		BlenderMc.id("mesh_piece"),
		DataComponentType.<MeshPiece>builder().persistent(MeshPiece.CODEC).networkSynchronized(MeshPiece.STREAM_CODEC).cacheEncoding().build());

	public static final Block MESH_CELL = Registry.register(BuiltInRegistries.BLOCK, MESH_CELL_KEY, new MeshCellBlock(BlockBehaviour.Properties.of()
		.setId(MESH_CELL_KEY)
		.mapColor(MapColor.SAND)
		.strength(1.5F, 3.0F)
		.sound(SoundType.WOOL)
		.dynamicShape()
		// Fluids treat it as solid ground, not something to wash away.
		.forceSolidOn()
		.lightLevel(state -> state.getValue(MeshCellBlock.LIGHT))
		.isSuffocating((state, level, pos) -> false)
		.isViewBlocking((state, level, pos) -> false)
		.isRedstoneConductor((state, level, pos) -> false)
		.isValidSpawn((state, level, pos, type) -> false)
		.pushReaction(PushReaction.BLOCK)
		.noLootTable()));

	public static final BlockEntityType<MeshCellBlockEntity> MESH_CELL_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		BlenderMc.id("mesh_cell"), FabricBlockEntityTypeBuilder.create(MeshCellBlockEntity::new, MESH_CELL).build());

	public static final ResourceKey<Block> OFFSET_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, BlenderMc.id("offset_block"));

	/** A plain block sitting off the grid; no longer placed (blocks follow the grid again), kept so saved ones load. */
	public static final Block OFFSET_BLOCK = Registry.register(BuiltInRegistries.BLOCK, OFFSET_BLOCK_KEY, new OffsetBlock(BlockBehaviour.Properties.of()
		.setId(OFFSET_BLOCK_KEY)
		.mapColor(MapColor.STONE)
		.strength(1.5F, 6.0F)
		.noOcclusion()
		.dynamicShape()
		.forceSolidOn()
		.isSuffocating((state, level, pos) -> false)
		.isViewBlocking((state, level, pos) -> false)
		.isRedstoneConductor((state, level, pos) -> false)
		.isValidSpawn((state, level, pos, type) -> false)
		.pushReaction(PushReaction.BLOCK)));

	public static final BlockEntityType<OffsetBlockEntity> OFFSET_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		BlenderMc.id("offset_block"), FabricBlockEntityTypeBuilder.create(OffsetBlockEntity::new, OFFSET_BLOCK).build());

	public static final ResourceKey<Block> LIMINAL_STONE_KEY = ResourceKey.create(Registries.BLOCK, BlenderMc.id("liminal_stone"));

	/** The fill inside a level: stone, but only seen where the mesh over it was dug away. */
	public static final Block LIMINAL_STONE = Registry.register(BuiltInRegistries.BLOCK, LIMINAL_STONE_KEY,
		new LiminalStoneBlock(BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.STONE).setId(LIMINAL_STONE_KEY)));

	public static final Item MESH_PIECE = Registry.register(BuiltInRegistries.ITEM, MESH_PIECE_KEY,
		new BlockItem(MESH_CELL, new Item.Properties().setId(MESH_PIECE_KEY)));

	private LiminalBlocks() {
	}

	/** Touching the class registers everything; call once from the mod initializer. */
	public static void register() {
	}

	/** A mesh_piece item carrying {@code piece}, named "fragment of &lt;level&gt;". */
	public static ItemStack pieceStack(MeshPiece piece) {
		ItemStack stack = new ItemStack(MESH_PIECE);
		stack.set(MESH_PIECE_COMPONENT, piece);
		stack.set(DataComponents.ITEM_NAME, Component.literal("fragment of " + piece.sourceLevel()));
		return stack;
	}
}
