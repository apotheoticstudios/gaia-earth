package net.apotheoticstudios.gaiaearth.hydrology;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.Level;
import net.apotheoticstudios.gaiaearth.RealEarth;

/** The server's physics settings are also used for client movement prediction. */
public record CurrentSettings(int strength, int depth) implements CustomPacketPayload {
	public static final Type<CurrentSettings> TYPE = new Type<>(RealEarth.id("current_settings"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CurrentSettings> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, CurrentSettings::strength,
		ByteBufCodecs.VAR_INT, CurrentSettings::depth,
		CurrentSettings::new);
	private static final CurrentSettings DEFAULT = new CurrentSettings(100, 8);
	private static volatile CurrentSettings remote = DEFAULT;

	public CurrentSettings {
		strength = Math.clamp(strength, 0, 200);
		depth = Math.clamp(depth, 1, 16);
	}

	static void register() {
		PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.player, TYPE)) {
				var config = RealEarth.config();
				ServerPlayNetworking.send(handler.player, new CurrentSettings(config.riverCurrentStrength, config.riverCurrentDepth));
			}
		});
	}

	public static void receive(CurrentSettings settings) {
		remote = settings == null ? DEFAULT : settings;
	}

	public static int strength(Level level) {
		return level.isClientSide() ? remote.strength : RealEarth.config().riverCurrentStrength;
	}

	static int depth(Level level) {
		return level.isClientSide() ? remote.depth : RealEarth.config().riverCurrentDepth;
	}

	@Override
	public Type<CurrentSettings> type() {
		return TYPE;
	}
}
