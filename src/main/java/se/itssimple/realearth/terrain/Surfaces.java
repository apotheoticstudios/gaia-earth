package se.itssimple.realearth.terrain;

import java.util.List;
import se.itssimple.realearth.osm.OsmDetail;
import se.itssimple.realearth.osm.Ways;

/**
 * Draws surveyed surfaces from an overlay (see OsmDetail.Surface) over the ways drawn from the map. A column inside a
 * carriageway is road, inside a pavement, cycle path, square or step a path, inside a parking space a service way, each
 * paved as surveyed ({@link ChunkTerrain#surface}). So roads come out as wide as they really are: where the map's road
 * reaches into a pavement, that column is pavement, and the kerb is where the survey has it. Rails, decks, tunnels and
 * water are left as they are; buildings are planned after, over whatever is here.
 */
final class Surfaces {
	private Surfaces() {
	}

	static void apply(ChunkTerrain t, List<OsmDetail.Surface> surfaces, double[] sx, double[] sy, boolean[] onGlobe) {
		int n = ChunkTerrain.SPAN;
		for (int k = 0; k < n * n; k++) {
			if (!onGlobe[k] || t.way[k] >= Ways.RAIL_BED || t.deckWay[k] != Ways.NONE || t.tunnel[k] != 0 || !Float.isNaN(t.waterMeters[k])) {
				continue;
			}
			OsmDetail.Surface s = at(surfaces, sx[k], sy[k]);
			if (s == null) {
				continue;
			}
			int kind = s.code() / 8;
			t.surface[k] = s.code();
			t.way[k] = kind == OsmDetail.ROAD ? (byte) Math.max(Ways.MINOR, Ways.isPaved(t.way[k]) ? t.way[k] : 0)
				: kind == OsmDetail.PARKING ? Ways.SERVICE : Ways.PATH;
		}
	}

	/** The surface containing a point (sample coordinates), or null. */
	static OsmDetail.Surface at(List<OsmDetail.Surface> surfaces, double x, double y) {
		for (OsmDetail.Surface s : surfaces) {
			if (x >= s.minX() && x <= s.maxX() && y >= s.minY() && y <= s.maxY() && BuildingPlan.contains(s.rings(), x, y)) {
				return s;
			}
		}
		return null;
	}
}
