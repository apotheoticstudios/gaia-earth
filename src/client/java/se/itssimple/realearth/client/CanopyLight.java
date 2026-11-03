package se.itssimple.realearth.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Filtered canopy skylight and its falloff through the surrounding air. */
public final class CanopyLight {
	private static final int BRIGHTEST_SHADE = 14;
	private static final int SPREAD = BRIGHTEST_SHADE - 1;
	private static final int MESH_BORDER = 2; // Sodium samples two blocks beyond the section.
	static final int LIGHT_BORDER = MESH_BORDER + SPREAD;
	private static final int MESH_WIDTH = 16 + 2 * MESH_BORDER;
	private static final int WIDTH = 16 + 2 * LIGHT_BORDER;
	private static final int PLANE = WIDTH * WIDTH;
	private static final int MAX_DEPTH = 96;
	private static final int CLEAR = 0;
	private static final int LEAF = 1;
	private static final int BLOCKED = 2;

	private CanopyLight() {
	}

	private static boolean fillColumn(ChunkAccess chunk, int x, int z, int top, int bottom, int minY, byte[] samples, int offset) {
		int maxY = minY + WIDTH - 1;
		// Air between the canopy and the ground carries the filtered light without further loss.
		int transmitted = BRIGHTEST_SHADE + 1;
		int leaves = 0;
		boolean seeded = false;
		for (int y = top; y >= bottom;) {
			LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
			int sectionBottom = Math.max(y & ~15, bottom);
			if (section.hasOnlyAir()) {
				if (transmitted <= BRIGHTEST_SHADE && sectionBottom <= maxY) {
					Arrays.fill(samples, offset + sectionBottom - minY, offset + Math.min(y, maxY) - minY + 1,
						(byte) transmitted);
					seeded = true;
				}
				y = sectionBottom - 1;
				continue;
			}
			for (; y >= sectionBottom; y--) {
				int filter = filter(section.getBlockState(x, y & 15, z));
				if (filter == BLOCKED) {
					return seeded;
				}
				if (filter == LEAF) {
					leaves++;
					transmitted = BRIGHTEST_SHADE - leaves / 2;
					if (transmitted == 0) {
						return seeded;
					}
				}
				if (y <= maxY && transmitted <= BRIGHTEST_SHADE) {
					samples[offset + y - minY] = (byte) transmitted;
					seeded = true;
				}
			}
		}
		return seeded;
	}

	/** Reused by a Sodium mesh worker. The outer border covers every source that can reach its samples. */
	public static final class SectionCache {
		private final ClientLevel level;
		private final ChunkAccess[] chunks = new ChunkAccess[9];
		private byte[] samples;
		private IntArrayList[] queues;
		private int minX;
		private int minY;
		private int minZ;
		private boolean built;
		private boolean hasLight;

		public SectionCache(ClientLevel level) {
			this.level = level;
		}

		public void reset(int x, int y, int z) {
			minX = x - SPREAD;
			minY = y - SPREAD;
			minZ = z - SPREAD;
			built = false;
			hasLight = false;
		}

		public int skyLight(BlockPos pos, int vanilla) {
			if (vanilla >= BRIGHTEST_SHADE) {
				return vanilla;
			}
			int x = pos.getX() - minX;
			int y = pos.getY() - minY;
			int z = pos.getZ() - minZ;
			if (x < SPREAD || x >= SPREAD + MESH_WIDTH || y < SPREAD || y >= SPREAD + MESH_WIDTH
					|| z < SPREAD || z >= SPREAD + MESH_WIDTH) {
				return vanilla;
			}
			if (!built) {
				build();
			}
			return hasLight ? Math.max(vanilla, samples[index(x, y, z)]) : vanilla;
		}

		private void build() {
			built = true;
			// The padded grid spans exactly the center chunk and its eight neighbors.
			int firstChunkX = minX >> 4;
			int firstChunkZ = minZ >> 4;
			int maxY = minY + WIDTH - 1;
			boolean preparedSamples = false;
			for (int dz = 0; dz < 3; dz++) {
				for (int dx = 0; dx < 3; dx++) {
					int chunkX = firstChunkX + dx;
					int chunkZ = firstChunkZ + dz;
					ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
					chunks[dz * 3 + dx] = chunk;
					if (chunk == null || !hasHeightmaps(chunk)) {
						continue;
					}

					int chunkStartX = (chunkX << 4) - minX;
					int chunkStartZ = (chunkZ << 4) - minZ;
					int startX = Math.max(0, chunkStartX);
					int endX = Math.min(WIDTH, chunkStartX + 16);
					for (int z = Math.max(0, chunkStartZ); z < Math.min(WIDTH, chunkStartZ + 16); z++) {
						for (int x = startX; x < endX; x++) {
							int localX = x - chunkStartX;
							int localZ = z - chunkStartZ;
							int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
							if (top < minY) {
								continue;
							}
							int bottom = Math.max(minY, shadeBottom(chunk, localX, localZ, top));
							if (bottom > Math.min(top, maxY)) {
								continue;
							}
							if (!preparedSamples) {
								if (samples == null) {
									samples = new byte[WIDTH * PLANE];
								} else {
									Arrays.fill(samples, (byte) 0);
								}
								preparedSamples = true;
							}
							hasLight |= fillColumn(chunk, localX, localZ, top, bottom, minY, samples, index(x, 0, z));
						}
					}
				}
			}
			if (hasLight) {
				spread();
			}
			Arrays.fill(chunks, null);
		}

		private void spread() {
			if (queues == null) {
				queues = new IntArrayList[BRIGHTEST_SHADE + 1];
				for (int strength = 2; strength <= BRIGHTEST_SHADE; strength++) {
					queues[strength] = new IntArrayList();
				}
			}
			// Fully lit interiors have nowhere to spread. Queue the edges of the seeded light.
			for (int z = 0; z < WIDTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					int cell = index(x, 0, z);
					for (int y = 0; y < WIDTH; y++, cell++) {
						int next = samples[cell] - 1;
						if (next > 0 && ((y > 0 && samples[cell - 1] < next)
								|| (y < WIDTH - 1 && samples[cell + 1] < next)
								|| (x > 0 && samples[cell - WIDTH] < next)
								|| (x < WIDTH - 1 && samples[cell + WIDTH] < next)
								|| (z > 0 && samples[cell - PLANE] < next)
								|| (z < WIDTH - 1 && samples[cell + PLANE] < next))) {
							queues[next + 1].add(cell);
						}
					}
				}
			}

			// Visit the strongest light first. A cell only needs to spread its final strength.
			for (int strength = BRIGHTEST_SHADE; strength > 1; strength--) {
				IntArrayList queue = queues[strength];
				for (int i = 0; i < queue.size(); i++) {
					int cell = queue.getInt(i);
					if (samples[cell] != strength) {
						continue;
					}
					int x = cell / WIDTH % WIDTH;
					int y = cell % WIDTH;
					int z = cell / PLANE;
					int next = strength - 1;
					if (x > 0) spreadTo(cell - WIDTH, next);
					if (x < WIDTH - 1) spreadTo(cell + WIDTH, next);
					if (y > 0) spreadTo(cell - 1, next);
					if (y < WIDTH - 1) spreadTo(cell + 1, next);
					if (z > 0) spreadTo(cell - PLANE, next);
					if (z < WIDTH - 1) spreadTo(cell + PLANE, next);
				}
				queue.clear();
			}
		}

		private void spreadTo(int cell, int strength) {
			int current = samples[cell];
			if (current < 0 || current >= strength) {
				return;
			}
			if (current == 0 && blocksSpread(cell)) {
				samples[cell] = -1;
				return;
			}
			samples[cell] = (byte) strength;
			if (strength > 1) {
				queues[strength].add(cell);
			}
		}

		private boolean blocksSpread(int cell) {
			int x = minX + cell / WIDTH % WIDTH;
			int y = minY + cell % WIDTH;
			int z = minZ + cell / PLANE;
			ChunkAccess chunk = chunks[((z >> 4) - (minZ >> 4)) * 3 + (x >> 4) - (minX >> 4)];
			if (chunk == null || chunk.isOutsideBuildHeight(y)) {
				return true;
			}
			LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
			return !section.hasOnlyAir() && filter(section.getBlockState(x & 15, y & 15, z & 15)) == BLOCKED;
		}
	}

	private static int index(int x, int y, int z) {
		return z * PLANE + x * WIDTH + y;
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

	public static boolean changesSpread(BlockState before, BlockState after) {
		return (filter(before) == BLOCKED) != (filter(after) == BLOCKED);
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
