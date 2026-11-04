package se.itssimple.realearth.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Coalesces canopy edits on the client thread before Sodium rebuilds its meshes. */
public final class CanopyMeshUpdates {
	private static final LongOpenHashSet DIRTY_SECTIONS = new LongOpenHashSet();
	private static ClientLevel pendingLevel;

	private CanopyMeshUpdates() {
	}

	static void install() {
		CanopyEntityLight.install();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CanopyEntityLight.setLevel(client.level);
			if (pendingLevel != null && pendingLevel == client.level) {
				var sections = DIRTY_SECTIONS.iterator();
				while (sections.hasNext()) {
					long section = sections.nextLong();
					CanopyEntityLight.invalidateSection(pendingLevel, section);
					client.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
				}
			}
			DIRTY_SECTIONS.clear();
			pendingLevel = null;
		});
	}

	public static void dirtyColumn(ClientLevel level, BlockPos edit, int bottom, int top) {
		dirtyRange(level, edit, bottom, Math.min(top, edit.getY()));
	}

	public static void dirtySpread(ClientLevel level, BlockPos edit) {
		dirtyRange(level, edit, edit.getY(), edit.getY());
	}

	private static void dirtyRange(ClientLevel level, BlockPos edit, int bottom, int highestSample) {
		if (bottom > highestSample) {
			return;
		}
		if (pendingLevel != level) {
			DIRTY_SECTIONS.clear();
			pendingLevel = level;
		}

		int border = CanopyLight.LIGHT_BORDER;
		int minX = SectionPos.blockToSectionCoord(edit.getX() - border);
		int maxX = SectionPos.blockToSectionCoord(edit.getX() + border);
		int minZ = SectionPos.blockToSectionCoord(edit.getZ() - border);
		int maxZ = SectionPos.blockToSectionCoord(edit.getZ() + border);
		int minY = Math.max(level.getMinSectionY(), SectionPos.blockToSectionCoord(bottom - border));
		int maxY = Math.min(level.getMaxSectionY(), SectionPos.blockToSectionCoord(highestSample + border));
		for (int z = minZ; z <= maxZ; z++) {
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					DIRTY_SECTIONS.add(SectionPos.asLong(x, y, z));
				}
			}
		}
	}
}
