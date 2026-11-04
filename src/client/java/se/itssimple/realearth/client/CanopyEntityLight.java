package se.itssimple.realearth.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** Shares one canopy light calculation across all entities rendered in a section. */
public final class CanopyEntityLight {
	private static final int MAX_SECTIONS = 128;
	private static final byte[] UNLIT = new byte[0];
	private static final Long2ObjectLinkedOpenHashMap<byte[]> SECTIONS = new Long2ObjectLinkedOpenHashMap<>();
	private static ClientLevel level;
	private static CanopyLight.SectionCache scratch;

	private CanopyEntityLight() {
	}

	static void install() {
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> invalidateChunk(world, chunk.getPos()));
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> invalidateChunk(world, chunk.getPos()));
	}

	static void setLevel(ClientLevel world) {
		if (level != world) {
			SECTIONS.clear();
			scratch = null;
			level = world;
		}
	}

	static void invalidateSection(ClientLevel world, long section) {
		if (level == world) {
			SECTIONS.remove(section);
		}
	}

	private static void invalidateChunk(ClientLevel world, ChunkPos chunk) {
		if (level != world || SECTIONS.isEmpty()) {
			return;
		}
		// A section's light calculation reads the chunk it is in and its eight neighbors.
		var entries = SECTIONS.long2ObjectEntrySet().fastIterator();
		while (entries.hasNext()) {
			long section = entries.next().getLongKey();
			if (Math.abs(SectionPos.x(section) - chunk.x()) <= 1 && Math.abs(SectionPos.z(section) - chunk.z()) <= 1) {
				entries.remove();
			}
		}
	}

	public static int sample(ClientLevel world, BlockPos pos, int vanilla) {
		if (vanilla >= CanopyLight.BRIGHTEST_SHADE || world.isOutsideBuildHeight(pos.getY())) {
			return vanilla;
		}
		setLevel(world);
		long section = SectionPos.asLong(pos);
		byte[] light = SECTIONS.getAndMoveToLast(section);
		if (light == null) {
			if (scratch == null) {
				scratch = new CanopyLight.SectionCache(world);
			}
			scratch.reset(SectionPos.of(section));
			light = scratch.copyInterior();
			if (light == null) {
				light = UNLIT;
			}
			if (SECTIONS.size() >= MAX_SECTIONS) {
				SECTIONS.removeFirst();
			}
			SECTIONS.put(section, light);
		}
		int index = ((pos.getZ() & 15) * 16 + (pos.getX() & 15)) * 16 + (pos.getY() & 15);
		return light == UNLIT ? vanilla : Math.max(vanilla, light[index]);
	}
}
