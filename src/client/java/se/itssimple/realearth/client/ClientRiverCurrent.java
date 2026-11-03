package se.itssimple.realearth.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import se.itssimple.realearth.hydrology.CurrentDemoPayload;
import se.itssimple.realearth.hydrology.RiverCurrentDemo;

final class ClientRiverCurrent {
	private static CurrentDemoPayload latest;

	private ClientRiverCurrent() {
	}

	static void install() {
		ClientPlayNetworking.registerGlobalReceiver(CurrentDemoPayload.TYPE, (payload, context) -> {
			latest = payload.enabled() ? payload : null;
			update(context.client(), context.client().level);
		});
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(ClientRiverCurrent::update);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			latest = null;
			RiverCurrentDemo.receive(null, null);
		});
	}

	private static void update(Minecraft minecraft, ClientLevel level) {
		BlockPos previous = RiverCurrentDemo.receive(level, latest);
		BlockPos next = latest != null && latest.appliesTo(level) ? latest.surface() : null;
		if (previous != null && !previous.equals(next)) dirty(minecraft.levelRenderer, previous);
		if (next != null) dirty(minecraft.levelRenderer, next);
	}

	private static void dirty(LevelRenderer renderer, BlockPos pos) {
		int r = RiverCurrentDemo.RADIUS;
		// Rebuild each affected section once, including the neighbouring block at every edge.
		for (int x = (pos.getX() - r - 1) >> 4; x <= (pos.getX() + r) >> 4; x++) {
			for (int y = (pos.getY() - RiverCurrentDemo.DEPTH) >> 4; y <= (pos.getY() + 1) >> 4; y++) {
				for (int z = (pos.getZ() - r - 1) >> 4; z <= (pos.getZ() + r) >> 4; z++) {
					renderer.setSectionDirty(x, y, z);
				}
			}
		}
	}
}
