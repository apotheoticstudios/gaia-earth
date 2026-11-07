package net.apotheoticstudios.gaiaearth.geo;

import java.util.ArrayDeque;

/**
 * The tuned conformal icosahedral projection (docs/PROJECTION-PLAN.md): the WGS84 ellipsoid mapped conformally, through
 * the conformal latitude, onto a sphere, and the sphere conformally onto the 20 faces of an icosahedron turned so that
 * people stand nearest their true size, unfolded along 11 edges that pass almost nobody. Shapes are true everywhere;
 * sizes are within 3% for 93% of the world's people.
 *
 * <p>The map of a face is exact, not interpolated: in stereographic coordinates z from the face's centre, a conformal
 * map onto a flat surface with cone points z_i of angle 300 degrees (the icosahedron's vertices) has
 * {@code dw = C * prod (1 - z / z_i)^(-1/6) dz} (the Schwarz-Christoffel formula for polyhedra), so a position is one
 * line integral, by Gauss-Legendre quadrature; near a vertex, from the vertex, with its singularity taken out by a
 * change of variable. The inverse is Newton's method on that integral, whose derivative is the integrand.
 *
 * <p>Not yet used by worlds: {@link Projection} still assumes latitude from Z alone.
 */
public final class Icosahedral {
	private static final double A = 6_378_137.0;
	private static final double F = 1 / 298.257223563;
	private static final double E2 = F * (2 - F);
	private static final double E = Math.sqrt(E2);
	/** The tuned turn of the icosahedron (0, ±1, ±φ) and its scale, from tools/projection/Tune.java. */
	private static final double[] QUATERNION = {0.471996229311, -0.802496125898, -0.357990178299, 0.071151666719};
	private static final double K = 1.4980252976;
	/** The edges cut to unfold it (vertex indices, as {@link #icosahedron} numbers them): 0.24 million people within 25 km. */
	private static final int[][] CUT = {{0, 1}, {0, 6}, {1, 2}, {1, 3}, {1, 7}, {1, 8}, {2, 4}, {4, 9}, {4, 10}, {5, 6}, {9, 11}};
	/** Where the world's north is true north: the default start, Stockholm. */
	private static final double NORTH_LAT = 59.3293;
	private static final double NORTH_LON = 18.0686;

	private static final int NODES = 40;
	/** Gauss-Legendre nodes and weights on [0, 1]. */
	private static final double[] GX = new double[NODES];
	private static final double[] GW = new double[NODES];

	static {
		for (int i = 0; i < NODES; i++) {
			double x = Math.cos(Math.PI * (i + 0.75) / (NODES + 0.5));
			double dp = 0;
			for (int it = 0; it < 100; it++) {
				double p0 = 1, p1 = x;
				for (int n = 2; n <= NODES; n++) {
					double p2 = ((2 * n - 1) * x * p1 - (n - 1) * p0) / n;
					p0 = p1;
					p1 = p2;
				}
				dp = NODES * (x * p1 - p0) / (x * x - 1);
				double dx = p1 / dp;
				x -= dx;
				if (Math.abs(dx) < 1e-16) {
					break;
				}
			}
			GX[i] = (1 - x) / 2;
			GW[i] = 1 / ((1 - x * x) * dp * dp);
		}
	}

	/** Built after the quadrature's nodes, which it uses. */
	public static final Icosahedral TUNED = new Icosahedral();

	private final double[][] vertices = icosahedron();
	/** Each face's vertices, counterclockwise seen from outside. */
	private final int[][] faces = new int[20][];
	/** Each face's frame: rows e1 (toward its first vertex), e2, e3 (its centre). */
	private final double[][][] frames = new double[20][][];
	/** The 12 vertices' stereographic positions seen from a face's centre (the same for every face); 0 to 2 its own corners. */
	private final double[] zr = new double[12];
	private final double[] zi = new double[12];
	/** Blocks per unit of z at a face's centre; and from a face's centre to its corners, in blocks. */
	private final double c;
	private final double corner;
	/** Each face's place on the net: net = rotate[f] * w + shift[f], as complex numbers. */
	private final double[][] rotate = new double[20][];
	private final double[][] shift = new double[20][];
	/** The net's corners of each face, for finding the face a point is on. */
	private final double[][][] netCorners = new double[20][3][];
	/** The whole net's turn and shift into blocks: block x = Re, z = -Im of (turn * net + offset). */
	private final double[] turn;
	private final double[] offset;
	private final double halfWidth;
	private final double halfHeight;

	private Icosahedral() {
		rotate(vertices, QUATERNION);
		int n = 0;
		for (int i = 0; i < 12; i++) {
			for (int j = i + 1; j < 12; j++) {
				for (int k = j + 1; k < 12; k++) {
					if (adjacent(i, j) && adjacent(j, k) && adjacent(i, k)) {
						double[] centre = normalize(add(add(vertices[i], vertices[j]), vertices[k]));
						boolean ccw = dot(cross(sub(vertices[j], vertices[i]), sub(vertices[k], vertices[i])), centre) > 0;
						faces[n] = ccw ? new int[] {i, j, k} : new int[] {i, k, j};
						double[] e3 = centre;
						double[] a = vertices[faces[n][0]];
						double[] e1 = normalize(sub(a, scale(e3, dot(a, e3))));
						frames[n] = new double[][] {e1, cross(e3, e1), e3};
						n++;
					}
				}
			}
		}
		// The vertices seen from face 0: its own corners first, at 0, 120 and 240 degrees.
		int[] order = new int[12];
		int m = 0;
		for (int v : faces[0]) {
			order[m++] = v;
		}
		for (int v = 0; v < 12; v++) {
			if (v != faces[0][0] && v != faces[0][1] && v != faces[0][2]) {
				order[m++] = v;
			}
		}
		for (int i = 0; i < 12; i++) {
			double[] p = toFrame(0, vertices[order[i]]);
			zr[i] = p[0] / (1 + p[2]);
			zi[i] = p[1] / (1 + p[2]);
		}
		double prod = 1;
		for (double[] v : vertices) {
			prod *= dist(frames[0][2], v);
		}
		c = 2 * A * K * Math.pow(prod, -1.0 / 6);
		// From corner 0 (on the real axis) to the centre: w(0) = 0 = W + C (0 - z0) S, so W = C z0 S.
		double s = 0;
		for (int k = 0; k < NODES; k++) {
			double sg = GX[k], s6 = Math.pow(sg, 6);
			s += GW[k] * 6 * Math.pow(sg, 4) * power(zr[0] * (1 - s6), zi[0] * (1 - s6), 0)[0];
		}
		corner = c * zr[0] * s;

		// Unfold: from face 0, across every edge not cut.
		boolean[] placed = new boolean[20];
		rotate[0] = new double[] {1, 0};
		shift[0] = new double[] {0, 0};
		placed[0] = true;
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		queue.add(0);
		while (!queue.isEmpty()) {
			int f = queue.poll();
			for (int k = 0; k < 3; k++) {
				netCorners[f][k] = add2(mul(rotate[f], cornerW(k)), shift[f]);
			}
			for (int g = 0; g < 20; g++) {
				int[] e = shared(faces[f], faces[g]);
				if (placed[g] || e == null || isCut(e[0], e[1])) {
					continue;
				}
				double[] pa = netCorners[f][indexOf(faces[f], e[0])], pb = netCorners[f][indexOf(faces[f], e[1])];
				double[] wa = cornerW(indexOf(faces[g], e[0])), wb = cornerW(indexOf(faces[g], e[1]));
				rotate[g] = div(sub2(pa, pb), sub2(wa, wb));
				shift[g] = sub2(pa, mul(rotate[g], wa));
				placed[g] = true;
				queue.add(g);
			}
		}
		// Turned so that north is up (-Z) at the start, and centred on the world.
		double[] here = net(NORTH_LAT, NORTH_LON), north = net(NORTH_LAT + 1e-4, NORTH_LON);
		double[] d = sub2(north, here);
		double len = Math.hypot(d[0], d[1]);
		turn = new double[] {d[1] / len, d[0] / len}; // turns d to +i
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (double[][] tri : netCorners) {
			for (double[] p : tri) {
				double[] q = mul(turn, p);
				minX = Math.min(minX, q[0]);
				maxX = Math.max(maxX, q[0]);
				minY = Math.min(minY, q[1]);
				maxY = Math.max(maxY, q[1]);
			}
		}
		offset = new double[] {-(minX + maxX) / 2, -(minY + maxY) / 2};
		halfWidth = (maxX - minX) / 2;
		halfHeight = (maxY - minY) / 2;
	}

	/** The block column (x, z) of a latitude and longitude. */
	public double[] block(double latitude, double longitude) {
		double[] q = add2(mul(turn, net(latitude, longitude)), offset);
		return new double[] {q[0], -q[1]};
	}

	/** The latitude and longitude of a block column, or null where it is off the net (past a seam). */
	public double[] geo(double x, double z) {
		double[] q = div(sub2(new double[] {x, -z}, offset), turn);
		for (int f = 0; f < 20; f++) {
			if (inside(netCorners[f], q)) {
				double[] w = div(sub2(q, shift[f]), rotate[f]);
				double[] zeta = faceInverse(w);
				double r2 = zeta[0] * zeta[0] + zeta[1] * zeta[1];
				double[] local = {2 * zeta[0] / (1 + r2), 2 * zeta[1] / (1 + r2), (1 - r2) / (1 + r2)};
				double[][] e = frames[f];
				double[] p = add(add(scale(e[0], local[0]), scale(e[1], local[1])), scale(e[2], local[2]));
				return new double[] {Math.toDegrees(geodeticLat(Math.asin(Math.max(-1, Math.min(1, p[2]))))), Math.toDegrees(Math.atan2(p[1], p[0]))};
			}
		}
		return null;
	}

	/** Blocks per metre on the ground, the same every way (the map is conformal). */
	public double blocksPerMeter(double latitude, double longitude) {
		double chi = conformalLat(latitude);
		double[] p = unit(chi, Math.toRadians(longitude));
		double prod = 1;
		for (double[] v : vertices) {
			prod *= dist(p, v);
		}
		double phi = Math.toRadians(latitude);
		return K * Math.pow(prod, -1.0 / 6) * A * Math.cos(chi) / (primeVertical(phi) * Math.cos(phi));
	}

	/** How far true north is turned from the world's north (-Z) here, in radians, clockwise (toward +X). */
	public double north(double latitude, double longitude) {
		double[] a = block(latitude, longitude), b = block(latitude + 1e-5, longitude);
		return Math.atan2(b[0] - a[0], a[1] - b[1]);
	}

	/** Half the net's width (x) and height (z), in blocks, about the world's centre. */
	public double[] halfExtent() {
		return new double[] {halfWidth, halfHeight};
	}

	/** The net position (complex) of a latitude and longitude. */
	private double[] net(double latitude, double longitude) {
		double[] p = unit(conformalLat(latitude), Math.toRadians(longitude));
		int f = 0;
		double best = -2;
		for (int g = 0; g < 20; g++) {
			double d = dot(p, frames[g][2]);
			if (d > best) {
				best = d;
				f = g;
			}
		}
		double[] local = toFrame(f, p);
		double[] w = faceMap(local[0] / (1 + local[2]), local[1] / (1 + local[2]));
		return add2(mul(rotate[f], w), shift[f]);
	}

	/** w(z) on a face: from the centre where z is near it, else from the nearest corner. */
	double[] faceMap(double re, double im) {
		double r0 = Math.hypot(zr[0], zi[0]);
		if (Math.hypot(re, im) <= 0.5 * r0) {
			double sr = 0, si = 0;
			for (int k = 0; k < NODES; k++) {
				double[] g = power(re * GX[k], im * GX[k], -1);
				sr += GW[k] * g[0];
				si += GW[k] * g[1];
			}
			return scale2(mul(new double[] {re, im}, new double[] {sr, si}), c);
		}
		int v = nearestCorner(re, im);
		// w = W_v + C (z - z_v) (1 - z / z_v)^(-1/6) * integral over s in [0, 1] of 6 s^4 h(z_v + (z - z_v) s^6) ds,
		// h the integrand without vertex v's own factor.
		double[] zv = {zr[v], zi[v]};
		double[] d = sub2(new double[] {re, im}, zv);
		double[] q = sub2(new double[] {1, 0}, div(new double[] {re, im}, zv));
		double[] t = cpow(q, -1.0 / 6);
		double sr = 0, si = 0;
		for (int k = 0; k < NODES; k++) {
			double s = GX[k], s6 = Math.pow(s, 6), wt = GW[k] * 6 * Math.pow(s, 4);
			double[] h = power(zv[0] + d[0] * s6, zv[1] + d[1] * s6, v);
			sr += wt * h[0];
			si += wt * h[1];
		}
		return add2(cornerW(v), scale2(mul(mul(d, t), new double[] {sr, si}), c));
	}

	/** z of a w on a face: Newton's method, from a guess by the triangle's own shape, or near a corner by its power law. */
	double[] faceInverse(double[] w) {
		double r0 = Math.hypot(zr[0], zi[0]);
		double[] z = scale2(w, r0 / corner);
		for (int v = 0; v < 3; v++) {
			double[] dw = sub2(w, cornerW(v));
			if (Math.hypot(dw[0], dw[1]) < 0.3 * corner) {
				// Near corner v: w - W_v ~ -(6/5) C z_v h_v(z_v) q^(5/6), q = 1 - z / z_v.
				double[] zv = {zr[v], zi[v]};
				double[] lead = scale2(mul(zv, power(zv[0], zv[1], v)), -1.2 * c);
				double[] q = cpow(div(dw, lead), 1.2);
				z = mul(zv, sub2(new double[] {1, 0}, q));
			}
		}
		for (int it = 0; it < 40; it++) {
			double[] f = sub2(faceMap(z[0], z[1]), w);
			if (Math.hypot(f[0], f[1]) < 1e-6) {
				break;
			}
			double[] step = div(f, scale2(power(z[0], z[1], -1), c));
			z = sub2(z, step);
			double r = Math.hypot(z[0], z[1]);
			if (r > r0) {
				z = scale2(z, r0 / r); // stay on the face's disc, where the integrand's branch is the right one
			}
		}
		return z;
	}

	/** prod over the vertices but {@code skip} of (1 - z / z_i)^(-1/6), each factor on its principal branch. */
	private double[] power(double re, double im, int skip) {
		double logAbs = 0, arg = 0;
		for (int i = 0; i < 12; i++) {
			if (i == skip) {
				continue;
			}
			double den = zr[i] * zr[i] + zi[i] * zi[i];
			double ur = 1 - (re * zr[i] + im * zi[i]) / den;
			double ui = -(im * zr[i] - re * zi[i]) / den;
			logAbs += 0.5 * Math.log(ur * ur + ui * ui);
			arg += Math.atan2(ui, ur);
		}
		double mag = Math.exp(-logAbs / 6);
		return new double[] {mag * Math.cos(-arg / 6), mag * Math.sin(-arg / 6)};
	}

	/** Corner k of a face's triangle, in its own plane: on the real axis, then at 120 and 240 degrees. */
	private double[] cornerW(int k) {
		double a = 2 * Math.PI * k / 3;
		return new double[] {corner * Math.cos(a), corner * Math.sin(a)};
	}

	private int nearestCorner(double re, double im) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int v = 0; v < 3; v++) {
			double d = Math.hypot(re - zr[v], im - zi[v]);
			if (d < bd) {
				bd = d;
				best = v;
			}
		}
		return best;
	}

	private double[] toFrame(int f, double[] p) {
		return new double[] {dot(frames[f][0], p), dot(frames[f][1], p), dot(frames[f][2], p)};
	}

	private boolean adjacent(int i, int j) {
		return i != j && dot(vertices[i], vertices[j]) > 0.4;
	}

	private static boolean isCut(int a, int b) {
		for (int[] e : CUT) {
			if (e[0] == Math.min(a, b) && e[1] == Math.max(a, b)) {
				return true;
			}
		}
		return false;
	}

	private static int[] shared(int[] f, int[] g) {
		int[] s = new int[2];
		int n = 0;
		for (int a : f) {
			for (int b : g) {
				if (a == b && n < 2) {
					s[n++] = a;
				}
			}
		}
		return n == 2 && f != g ? s : null;
	}

	private static int indexOf(int[] face, int v) {
		return face[0] == v ? 0 : face[1] == v ? 1 : 2;
	}

	/** Whether q lies in the triangle (with a hair's margin, so points on a joined edge find a face). */
	private static boolean inside(double[][] t, double[] q) {
		double s = 0;
		boolean neg = false, pos = false;
		for (int i = 0; i < 3; i++) {
			double[] a = t[i], b = t[(i + 1) % 3];
			double cr = (b[0] - a[0]) * (q[1] - a[1]) - (b[1] - a[1]) * (q[0] - a[0]);
			double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
			s = cr / len;
			neg |= s < -1e-6;
			pos |= s > 1e-6;
		}
		return !(neg && pos);
	}

	/** The icosahedron Tune.java turns: (0, ±1, ±φ) and its cyclic permutations, in its order. */
	static double[][] icosahedron() {
		double g = (1 + Math.sqrt(5)) / 2;
		double[][] v = new double[12][];
		int k = 0;
		for (int s1 = -1; s1 <= 1; s1 += 2) {
			for (int s2 = -1; s2 <= 1; s2 += 2) {
				v[k++] = normalize(new double[] {0, s1, s2 * g});
				v[k++] = normalize(new double[] {s1, s2 * g, 0});
				v[k++] = normalize(new double[] {s2 * g, 0, s1});
			}
		}
		return v;
	}

	private static void rotate(double[][] v, double[] q) {
		double w = q[0], x = q[1], y = q[2], z = q[3];
		double[][] m = {{1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)}, {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
			{2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
		double n = Math.sqrt(w * w + x * x + y * y + z * z);
		for (int i = 0; i < v.length; i++) {
			double[] p = v[i];
			v[i] = normalize(new double[] {dot(m[0], p) / (n * n), dot(m[1], p) / (n * n), dot(m[2], p) / (n * n)});
		}
	}

	static double conformalLat(double latDegrees) {
		double phi = Math.toRadians(latDegrees), s = Math.sin(phi);
		return 2 * Math.atan(Math.tan(Math.PI / 4 + phi / 2) * Math.pow((1 - E * s) / (1 + E * s), E / 2)) - Math.PI / 2;
	}

	static double geodeticLat(double chi) {
		double phi = chi;
		for (int i = 0; i < 12; i++) {
			double s = Math.sin(phi);
			phi = 2 * Math.atan(Math.tan(Math.PI / 4 + chi / 2) * Math.pow((1 + E * s) / (1 - E * s), E / 2)) - Math.PI / 2;
		}
		return phi;
	}

	private static double primeVertical(double phi) {
		double s = Math.sin(phi);
		return A / Math.sqrt(1 - E2 * s * s);
	}

	private static double[] unit(double lat, double lon) {
		return new double[] {Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] scale(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s, a[2] * s};
	}

	private static double dist(double[] a, double[] b) {
		return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
	}

	private static double[] normalize(double[] a) {
		return scale(a, 1 / Math.sqrt(dot(a, a)));
	}

	// Complex numbers as {re, im}.

	private static double[] mul(double[] a, double[] b) {
		return new double[] {a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]};
	}

	private static double[] div(double[] a, double[] b) {
		double d = b[0] * b[0] + b[1] * b[1];
		return new double[] {(a[0] * b[0] + a[1] * b[1]) / d, (a[1] * b[0] - a[0] * b[1]) / d};
	}

	private static double[] add2(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1]};
	}

	private static double[] sub2(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1]};
	}

	private static double[] scale2(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s};
	}

	/** a^p on the principal branch. */
	private static double[] cpow(double[] a, double p) {
		double r = Math.pow(Math.hypot(a[0], a[1]), p), t = Math.atan2(a[1], a[0]) * p;
		return new double[] {r * Math.cos(t), r * Math.sin(t)};
	}
}
