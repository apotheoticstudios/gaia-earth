package net.apotheoticstudios.gaiaearth.world;

import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain;
import net.apotheoticstudios.gaiaearth.terrain.TerrainField;

/**
 * Half-block steps in the ground. A column's top face sits at the whole block nearest the real ground, so it can be
 * up to half a block off, and a slope comes out as one-block steps. Beside such a step, a half slab of the ground's
 * own material puts the surface nearer the real ground: on top of the lower column where the ground there is at
 * least a quarter block above its face, in place of the upper column's top block where the ground is at least a
 * quarter block below it. A step becomes two half steps, exactly where the land rises.
 *
 * Laid after trees and plants, only where nothing stands, so nothing floats and nothing grows on a slab (they are in
 * none of the ground tags plants and trees look for). Grass, dirt, sand and the like have no slabs in vanilla: these
 * are Earth's own, drawn with vanilla's textures; rock uses vanilla's slabs, snow its layers.
 */
public final class TerrainSlabs {
	public static final Block GRASS_SLAB = register("grass_slab", Blocks.GRASS_BLOCK);
	public static final Block DIRT_SLAB = register("dirt_slab", Blocks.DIRT);
	public static final Block COARSE_DIRT_SLAB = register("coarse_dirt_slab", Blocks.COARSE_DIRT);
	public static final Block PODZOL_SLAB = register("podzol_slab", Blocks.PODZOL);
	public static final Block MUD_SLAB = register("mud_slab", Blocks.MUD);
	public static final Block MOSS_SLAB = register("moss_slab", Blocks.MOSS_BLOCK);
	public static final Block SAND_SLAB = register("sand_slab", Blocks.SAND);
	public static final Block RED_SAND_SLAB = register("red_sand_slab", Blocks.RED_SAND);
	public static final Block GRAVEL_SLAB = register("gravel_slab", Blocks.GRAVEL);

	/** Each ground block's half slab, and back. */
	private static final Map<Block, BlockState> SLABS = new IdentityHashMap<>();
	private static final Map<Block, BlockState> GROUND = new IdentityHashMap<>();

	static {
		SLABS.put(Blocks.GRASS_BLOCK, GRASS_SLAB.defaultBlockState());
		SLABS.put(Blocks.DIRT, DIRT_SLAB.defaultBlockState());
		SLABS.put(Blocks.COARSE_DIRT, COARSE_DIRT_SLAB.defaultBlockState());
		SLABS.put(Blocks.PODZOL, PODZOL_SLAB.defaultBlockState());
		SLABS.put(Blocks.MUD, MUD_SLAB.defaultBlockState());
		SLABS.put(Blocks.MOSS_BLOCK, MOSS_SLAB.defaultBlockState());
		SLABS.put(Blocks.SAND, SAND_SLAB.defaultBlockState());
		SLABS.put(Blocks.RED_SAND, RED_SAND_SLAB.defaultBlockState());
		SLABS.put(Blocks.GRAVEL, GRAVEL_SLAB.defaultBlockState());
		SLABS.put(Blocks.STONE, Blocks.STONE_SLAB.defaultBlockState());
		SLABS.put(Blocks.ANDESITE, Blocks.ANDESITE_SLAB.defaultBlockState());
		SLABS.put(Blocks.GRANITE, Blocks.GRANITE_SLAB.defaultBlockState());
		SLABS.put(Blocks.DIORITE, Blocks.DIORITE_SLAB.defaultBlockState());
		SLABS.put(Blocks.TUFF, Blocks.TUFF_SLAB.defaultBlockState());
		SLABS.put(Blocks.SANDSTONE, Blocks.SANDSTONE_SLAB.defaultBlockState());
		SLABS.put(Blocks.RED_SANDSTONE, Blocks.RED_SANDSTONE_SLAB.defaultBlockState());
		SLABS.put(Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB.defaultBlockState());
		SLABS.put(Blocks.SNOW_BLOCK, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
		SLABS.forEach((ground, slab) -> {
			if (!slab.is(Blocks.SNOW)) {
				GROUND.put(slab.getBlock(), ground.defaultBlockState());
			}
		});
	}

	private TerrainSlabs() {
	}

	private static Block register(String name, Block base) {
		Block slab = Registry.register(BuiltInRegistries.BLOCK, RealEarth.id(name), new SlabBlock(BlockBehaviour.Properties.ofFullCopy(base)));
		Registry.register(BuiltInRegistries.ITEM, RealEarth.id(name), new BlockItem(slab, new Item.Properties()));
		return slab;
	}

	/** Registers the slabs (by loading this class) and lists them with the natural blocks. */
	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register(entries -> {
			for (Block b : new Block[] {GRASS_SLAB, DIRT_SLAB, COARSE_DIRT_SLAB, PODZOL_SLAB, MUD_SLAB, MOSS_SLAB, SAND_SLAB, RED_SAND_SLAB, GRAVEL_SLAB}) {
				entries.accept(b);
			}
		});
	}

	/** The half slab of a ground block, or null if it has none. */
	public static BlockState slabOf(BlockState ground) {
		return SLABS.get(ground.getBlock());
	}

	/** The ground block a half slab is of (half a block of snow is snow), else the state as it is. */
	public static BlockState groundOf(BlockState state) {
		if (state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == 4) {
			return Blocks.SNOW_BLOCK.defaultBlockState();
		}
		return GROUND.getOrDefault(state.getBlock(), state);
	}

	/** Lays the half steps in a chunk whose trees and plants are in. */
	public static void smooth(ChunkAccess chunk) {
		TerrainField field = RealEarth.terrain();
		if (field == null || !RealEarth.config().terrainSlabs) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		ChunkTerrain t = field.chunkIfReady(pos.x, pos.z);
		if (t == null) {
			return;
		}
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int k = ChunkTerrain.index(x, z);
				if (!open(t, k)) {
					continue;
				}
				int top = t.topSolidY[k];
				boolean higher = false;
				boolean lower = false;
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						int n = ChunkTerrain.index(x + dx, z + dz);
						if (n == k || t.waterTopY[n] != Integer.MIN_VALUE) {
							continue;
						}
						higher |= t.topSolidY[n] == top + 1;
						lower |= t.topSolidY[n] == top - 1;
					}
				}
				if (!higher && !lower) {
					continue;
				}
				// The real ground against the top block's face, in blocks (the face is at the nearest whole block).
				double off = field.toY(t.groundMeters[k], t.latitude[k], t.longitude[k]) - (top + 1);
				int wx = pos.getMinBlockX() + x;
				int wz = pos.getMinBlockZ() + z;
				if (higher && off >= 0.25) {
					BlockState slab = slabOf(chunk.getBlockState(at.set(wx, top, wz)));
					BlockState above = chunk.getBlockState(at.set(wx, top + 1, wz));
					if (slab == null) {
						continue;
					}
					if (above.isAir()) {
						chunk.setBlockState(at, slab, false);
					} else if (above.is(Blocks.SNOW) && above.getValue(SnowLayerBlock.LAYERS) < 4) {
						chunk.setBlockState(at, above.setValue(SnowLayerBlock.LAYERS, 4), false);
					}
				} else if (lower && off <= -0.25 && chunk.getBlockState(at.set(wx, top + 1, wz)).isAir()) {
					BlockState slab = slabOf(chunk.getBlockState(at.set(wx, top, wz)));
					if (slab != null) {
						chunk.setBlockState(at, slab, false);
					}
				}
			}
		}
	}

	/** Natural dry ground: not water, a building, a road or railway, or a tunnel's mouth. */
	public static boolean open(ChunkTerrain t, int k) {
		return t.waterTopY[k] == Integer.MIN_VALUE && t.building[k] == 0 && t.way[k] == net.apotheoticstudios.gaiaearth.osm.Ways.NONE
			&& t.tunnel[k] == 0;
	}
}
