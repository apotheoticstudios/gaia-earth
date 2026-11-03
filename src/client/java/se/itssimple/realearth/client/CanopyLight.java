package se.itssimple.realearth.client;

import java.util.Arrays;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/** The skylight that reaches a render sample through the foliage directly above it. */
public final class CanopyLight {
	public static final int SAMPLE_WIDTH = 18; // A section plus a block on each side for smooth lighting.
	public static final int BRIGHTEST_SHADE = 12;

	private static final int MAX_DEPTH = 96;
	private static final int CLEAR = 0;
	private static final int LEAF = 1;
	private static final int BLOCKED = 2;

	private CanopyLight() {
	}

	/** A zero byte means the column has yet to be sampled. Store skylight plus one. */
	public static void fillColumn(ChunkAccess chunk, int x, int z, int minY, byte[] samples, int offset) {
		Arrays.fill(samples, offset, offset + SAMPLE_WIDTH, (byte) 1);
		if (chunk == null || !hasHeightmaps(chunk)) {
			return;
		}

		int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		int maxY = minY + SAMPLE_WIDTH - 1;
		int bottom = Math.max(minY, shadeBottom(chunk, x, z, top));
		if (bottom > Math.min(top, maxY)) {
			return;
		}

		// Air between the canopy and the ground carries the filtered light without further loss.
		int transmitted = BRIGHTEST_SHADE + 1;
		for (int y = top; y >= bottom;) {
			LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
			int sectionBottom = Math.max(y & ~15, bottom);
			if (section.hasOnlyAir()) {
				if (transmitted <= BRIGHTEST_SHADE && sectionBottom <= maxY) {
					Arrays.fill(samples, offset + sectionBottom - minY, offset + Math.min(y, maxY) - minY + 1,
						(byte) (transmitted + 1));
				}
				y = sectionBottom - 1;
				continue;
			}
			for (; y >= sectionBottom; y--) {
				int filter = filter(section.getBlockState(x, y & 15, z));
				if (filter == BLOCKED || (filter == LEAF && --transmitted == 0)) {
					return;
				}
				if (y <= maxY && transmitted <= BRIGHTEST_SHADE) {
					samples[offset + y - minY] = (byte) (transmitted + 1);
				}
			}
		}
	}

	public static boolean hasHeightmaps(ChunkAccess chunk) {
		return chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)
			&& chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)
			&& chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
	}

	public static int shadeBottom(ChunkAccess chunk, int x, int z, int top) {
		int roof = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= roof) {
			return Integer.MAX_VALUE;
		}
		return Math.max(roof + 1, top - MAX_DEPTH);
	}

	public static boolean changesTransmission(BlockState before, BlockState after) {
		return filter(before) != filter(after);
	}

	private static int filter(BlockState state) {
		if (state.isAir()) {
			return CLEAR;
		}
		if (!state.getFluidState().isEmpty()) {
			return BLOCKED;
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return LEAF;
		}
		return Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(state)
			|| state.getLightDampening() > 0 || state.canOcclude() ? BLOCKED : CLEAR;
	}
}
