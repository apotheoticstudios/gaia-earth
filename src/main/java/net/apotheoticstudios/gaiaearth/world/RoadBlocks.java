package net.apotheoticstudios.gaiaearth.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.terrain.TerrainField;

/**
 * Saro's Road Blocks (a dependency): its asphalt, plain or painted with a white or yellow line down the middle
 * (texture_variant straight or road_block_linie_yellow; along Z facing north, along X facing east). Looked up on
 * first use, after every mod has registered its blocks; vanilla blocks if a version of it renames them.
 */
final class RoadBlocks {
	static final BlockState ASPHALT = block("asphalt");
	static final BlockState WHITE = ASPHALT == null ? Blocks.WHITE_CONCRETE.defaultBlockState() : with(ASPHALT, "texture_variant", "straight");
	static final BlockState YELLOW = ASPHALT == null ? Blocks.YELLOW_CONCRETE.defaultBlockState() : with(ASPHALT, "texture_variant", "road_block_linie_yellow");
	static final BlockState SIDEWALK = block("sidewalk");
	/** A street lamp: poles, then the head, whose arm and lamp hang toward its facing (the pole at the back edge). */
	static final BlockState POLE = block("street_light_pole");
	static final BlockState LIGHT = block("street_light");

	private RoadBlocks() {
	}

	private static BlockState block(String name) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK
			.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("saros_road_blocks_mod", name)).map(Block::defaultBlockState).orElse(null);
	}

	/**
	 * This world's road version (WorldSettings.roadVersion): from 1 asphalt and markings, from 3 kerbs, from 4 street
	 * furniture, from 5 roads level with the ground, from 6 roads climbing in sixteenths, from 7 bridges as they really are,
	 * from 8 opening bridges' leaves and drawbridges' lifting gear, from 9 truss bridges' trusses, from 10 bridges whole
	 * across map tiles, from 11 every bridge the map outlines built whole, with Macaw's railings, from 12 long beam spans
	 * haunched, from 13 bridge decks wide enough to walk between their railings.
	 */
	static int roadVersion() {
		TerrainField field = RealEarth.terrain();
		return field == null || field.map() == null ? 0 : field.map().roadVersion();
	}

	/**
	 * Saro's asphalt or sidewalk {@code sixteenths} high (1 to 16; its variant N is 16 - N sixteenths tall, "default" a
	 * full block); anything else, or 0, unchanged.
	 */
	static BlockState high(BlockState state, int sixteenths) {
		boolean saro = ASPHALT != null && state.is(ASPHALT.getBlock()) || SIDEWALK != null && state.is(SIDEWALK.getBlock());
		return !saro || sixteenths <= 0 ? state : with(state, "variant", sixteenths >= 16 ? "default" : Integer.toString(16 - sixteenths));
	}

	/** The state with a property of the mod's set by name, unchanged where it has no such property or value. */
	static BlockState with(BlockState state, String property, String value) {
		var p = state.getBlock().getStateDefinition().getProperty(property);
		return p == null ? state : set(state, p, value);
	}

	private static <T extends Comparable<T>> BlockState set(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> p,
		String value) {
		return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
	}
}
