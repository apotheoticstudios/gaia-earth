# One block a metre everywhere: a plan

The goal (2026-11-03): new worlds at true 1:1 scale everywhere, as Build The Earth is, so every building, road and
hill is its real size wherever you are.

## What is possible

No flat map keeps every distance of a sphere: some places are always stretched (Gauss's Theorema Egregium). A map
can keep **shapes** true everywhere (conformal: north-south and east-west scale equal at every point) while the
**size** drifts from place to place, or keep size true in one direction and shear shapes. So "1:1 everywhere" means
as close to 1:1 as possible where people are, with the stretch pushed into the oceans.

The floor for a single region: the best conformal map of a cap of angular radius θ stretches by about
1/cos²(θ/2) from its centre to its edge. A cap 1,100 km across (θ = 5°): 0.2%. Europe-sized (θ = 15°): 1.7%. A
continent 6,600 km across (θ = 30°): 7%.

## Measured (2026-11-03)

Blocks per real metre (north-south and east-west on the WGS84 ellipsoid), measured from each projection's own code:
BTE's projection compiled from its source (`terraplusplus`, MIT), the mod's Web Mercator (1:1 at Stockholm, as
new worlds are made now), and sinusoidal centred on Stockholm's meridian.

| Place | BTE (N, E) | Mercator (N, E) | Sinusoidal (N, E) |
|---|---|---|---|
| Stockholm | 1.225, 1.220 | 0.999, 0.998 | 0.998, 0.996 |
| London | 1.085, 1.082 | 0.820, 0.818 | 1.030, 0.997 |
| Paris | 1.066, 1.063 | 0.776, 0.774 | 1.021, 0.997 |
| Delft | 1.098, 1.096 | 0.829, 0.827 | 1.017, 0.997 |
| Moscow | 1.069, 1.066 | 0.906, 0.905 | 1.038, 0.997 |
| New York | 1.055, 1.050 | 0.675, 0.672 | 1.451, 0.997 |
| Los Angeles | 1.023, 1.019 | 0.618, 0.615 | 1.670, 0.998 |
| Mexico City | 1.012, 1.006 | 0.544, 0.541 | 1.215, 0.999 |
| São Paulo | 1.056, 1.050 | 0.559, 0.556 | 1.101, 0.998 |
| Buenos Aires | 1.298, 1.290 | 0.622, 0.619 | 1.258, 0.998 |
| Lagos | 1.156, 1.150 | 0.517, 0.513 | 1.006, 0.999 |
| Cairo | 1.008, 1.003 | 0.592, 0.589 | 1.010, 0.998 |
| Johannesburg | 1.023, 1.018 | 0.571, 0.568 | 1.007, 0.998 |
| Delhi | 1.022, 1.017 | 0.584, 0.581 | 1.119, 0.998 |
| Singapore | 1.019, 1.013 | 0.514, 0.510 | 1.006, 0.999 |
| Beijing | 1.296, 1.289 | 0.667, 0.664 | 1.489, 0.998 |
| Tokyo | 1.089, 1.083 | 0.630, 0.627 | 1.595, 0.998 |
| Sydney | 1.004, 0.999 | 0.617, 0.614 | 1.640, 0.998 |
| Auckland | 1.013, 1.009 | 0.639, 0.637 | 1.925, 0.998 |

Over the whole globe, weighted by area, the size error is:

| Projection | Half the globe within | 90% within | 99% within |
|---|---|---|---|
| BTE | 2.4% | 13.5% | 36.7% |
| Mercator | 43% | 49% | 208% |
| Sinusoidal | 7.7% | 45% | 72% (and shapes shear up to 2.6 : 1) |

So BTE's projection keeps shapes true and most of the world within a few percent. It is not 1:1 everywhere,
though: one of its icosahedron's corners lies off Norway, so Stockholm is 22% too big; Beijing and Buenos Aires
are 29% too big.

## Options

1. **BTE's projection as it is.** The same block coordinates as BTE's world (MIT, credited), so the two can be
   compared block for block. Shapes true; sizes as measured above (0.99 to 1.30 in big cities).
2. **Recommended: our own conformal icosahedral projection, tuned for land.** The same construction as BTE's (a
   conformal map on each face of an icosahedron, faces unfolded with the cuts in the oceans). The icosahedron is
   turned, and the scale set, so that where people live comes out nearest 1:1. Candidates are measured with the
   same tool, weighted by population (GHSL population grid, CC BY 4.0), with a target set before tuning: for
   example, nine people in ten within 3% of their true size, and the Stockholm start within 1%. Not
   BTE-compatible.
3. **Keep Mercator,** 1:1 only at the start latitude.

## What it touches

New worlds only. Each world keeps the projection it was made with (`WorldSettings.projection`), so existing worlds
never tear.

The code assumes today's projections throughout: latitude from a block's Z alone (`Projection.latitudeAt(z)`, 41
uses in 18 files), Z from latitude alone (`blockZ(latitude)`, 45 uses), north straight up or merely sheared
(`shear`), and the source tiles' Mercator sample grid mapped to blocks one way (53 uses of `sampleX`, `sampleY` and
`blockOfSample`). An icosahedral map needs latitude and longitude from X and Z together, north turning from face to
face, and seams between faces in the oceans. So:

1. **Projection interface:** `geo(x, z)` and `block(lat, lon)` both ways, `blocksPerMeter(x, z)` and
   `north(x, z)`, with the current projections as implementations.
2. **Every caller moved to it:**
   - terrain sampling and the chunk build (`TerrainField`);
   - water, roads and rails (`WayRaster`, `RailTracks`, `Bridges`, `Tunnels`);
   - buildings, trees, portals and spawn;
   - Xaero's maps, Distant Horizons (`LodSampler`), `/earth` commands, the API.
   Self-tests for each, run under both Mercator and the new projection.
3. **Seams:** what lies past a cut (open ocean, blocked or wrapped), and roads and water stopping cleanly there.
4. **The projection itself:** chosen by option above, measured, written up with its error map, and made the
   default for new worlds.

This comes before the building rebuild is measured against real sizes, since a building can only be its real size
where the world is.
