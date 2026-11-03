package se.itssimple.realearth.client;

import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** The skylight that reaches a render sample through the foliage directly above it. */
public final class CanopyLight {
	private static final int BRIGHTEST_SHADE = 14;
	private static final int MAX_DEPTH = 96;
	private static final int CLEAR = 0;
	private static final int LEAF = 1;
	private static final int BLOCKED = 2;

	private CanopyLight() {
	}

	/** A zero byte means the column has yet to be sampled. Store skylight plus one. */
	private static void fillColumn(ChunkAccess chunk, int x, int z, int minY, int width, byte[] samples, int offset) {
		Arrays.fill(samples, offset, offset + width, (byte) 1);
		if (chunk == null || !hasHeightmaps(chunk)) {
			return;
		}

		int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		int maxY = minY + width - 1;
		int bottom = Math.max(minY, shadeBottom(chunk, x, z, top));
		if (bottom > Math.min(top, maxY)) {
			return;
		}

		// Air between the canopy and the ground carries the filtered light without further loss.
		int transmitted = BRIGHTEST_SHADE + 1;
		int leaves = 0;
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
				if (filter == BLOCKED) {
					return;
				}
				if (filter == LEAF) {
					leaves++;
					transmitted = BRIGHTEST_SHADE - leaves / 2;
					if (transmitted == 0) {
						return;
					}
				}
				if (y <= maxY && transmitted <= BRIGHTEST_SHADE) {
					samples[offset + y - minY] = (byte) (transmitted + 1);
				}
			}
		}
	}

	/** Owned by one renderer's chunk build and indexed by block within its lighting sample bounds. */
	public static final class SectionCache {
		private final ClientLevel level;
		private final int width;
		private byte[] samples;
		private int minX;
		private int minY;
		private int minZ;

		public SectionCache(ClientLevel level, int width) {
			this.level = level;
			this.width = width;
		}

		public void reset(int x, int y, int z) {
			minX = x;
			minY = y;
			minZ = z;
			if (samples != null) {
				Arrays.fill(samples, (byte) 0);
			}
		}

		public int skyLight(BlockPos pos, int vanilla) {
			if (vanilla >= BRIGHTEST_SHADE) {
				return vanilla;
			}
			int x = pos.getX() - minX;
			int y = pos.getY() - minY;
			int z = pos.getZ() - minZ;
			if (x < 0 || x >= width || y < 0 || y >= width || z < 0 || z >= width) {
				return vanilla;
			}
			if (samples == null) {
				samples = new byte[width * width * width];
			}
			int column = (z * width + x) * width;
			if (samples[column] == 0) {
				int worldX = pos.getX();
				int worldZ = pos.getZ();
				ChunkAccess chunk = level.getChunkSource().getChunk(worldX >> 4, worldZ >> 4, ChunkStatus.FULL, false);
				fillColumn(chunk, worldX & 15, worldZ & 15, minY, width, samples, column);
			}
			return Math.max(vanilla, samples[column + y] - 1);
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
