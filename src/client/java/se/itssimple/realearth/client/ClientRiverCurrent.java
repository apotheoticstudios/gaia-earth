package se.itssimple.realearth.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.hydrology.CurrentDemoPayload;
import se.itssimple.realearth.hydrology.CurrentSettings;
import se.itssimple.realearth.hydrology.MappedCurrents;
import se.itssimple.realearth.hydrology.RiverCurrentDemo;

final class ClientRiverCurrent {
	private static final int MAX_SAMPLES = 32;
	private static int soundTicks;
	private static CurrentDemoPayload latest;

	private ClientRiverCurrent() {
	}

	static void install() {
		ClientTickEvents.END_CLIENT_TICK.register(ClientRiverCurrent::effects);
		ClientPlayNetworking.registerGlobalReceiver(CurrentSettings.TYPE, (payload, context) -> CurrentSettings.receive(payload));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> chunk.onAttachedSet(MappedCurrents.TYPE).register((oldField, field) -> {
			var minecraft = Minecraft.getInstance();
			if (minecraft.level != level) return;
			var pos = chunk.getPos();
			if (oldField != null && !oldField.isEmpty()) dirty(minecraft.levelRenderer, pos.x(), pos.z(), oldField);
			if (field != null && !field.isEmpty()) dirty(minecraft.levelRenderer, pos.x(), pos.z(), field);
		}));
		ClientPlayNetworking.registerGlobalReceiver(CurrentDemoPayload.TYPE, (payload, context) -> {
			latest = payload.enabled() ? payload : null;
			update(context.client(), context.client().level);
		});
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(ClientRiverCurrent::update);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			latest = null;
			soundTicks = 0;
			RiverCurrentDemo.receive(null, null);
			CurrentSettings.receive(null);
		});
	}

	private static void effects(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.isPaused() || !RealEarth.isEarth(level)) return;
		var config = RealEarth.config();
		int remaining = config.riverParticlesPerTick;
		boolean sound = config.riverSounds && config.riverSoundVolume > 0 && ++soundTicks % 40 == 0;
		if (remaining == 0 && !sound) return;

		Vec3 camera = minecraft.gameRenderer.getMainCamera().position();
		int centreX = Mth.floor(camera.x), centreZ = Mth.floor(camera.z);
		var random = level.getRandom();
		int radius = config.riverEffectRadius;
		double drift = 0.08 * config.riverWakeSpeed / 100.0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int attempt = 0; attempt < MAX_SAMPLES && (remaining > 0 || sound); attempt++) {
			int x = centreX + random.nextInt(radius * 2 + 1) - radius;
			int z = centreZ + random.nextInt(radius * 2 + 1) - radius;
			int y = MappedCurrents.surfaceAt(level, x, z);
			if (y == Integer.MIN_VALUE || Math.abs(y + 1.0 - camera.y) > 8) continue;
			pos.set(x, y, z);
			if (!level.getBlockState(pos).is(Blocks.WATER) || !level.getBlockState(pos.above()).isAir()) continue;
			var fluid = level.getFluidState(pos);
			if (!fluid.isSource()) continue;
			Vec3 flow = MappedCurrents.flowAt(level, pos);
			if (flow == Vec3.ZERO) continue;
			double surface = y + fluid.getHeight(level, pos) + 0.012;
			if (remaining > 0) {
				double speed = drift * MappedCurrents.speedAt(level, pos);
				level.addParticle(ParticleTypes.FISHING, x + random.nextDouble(), surface, z + random.nextDouble(),
					flow.x * speed, 0, flow.z * speed);
				remaining--;
			}
			if (sound) {
				level.playLocalSound(x + 0.5, surface, z + 0.5, SoundEvents.WATER_AMBIENT, SoundSource.AMBIENT,
					config.riverSoundVolume / 100.0F, 1.0F, false);
				sound = false;
			}
		}
	}

	private static void dirty(LevelRenderer renderer, int x, int z, MappedCurrents.Field field) {
		int top = field.maxSection();
		for (int y = field.minSection(); y <= top; y++) {
			renderer.setSectionDirtyWithNeighbors(x, y, z);
		}
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
