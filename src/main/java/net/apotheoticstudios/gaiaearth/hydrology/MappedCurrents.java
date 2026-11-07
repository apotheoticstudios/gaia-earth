package net.apotheoticstudios.gaiaearth.hydrology;

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
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.geo.Mercator;
import net.apotheoticstudios.gaiaearth.geo.Projection;
import net.apotheoticstudios.gaiaearth.osm.OsmWater;

/** Current directions and surface elevations attached to loaded Earth chunks. */
public final class MappedCurrents {
	private static final int STRIDE = 5;
	private static final int SIZE = 256 * STRIDE;
	private static final int NORMAL_SPEED = 128;
	private static final int MAX_DEPTH = 16;
	private static final int MAX_ACTIVE = 4;
	private static final int MAX_CHECKS_PER_TICK = 32;
	private static final Set<LevelChunk> WAITING = new LinkedHashSet<>();
	private static final Map<LevelChunk, CompletableFuture<Field>> ACTIVE = new LinkedHashMap<>();
	// Search across wide mapped river polygons without projecting flow into unrelated water.
	public static final int REACH_SAMPLES = 512;
	public static final Field EMPTY = new Field(new byte[0], STRIDE);
	private static final Vec3[] DIRECTIONS = new Vec3[256];
	public static final AttachmentType<Field> TYPE = AttachmentRegistry.<Field>builder()
		.persistent(Codec.BYTE_BUFFER.comapFlatMap(MappedCurrents::decode, field -> ByteBuffer.wrap(field.encoded())))
		.syncWith(StreamCodec.of((RegistryFriendlyByteBuf buffer, Field field) -> buffer.writeByteArray(field.encoded()),
			buffer -> decode(ByteBuffer.wrap(buffer.readByteArray(SIZE))).getOrThrow()),
			(target, player) -> RealEarth.config().mappedRiverCurrents)
		.buildAndRegister(RealEarth.id("mapped_current_v1"));

	static {
		for (int i = 0; i < DIRECTIONS.length; i++) DIRECTIONS[i] = CurrentAngle.vector((byte) i);
	}

	private MappedCurrents() {
	}

	public static final class Field {
		private final byte[] data;
		private final int savedStride;

		private Field(byte[] data, int savedStride) {
			this.data = data;
			this.savedStride = savedStride;
		}

		public boolean isEmpty() {
			return data.length == 0;
		}

		public boolean isUpToDate() {
			return savedStride == STRIDE;
		}

		private byte[] encoded() {
			if (isUpToDate()) return data;
			byte[] old = new byte[256 * savedStride];
			for (int i = 0; i < 256; i++) System.arraycopy(data, i * STRIDE, old, i * savedStride, savedStride);
			return old;
		}

		private int surface(int column) {
			int offset = column * STRIDE;
			return (short) ((data[offset + 1] & 255) << 8 | (data[offset + 2] & 255));
		}

		private int depth(int column) {
			return Math.clamp(data[column * STRIDE + 4] & 255, 1, MAX_DEPTH);
		}

		public int minSection() {
			int min = Integer.MAX_VALUE;
			for (int i = 0; i < 256; i++) {
				if (CurrentAngle.valid(data[i * STRIDE])) min = Math.min(min, surface(i) - depth(i));
			}
			return min >> 4;
		}

		public int maxSection() {
			int max = Integer.MIN_VALUE;
			for (int i = 0; i < 256; i++) {
				if (CurrentAngle.valid(data[i * STRIDE])) max = Math.max(max, surface(i) + 1);
			}
			return max >> 4;
		}
	}

	private static DataResult<Field> decode(ByteBuffer bytes) {
		int length = bytes.remaining();
		if (length == 0) return DataResult.success(EMPTY);
		if (length != SIZE && length != 256 * 4 && length != 256 * 3) {
			return DataResult.error(() -> "Wrong size for an Earth current field");
		}
		int savedStride = length / 256;
		byte[] data = new byte[SIZE];
		ByteBuffer source = bytes.duplicate();
		if (savedStride != STRIDE) {
			for (int offset = 0; offset < SIZE; offset += STRIDE) {
				source.get(data, offset, savedStride);
				if (savedStride == 3) data[offset + 3] = (byte) NORMAL_SPEED;
				data[offset + 4] = (byte) MAX_DEPTH;
			}
		} else {
			source.get(data);
		}
		for (int i = 0; i < 256; i++) {
			if (CurrentAngle.valid(data[i * STRIDE])) return DataResult.success(new Field(data, savedStride));
		}
		return DataResult.success(EMPTY);
	}

	public static Field of(byte[] angles, int[] surfaces, byte[] speeds, byte[] depths) {
		byte[] data = new byte[SIZE];
		boolean any = false;
		for (int i = 0; i < 256; i++) {
			int y = surfaces[i];
			if (!CurrentAngle.valid(angles[i]) || y < Short.MIN_VALUE || y > Short.MAX_VALUE) {
				data[i * STRIDE] = CurrentAngle.NONE;
				continue;
			}
			data[i * STRIDE] = angles[i];
			data[i * STRIDE + 1] = (byte) (y >> 8);
			data[i * STRIDE + 2] = (byte) y;
			data[i * STRIDE + 3] = speeds[i];
			data[i * STRIDE + 4] = depths[i];
			any = true;
		}
		return any ? new Field(data, STRIDE) : EMPTY;
	}

	/** Distance to connected sea water, measured as a fraction of the downstream probe. */
	public static byte taperOutlet(byte speed, double distance) {
		double t = Math.clamp(distance, 0, 1);
		double fade = 0.25 + 0.75 * t * t * (3 - 2 * t);
		return (byte) Math.max(1, Math.round(Byte.toUnsignedInt(speed) * fade));
	}

	/** Relative game speed from the open-channel relationship v ~ depth^(2/3) * sqrt(slope). */
	public static byte speed(double depthMetres, double slope) {
		double gradient = Double.isFinite(slope) ? Math.clamp(slope, 0.00012, 0.025) : 0.0006;
		double relative = Math.pow(Math.clamp(depthMetres, 0.5, 5.0) / 1.8, 2.0 / 3.0) * Math.sqrt(gradient / 0.001);
		return (byte) Math.round(Math.clamp(relative, 0.18, 1.8) * NORMAL_SPEED);
	}

	public static void register() {
		CurrentSettings.register();
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			var terrain = RealEarth.terrain();
			if (!RealEarth.config().mappedRiverCurrents || terrain == null || terrain.map() == null
				|| !RealEarth.isEarth(level) || resolved(chunk)) return;
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
		if (terrain == null || !RealEarth.config().mappedRiverCurrents) return;
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
			if (resolved(chunk) || !loaded(chunk)) continue;
			boolean wet = Arrays.stream(chunk.getSections()).anyMatch(section -> section.maybeHas(state -> state.is(Blocks.WATER)));
			if (!wet) continue;
			var pos = chunk.getPos();
			ACTIVE.put(chunk, terrain.mappedCurrents(pos.x, pos.z));
		}
	}

	private static boolean resolved(LevelChunk chunk) {
		Field field = chunk.getAttached(TYPE);
		return field != null && field.isUpToDate();
	}

	private static boolean loaded(LevelChunk chunk) {
		var pos = chunk.getPos();
		return ((ServerLevel) chunk.getLevel()).getChunkSource().getChunkNow(pos.x, pos.z) == chunk;
	}

	public static Vec3 flowAt(Level level, BlockPos pos) {
		Field field = flowingFieldAt(level, pos);
		if (field == null) return Vec3.ZERO;
		return DIRECTIONS[field.data[column(pos.getX(), pos.getZ()) * STRIDE] & 255];
	}

	/** Relative speed at a mapped column, independent of the user's strength setting. */
	public static double speedAt(Level level, BlockPos pos) {
		Field field = flowingFieldAt(level, pos);
		return field == null ? 0 : (field.data[column(pos.getX(), pos.getZ()) * STRIDE + 3] & 255) / (double) NORMAL_SPEED;
	}

	private static Field flowingFieldAt(Level level, BlockPos pos) {
		if (level == null || !RealEarth.isEarth(level)) return null;
		if (!level.isClientSide() && !RealEarth.config().mappedRiverCurrents) return null;
		Field field = fieldAt(level, pos.getX(), pos.getZ());
		int column = column(pos.getX(), pos.getZ());
		if (field == null || !CurrentAngle.valid(field.data[column * STRIDE])) return null;
		int surface = field.surface(column);
		int depth = Math.min(CurrentSettings.depth(level), field.depth(column));
		return pos.getY() <= surface && pos.getY() > surface - depth ? field : null;
	}

	private static int column(int x, int z) {
		return (x & 15) + (z & 15) * 16;
	}

	/** Surface block Y for a current column, or MIN_VALUE when the column has no current. */
	public static int surfaceAt(Level level, int x, int z) {
		Field field = fieldAt(level, x, z);
		int column = column(x, z);
		return field != null && CurrentAngle.valid(field.data[column * STRIDE]) ? field.surface(column) : Integer.MIN_VALUE;
	}

	private static Field fieldAt(Level level, int x, int z) {
		int cx = x >> 4, cz = z >> 4;
		var chunk = level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(cx, cz)
			: level.getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null) return null;
		Field field = ((AttachmentTarget) chunk).getAttached(TYPE);
		return field == null || field.isEmpty() ? null : field;
	}

	private record Segment(double x, double y, double dx, double dy, double lengthSquared, double width,
		Vec3 tangent, Vec3 entry, Vec3 exit) {
		double along(double px, double py) {
			return Math.clamp(((px - x) * dx + (py - y) * dy) / lengthSquared, 0, 1);
		}

		double distanceSquared(double px, double py) {
			double t = along(px, py);
			double ex = px - (x + t * dx);
			double ey = py - (y + t * dy);
			return ex * ex + ey * ey;
		}

		byte angleAt(double px, double py, double spacing) {
			double t = along(px, py);
			// Keep long straight reaches aligned with the channel. Only turn near each vertex.
			double turn = Math.min(0.5, 12 / (spacing * Math.sqrt(lengthSquared)));
			Vec3 bend = t < 0.5 ? entry : exit;
			double weight = Math.max(0, 1 - Math.min(t, 1 - t) / turn);
			weight = weight * weight * (3 - 2 * weight);
			return CurrentAngle.encode(Math.atan2(tangent.z + (bend.z - tangent.z) * weight,
				tangent.x + (bend.x - tangent.x) * weight));
		}
	}

	public record Directions(byte[] world, byte[] map) {}

	public static boolean intersectsRiverArea(List<OsmWater.Shapes> shapes, double minX, double minY, double maxX, double maxY) {
		return shapes.stream().flatMap(s -> s.areas().stream()).anyMatch(area ->
			area.river() && overlaps(area, minX, minY, maxX, maxY));
	}

	private static boolean overlaps(OsmWater.Area area, double minX, double minY, double maxX, double maxY) {
		double centre = (area.minX() + area.maxX()) * 0.5;
		double shift = near(centre, (minX + maxX) * 0.5) - centre;
		return area.maxX() + shift >= minX && area.minX() + shift <= maxX && area.maxY() >= minY && area.minY() <= maxY;
	}

	/** At zoom 14 OpenMapTiles uses the individual OSM ways, whose node order follows flow. */
	public static Directions directions(Projection projection, double nearX, double[] x, double[] y, List<OsmWater.Shapes> shapes) {
		byte[] angles = new byte[256];
		byte[] mapAngles = new byte[256];
		Arrays.fill(angles, CurrentAngle.NONE);
		double minX = Arrays.stream(x).min().orElseThrow(), maxX = Arrays.stream(x).max().orElseThrow();
		double minY = Arrays.stream(y).min().orElseThrow(), maxY = Arrays.stream(y).max().orElseThrow();
		double midX = (minX + maxX) * 0.5;
		List<OsmWater.Area> areas = shapes.stream().flatMap(s -> s.areas().stream())
			.filter(area -> overlaps(area, minX, minY, maxX, maxY)).toList();
		boolean wide = areas.stream().anyMatch(OsmWater.Area::river);
		double spacing = Math.min(Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(minY)),
			Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(maxY)));
		List<Segment> segments = new ArrayList<>();
		for (var shape : shapes) {
			for (var line : shape.lines()) {
				double[] p = line.points();
				double reach = wide ? REACH_SAMPLES : Math.max(0.3, line.widthMeters() / (2 * spacing));
				for (int i = 0; i + 3 < p.length; i += 2) {
					double ax = near(p[i], midX), ay = p[i + 1];
					double bx = near(p[i + 2], ax), by = p[i + 3];
					if (Math.max(ax, bx) < minX - reach || Math.min(ax, bx) > maxX + reach
						|| Math.max(ay, by) < minY - reach || Math.min(ay, by) > maxY + reach) continue;
					double dx = bx - ax, dy = by - ay;
					double len2 = dx * dx + dy * dy;
					if (len2 == 0) continue;
					Vec3 tangent = tangent(projection, nearX, ax, ay, bx, by);
					Vec3 entry = i == 0 ? tangent : blend(tangent,
						tangent(projection, nearX, near(p[i - 2], ax), p[i - 1], ax, ay));
					Vec3 exit = i + 5 >= p.length ? tangent : blend(tangent,
						tangent(projection, nearX, bx, by, near(p[i + 4], bx), p[i + 5]));
					segments.add(new Segment(ax, ay, dx, dy, len2, line.widthMeters(), tangent, entry, exit));
				}
			}
		}
		if (segments.isEmpty()) return new Directions(angles, mapAngles);
		for (int i = 0; i < 256; i++) {
			double columnSpacing = Mercator.sampleSpacingMeters(Mercator.latitudeOfSampleY(y[i]));
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
			Segment closest = null;
			for (Segment segment : segments) {
				double radius = river ? REACH_SAMPLES : Math.max(0.3, segment.width() / (2 * columnSpacing));
				double distance = segment.distanceSquared(x[i], y[i]);
				if (distance <= radius * radius && distance < best) {
					best = distance;
					closest = segment;
				}
			}
			if (closest != null) {
				angles[i] = closest.angleAt(x[i], y[i], columnSpacing);
				mapAngles[i] = projection instanceof Projection.WebMercator ? angles[i]
					: CurrentAngle.encode(Math.atan2(closest.dy(), closest.dx()));
			}
		}
		return new Directions(angles, mapAngles);
	}

	private static Vec3 tangent(Projection projection, double nearX, double ax, double ay, double bx, double by) {
		// Both grids are Mercator, so directions are identical in the common projection.
		if (projection instanceof Projection.WebMercator) return new Vec3(bx - ax, 0, by - ay).normalize();
		double[] a = projection.blockOfSample(ax, ay, nearX);
		double[] b = projection.blockOfSample(bx, by, nearX);
		return new Vec3(b[0] - a[0], 0, b[1] - a[1]).normalize();
	}

	private static Vec3 blend(Vec3 tangent, Vec3 neighbour) {
		return tangent.dot(neighbour) < -0.6 ? tangent : tangent.add(neighbour).normalize();
	}

	public static Vec3 direction(byte angle) {
		return DIRECTIONS[angle & 255];
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
