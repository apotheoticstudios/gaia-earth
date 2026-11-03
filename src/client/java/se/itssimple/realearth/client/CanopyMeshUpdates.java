package se.itssimple.realearth.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Coalesces canopy edits on the client thread before Sodium rebuilds its meshes. */
public final class CanopyMeshUpdates {
	private static final int SAMPLE_BORDER = 2; // Sodium can sample two blocks outside a section.
	private static final LongOpenHashSet DIRTY_SECTIONS = new LongOpenHashSet();
	private static ClientLevel pendingLevel;

	private CanopyMeshUpdates() {
	}

	static void install() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pendingLevel != null && pendingLevel == client.level) {
				var sections = DIRTY_SECTIONS.iterator();
				while (sections.hasNext()) {
					long section = sections.nextLong();
					client.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
				}
			}
			DIRTY_SECTIONS.clear();
			pendingLevel = null;
		});
	}

	public static void dirtyColumn(ClientLevel level, BlockPos edit, int bottom, int top) {
		int highestSample = Math.min(top, edit.getY());
		if (bottom > highestSample) {
			return;
		}
		if (pendingLevel != level) {
			DIRTY_SECTIONS.clear();
			pendingLevel = level;
		}

		int minX = SectionPos.blockToSectionCoord(edit.getX() - SAMPLE_BORDER);
		int maxX = SectionPos.blockToSectionCoord(edit.getX() + SAMPLE_BORDER);
		int minZ = SectionPos.blockToSectionCoord(edit.getZ() - SAMPLE_BORDER);
		int maxZ = SectionPos.blockToSectionCoord(edit.getZ() + SAMPLE_BORDER);
		int minY = Math.max(level.getMinSectionY(), SectionPos.blockToSectionCoord(bottom - SAMPLE_BORDER));
		int maxY = Math.min(level.getMaxSectionY(), SectionPos.blockToSectionCoord(highestSample + SAMPLE_BORDER));
		for (int z = minZ; z <= maxZ; z++) {
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					DIRTY_SECTIONS.add(SectionPos.asLong(x, y, z));
				}
			}
		}
	}
}
