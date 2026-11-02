package se.itssimple.realearth.world;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.terrain.VerticalScale;
import se.itssimple.realearth.RealEarth;

/**
 * The shape of the server's map -- its projection and vertical scale -- for snow and ice
 * (BiomeTemperatureMixin), which follow a block's latitude and real elevation on the client as on
 * the server. Sent once, on joining; in single player the client reads the server's own.
 */
public final class ClimateLink {
	/** A projection by its id and its one or two parameters, and the vertical scale's two knees. */
	public record Payload(String projection, double latitude, double longitude, double compressAbove, double compressBelow) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(RealEarth.id("map_shape"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Payload::projection,
			ByteBufCodecs.DOUBLE, Payload::latitude,
			ByteBufCodecs.DOUBLE, Payload::longitude,
			ByteBufCodecs.DOUBLE, Payload::compressAbove,
			ByteBufCodecs.DOUBLE, Payload::compressBelow,
			Payload::new);

		static Payload of(Projection projection, double compressAbove, double compressBelow) {
			return projection instanceof Projection.WebMercator m
				? new Payload(m.id(), m.scaleLatitude(), 0.0, compressAbove, compressBelow)
				: new Payload(projection.id(), projection.originLatitude(), projection.originLongitude(), compressAbove, compressBelow);
		}

		Shape shape() {
			Projection p = "sinusoidal".equals(projection) ? new Projection.Sinusoidal(latitude, longitude) : new Projection.WebMercator(latitude);
			return new Shape(p, new VerticalScale(compressAbove, compressBelow));
		}

		@Override
		public Type<Payload> type() {
			return TYPE;
		}
	}

	/** Where a block is on Earth, and how high in real metres. */
	public record Shape(Projection projection, VerticalScale scale) {
		/** The temperature that decides snow and ice at a block, from its biome's own. */
		public float temperature(float base, BlockPos pos) {
			double lat = projection.latitudeAt(pos.getZ() + 0.5);
			double meters = scale.toElevation(pos.getY()) / projection.blocksPerMeter(lat);
			return BiomePicker.heightTemperature(base, meters, lat, projection.longitudeAt(pos.getX() + 0.5, lat));
		}
	}

	private static volatile Payload served;
	private static volatile Shape server;
	private static volatile Shape client;

	private ClimateLink() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			Payload p = served;
			if (p != null && ServerPlayNetworking.canSend(handler.player, Payload.TYPE)) {
				ServerPlayNetworking.send(handler.player, p);
			}
		});
	}

	/** The server's map, from its start until it stops. */
	public static void serve(Projection projection, double compressAbove, double compressBelow) {
		Payload p = Payload.of(projection, compressAbove, compressBelow);
		server = p.shape();
		served = p;
	}

	public static void stop() {
		served = null;
		server = null;
	}

	/** On the client: the map the server sent, or null on leaving it. */
	public static void receive(Payload payload) {
		client = payload == null ? null : payload.shape();
	}

	/** This side's map: the server's own where there is one here, else the one sent; null if neither. */
	public static Shape current() {
		Shape s = server;
		return s != null ? s : client;
	}
}
