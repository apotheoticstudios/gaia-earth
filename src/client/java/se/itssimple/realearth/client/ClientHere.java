package se.itssimple.realearth.client;

import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import se.itssimple.realearth.world.ClimateLink;
import se.itssimple.realearth.world.HereLink;
import se.itssimple.realearth.world.MapWaypoints;

/**
 * Where on Earth the server last said this player is, and the towns and stations round them, for
 * what the client shows (Xaero's minimap and world map).
 */
public final class ClientHere {
	private static volatile HereLink.Payload now = HereLink.Payload.NOWHERE;
	private static volatile List<MapWaypoints.Mark> marks = List.of();

	private ClientHere() {
	}

	static void install() {
		ClientPlayNetworking.registerGlobalReceiver(HereLink.Payload.TYPE, (payload, context) -> now = payload);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> now = HereLink.Payload.NOWHERE);
		ClientPlayNetworking.registerGlobalReceiver(MapWaypoints.Payload.TYPE, (payload, context) -> marks = payload.marks());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> marks = List.of());
		ClientPlayNetworking.registerGlobalReceiver(ClimateLink.Payload.TYPE, (payload, context) -> ClimateLink.receive(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClimateLink.receive(null));
	}

	/** Where the player is; latitude NaN when they are not on the Earth (or the server has no Real Earth). */
	public static HereLink.Payload now() {
		return now;
	}

	/** The marks round the player, a new list each time the server sends; empty off the Earth. */
	public static List<MapWaypoints.Mark> marks() {
		return marks;
	}

	/** 59.32930° N, 18.06860° E */
	public static String latLon(double lat, double lon) {
		return String.format(Locale.ROOT, "%.5f° %s, %.5f° %s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
	}
}
