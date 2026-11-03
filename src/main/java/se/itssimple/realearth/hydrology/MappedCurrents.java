package se.itssimple.realearth.hydrology;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.geo.Mercator;
import se.itssimple.realearth.geo.Projection;
import se.itssimple.realearth.osm.OsmWater;

/** Current directions and surface elevations attached to loaded Earth chunks. */
public final class MappedCurrents {
	private static final int SIZE = 256 * 3;
	private static final int DEPTH = 8;
	private static final int MAX_ACTIVE = 4;
	private static final int MAX_CHECKS_PER_TICK = 32;
	private static final Set<LevelChunk> WAITING = new LinkedHashSet<>();
	private static final Map<LevelChunk, CompletableFuture<Field>> ACTIVE = new LinkedHashMap<>();
	// Search across wide mapped river polygons without projecting flow into unrelated water.
	public static final int REACH_SAMPLES = 512;
	public static final Field EMPTY = new Field(new byte[0]);
	private static final Vec3[] DIRECTIONS = new Vec3[256];
	public static final AttachmentType<Field> TYPE = AttachmentRegistry.<Field>builder()
		.persistent(Codec.BYTE_BUFFER.comapFlatMap(MappedCurrents::decode, field -> ByteBuffer.wrap(field.data)))
		.syncWith(StreamCodec.of((RegistryFriendlyByteBuf buffer, Field field) -> buffer.writeByteArray(field.data),
			buffer -> decode(ByteBuffer.wrap(buffer.readByteArray(SIZE))).getOrThrow()),
			AttachmentSyncPredicate.all())
		.buildAndRegister(RealEarth.id("mapped_current_v1"));

	static {
		for (int i = 0; i < DIRECTIONS.length; i++) DIRECTIONS[i] = CurrentAngle.vector((byte) i);
	}

	private MappedCurrents() {
	}

	public static final class Field {
		private final byte[] data;

		private Field(byte[] data) {
			this.data = data;
		}

		public boolean isEmpty() {
			return data.length == 0;
		}

		private int surface(int column) {
			int offset = column * 3;
			return (short) ((data[offset + 1] & 255) << 8 | (data[offset + 2] & 255));
		}

		public int minSection() {
			int min = Integer.MAX_VALUE;
			for (int i = 0; i < 256; i++) {
				if (CurrentAngle.valid(data[i * 3])) min = Math.min(min, surface(i) - DEPTH);
			}
			return min >> 4;
		}

		public int maxSection() {
			int max = Integer.MIN_VALUE;
			for (int i = 0; i < 256; i++) {
				if (CurrentAngle.valid(data[i * 3])) max = Math.max(max, surface(i) + 1);
			}
			return max >> 4;
		}
	}

	private static DataResult<Field> decode(ByteBuffer bytes) {
		if (bytes.remaining() == 0) return DataResult.success(EMPTY);
		if (bytes.remaining() != SIZE) return DataResult.error(() -> "Wrong size for an Earth current field");
		byte[] data = new byte[SIZE];
		bytes.duplicate().get(data);
		for (int i = 0; i < 256; i++) {
			if (CurrentAngle.valid(data[i * 3])) return DataResult.success(new Field(data));
		}
		return DataResult.success(EMPTY);
	}

	public static Field of(byte[] angles, int[] surfaces) {
		byte[] data = new byte[SIZE];
		boolean any = false;
		for (int i = 0; i < 256; i++) {
			int y = surfaces[i];
			if (!CurrentAngle.valid(angles[i]) || y < Short.MIN_VALUE || y > Short.MAX_VALUE) {
				data[i * 3] = CurrentAngle.NONE;
				continue;
			}
			data[i * 3] = angles[i];
			data[i * 3 + 1] = (byte) (y >> 8);
			data[i * 3 + 2] = (byte) y;
			any = true;
		}
		return any ? new Field(data) : EMPTY;
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			var terrain = RealEarth.terrain();
			if (terrain == null || terrain.map() == null || !RealEarth.isEarth(level) || chunk.hasAttached(TYPE)) return;
			// The full chunk future is still completing here. Even a ready current result must
			// wait for a later tick before checking the chunk or attaching anything to it.
			WAITING.add(chunk);
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> WAITING.remove(chunk));
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WAITING.clear();
			ACTIVE.clear();
		});
	}

	private static void tick() {
		var terrain = RealEarth.terrain();
		if (terrain == null) return;
		var active = ACTIVE.entrySet().iterator();
		while (active.hasNext()) {
			var entry = active.next();
			if (!entry.getValue().isDone()) continue;
			active.remove();
			LevelChunk chunk = entry.getKey();
			try {
				Field field = entry.getValue().getNow(null);
				if (field != null && loaded(chunk)) chunk.setAttached(TYPE, field);
			} catch (CompletionException e) {
				RealEarth.LOGGER.debug("Could not resolve currents in {}", chunk.getPos(), e);
			}
		}
		var waiting = WAITING.iterator();
		for (int checked = 0; checked < MAX_CHECKS_PER_TICK && ACTIVE.size() < MAX_ACTIVE && waiting.hasNext(); checked++) {
			LevelChunk chunk = waiting.next();
			waiting.remove();
			if (chunk.hasAttached(TYPE) || !loaded(chunk)) continue;
			boolean wet = Arrays.stream(chunk.getSections()).anyMatch(section -> section.maybeHas(state -> state.is(Blocks.WATER)));
			if (!wet) continue;
			var pos = chunk.getPos();
			ACTIVE.put(chunk, terrain.mappedCurrents(pos.x(), pos.z()));
		}
	}

	private static boolean loaded(LevelChunk chunk) {
		var pos = chunk.getPos();
		return ((ServerLevel) chunk.getLevel()).getChunkSource().getChunkNow(pos.x(), pos.z()) == chunk;
	}

	public static Vec3 flowAt(Level level, BlockPos pos) {
		if (level == null || !RealEarth.isEarth(level)) return Vec3.ZERO;
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		var chunk = level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(cx, cz)
			: level.getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null) return Vec3.ZERO;
		Field field = ((AttachmentTarget) chunk).getAttached(TYPE);
		if (field == null || field.isEmpty()) return Vec3.ZERO;
		int column = (pos.getX() & 15) + (pos.getZ() & 15) * 16;
		Vec3 flow = DIRECTIONS[field.data[column * 3] & 255];
		if (flow == Vec3.ZERO) return flow;
		int surface = field.surface(column);
		return pos.getY() <= surface && pos.getY() > surface - DEPTH ? flow : Vec3.ZERO;
	}

	private record Segment(double x, double y, double dx, double dy, double lengthSquared, double width, byte angle) {
		double distanceSquared(double px, double py) {
			double t = Math.clamp(((px - x) * dx + (py - y) * dy) / lengthSquared, 0, 1);
			double ex = px - (x + t * dx);
			double ey = py - (y + t * dy);
			return ex * ex + ey * ey;
		}
	}

	/** At zoom 14 OpenMapTiles uses the individual OSM ways, whose node order follows flow. */
	public static byte[] directions(Projection projection, double nearX, double[] x, double[] y, List<OsmWater.Shapes> shapes) {
		byte[] angles = new byte[256];
		Arrays.fill(angles, CurrentAngle.NONE);
		double minX = Arrays.stream(x).min().orElseThrow(), maxX = Arrays.stream(x).max().orElseThrow();
		double minY = Arrays.stream(y).min().orElseThrow(), maxY = Arrays.stream(y).max().orElseThrow();
		double midX = (minX + maxX) * 0.5;
		List<Segment> segments = new ArrayList<>();
		for (var shape : shapes) {
			for (var line : shape.lines()) {
				double[] p = line.points();
				for (int i = 0; i + 3 < p.length; i += 2) {
					double ax = near(p[i], midX), ay = p[i + 1];
					double bx = near(p[i + 2], ax), by = p[i + 3];
					if (Math.max(ax, bx) < minX - REACH_SAMPLES || Math.min(ax, bx) > maxX + REACH_SAMPLES
						|| Math.max(ay, by) < minY - REACH_SAMPLES || Math.min(ay, by) > maxY + REACH_SAMPLES) continue;
					double dx = bx - ax, dy = by - ay;
					double len2 = dx * dx + dy * dy;
					if (len2 == 0) continue;
					double[] a = projection.blockOfSample(ax, ay, nearX);
					double[] b = projection.blockOfSample(bx, by, nearX);
					byte angle = CurrentAngle.encode(Math.atan2(b[1] - a[1], b[0] - a[0]));
					segments.add(new Segment(ax, ay, dx, dy, len2, line.widthMeters(), angle));
				}
			}
		}
		if (segments.isEmpty()) return angles;
		List<OsmWater.Area> areas = shapes.stream().flatMap(s -> s.areas().stream()).toList();
		for (int i = 0; i < 256; i++) {
			double spacing = Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(y[i]));
			boolean river = false;
			boolean standing = false;
			boolean sea = false;
			for (var area : areas) {
				if (!contains(area, x[i], y[i])) continue;
				river |= area.river();
				sea |= area.sea();
				standing |= !area.river() && !area.sea();
			}
			// Some rivers are supplied both as named lake outlines and explicit river surfaces.
			if (sea || standing && !river) continue;
			double best = Double.POSITIVE_INFINITY;
			for (Segment segment : segments) {
				double radius = river ? REACH_SAMPLES : Math.max(0.3, segment.width() / (2 * spacing));
				double distance = segment.distanceSquared(x[i], y[i]);
				if (distance <= radius * radius && distance < best) {
					best = distance;
					angles[i] = segment.angle();
				}
			}
		}
		return angles;
	}

	public static double near(double x, double reference) {
		return x + Math.rint((reference - x) / Mercator.SAMPLES_PER_AXIS) * Mercator.SAMPLES_PER_AXIS;
	}

	private static boolean contains(OsmWater.Area area, double x, double y) {
		double px = near(x, (area.minX() + area.maxX()) * 0.5);
		if (px < area.minX() || px > area.maxX() || y < area.minY() || y > area.maxY()) return false;
		boolean inside = false;
		for (double[] ring : area.rings()) {
			if (ring.length < 6) continue;
			for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
				double yi = ring[i + 1], yj = ring[j + 1];
				if ((yi > y) != (yj > y) && px < ring[i] + (ring[j] - ring[i]) * (y - yi) / (yj - yi)) {
					inside = !inside;
				}
			}
		}
		return inside;
	}
}
