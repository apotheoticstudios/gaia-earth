"""Grows trees of species no tree pack has, as vanilla structure templates for Project Gaia: Earth's trees.

Each species is a few numbers: its height, how much of it is bare trunk, its crown's width and shape, its stems and
limbs, the size and look of its leaf clumps, and the blocks of its bark and leaves. Three forms grow from them:

- crown: a broadleaf or pine. Leaf clumps are scattered through the crown's outline (an ellipsoid, a dome widest
  low down, an umbrella widest high up, or a cone), then the trunk forks into limbs, each limb towards a group of
  neighbouring clumps, and forks again, so the branches reach every clump. With a leader the trunk runs to the top
  instead and each clump gets its own branch from it, as pines do. Each clump is a cloud of small leafy sprays, not
  one ball: ragged and gappy, fuller on top, the far sprays on twigs of their own. Big trunks flare into roots.
- conifer: a spruce, fir or cypress, a trunk to the top with whorls of branches narrowing to a spire, each branch
  of its own length, level by the trunk and drooping to its tip, its sprays gappy and with side twigs; below the
  crown the stubs of branches that died in the shade, and now and then a forked top.
- grasstree: a Xanthorrhoea, a short black trunk under a tuft, sometimes forked, sometimes in flower.

Each species grows VERSIONS trees at heights scattered from young to full height, and no two alike: each tree takes
its own proportions round its species' (width, bare trunk, clump size and spacing, lean, how full its foliage is,
how many limbs), and its crown is lopsided,
fuller on one side and higher-bottomed on another, its clumps uneven, now and then a branch dead or broken off, its
limbs parting at different heights; a conifer's trunk sweeps a little and its whorls are uneven. Every run makes the
same trees, as each is seeded by its species and version. They go through convert.py's own steps (ground, leaf
decay, reach), so the game loads them like any pack's trees. Leaves out of reach of every log are left out, so a whole
tree decays once it is cut.

    python3 generate.py <out folder>
    python3 generate.py --preview <folder> [species ...]   side views as PNG, to check the shapes by eye
    python3 generate.py --self-test

Output, in <out folder>/gaia-generated/: <species>_<n>.nbt and trees.json, as convert.py writes them.
"""
import json
import math
import os
import random
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert  # noqa: E402

PACK = "gaia-generated"
VERSIONS = 24
MAX_RADIUS = 20  # crown radius, inside convert.MAX_REACH with a clump's edge
WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"}  # 1.21.1's
LEAVES = WOODS | {"azalea"}
DISCS = {1: [(0, 0)], 2: [(0, 0), (1, 0), (0, 1), (1, 1)], 3: [(x, z) for x in (-1, 0, 1) for z in (-1, 0, 1)],
         4: [(x, z) for x in range(-1, 3) for z in range(-1, 3) if (x, z) not in ((-1, -1), (-1, 2), (2, -1), (2, 2))]}
SPRAY = [(x, 0, z) for x in (-1, 0, 1) for z in (-1, 0, 1)] + [(0, 1, 0)]  # leaves round a conifer branch
SHAPES = {  # crown radius as a share of the widest, from the crown's bottom (0) to its top (1)
    "ellipsoid": lambda s: math.sqrt(max(0.0, 1 - (2 * s - 1) ** 2)),
    "dome": lambda s: math.sqrt(max(0.0, 1 - ((s - 0.3) / (0.7 if s > 0.3 else 0.5)) ** 2)),
    "umbrella": lambda s: min(1.0, 0.35 + s) if s < 0.65 else math.sqrt(max(0.0, 1 - ((s - 0.65) / 0.35) ** 2)),
    "cone": lambda s: 1 - 0.85 * s,
}

# h: height in metres (species.tsv's), bole: bare trunk as a share of the height, width: crown width as a share of
# the height, clump: leaf clump radius across and up, spacing: clumps apart in clump sizes (under 1 overlaps),
# stout: trunk width per metre of height, stocking: other bark low on the trunk (blocks, share of the height).
CROWN = dict(form="crown", stems=(1, 1), lean=0.0, crook=0.2, rise=0.1, bole=0.4, width=0.5, shape="ellipsoid",
             limbs=(3, 5), depth=2, clump=(3.0, 2.0), style="blob", spacing=1.2, leader=False, stout=0.04)
CONIFER = dict(form="conifer", bole=0.1, width=0.3, taper=1.0, whorl=2, droop=0.3, stout=0.035)
SPECIES = {
    # Australia
    "eucalyptus_pauciflora": dict(CROWN, h=(4, 20), stems=(1, 4), lean=0.12, crook=0.4, bole=0.2, width=0.9,
                                  shape="dome", limbs=(2, 4), clump=(2.5, 1.8), spacing=1.3,
                                  bark={"stripped_birch": 8, "stripped_acacia": 1}, leaves={"birch": 1}),
    "eucalyptus_delegatensis": dict(CROWN, h=(30, 60), bole=0.55, width=0.45, bark={"stripped_birch": 1},
                                    stocking=({"jungle": 1}, 0.35), leaves={"birch": 1}),
    "eucalyptus_melliodora": dict(CROWN, h=(15, 30), bole=0.3, width=0.8, shape="dome", crook=0.25, clump=(3, 2.2),
                                  spacing=1.0, bark={"stripped_birch": 4},
                                  stocking=({"oak": 1}, 0.25), leaves={"birch": 1}),
    "eucalyptus_microcarpa": dict(CROWN, h=(15, 25), bole=0.35, width=0.7, shape="dome", crook=0.25, clump=(3, 2.2),
                                  spacing=1.0, bark={"acacia": 1}, leaves={"birch": 1}),
    "eucalyptus_populnea": dict(CROWN, h=(10, 20), bole=0.3, width=0.8, shape="dome", clump=(3, 2.5), spacing=0.9,
                                bark={"acacia": 1}, leaves={"birch": 2, "oak": 1}),
    "eucalyptus_sideroxylon": dict(CROWN, h=(10, 30), bole=0.4, width=0.55, crook=0.3, clump=(2.5, 2), spacing=1.5,
                                   bark={"dark_oak": 4, "mangrove": 1}, leaves={"birch": 1}),
    "eucalyptus_crebra": dict(CROWN, h=(20, 35), bole=0.45, width=0.5, crook=0.25, clump=(2.5, 2), spacing=1.4,
                              bark={"dark_oak": 1}, leaves={"birch": 1}),
    "corymbia_maculata": dict(CROWN, h=(30, 45), bole=0.55, width=0.45, limbs=(3, 4), spacing=1.3,
                              bark={"stripped_birch": 6, "stripped_spruce": 1}, leaves={"birch": 1}),
    "eucalyptus_haemastoma": dict(CROWN, h=(5, 15), stems=(1, 2), lean=0.12, crook=0.4, bole=0.3, width=0.7,
                                  shape="dome", clump=(2.5, 2), spacing=1.4, bark={"stripped_birch": 5, "birch": 2},
                                  leaves={"birch": 1}),
    "eucalyptus_macrorhyncha": dict(CROWN, h=(15, 35), bole=0.4, width=0.55, bark={"jungle": 1}, leaves={"birch": 1}),
    "eucalyptus_eugenioides": dict(CROWN, h=(15, 30), bole=0.4, width=0.6, shape="dome", bark={"jungle": 3, "spruce": 1},
                                   leaves={"birch": 1}),
    "eucalyptus_tereticornis": dict(CROWN, h=(20, 50), bole=0.45, width=0.5, clump=(3, 2.2),
                                    bark={"stripped_birch": 6, "acacia": 1}, leaves={"birch": 1}),
    "eucalyptus_grandis": dict(CROWN, h=(45, 55), bole=0.6, width=0.4, stout=0.035, bark={"stripped_birch": 1},
                               stocking=({"jungle": 1}, 0.12), leaves={"birch": 1}),
    "lophostemon_confertus": dict(CROWN, h=(30, 40), bole=0.45, width=0.5, clump=(3, 2.5), spacing=0.9,
                                  bark={"stripped_cherry": 1}, stocking=({"spruce": 1}, 0.3), leaves={"oak": 1}),
    "eucalyptus_wandoo": dict(CROWN, h=(15, 25), bole=0.4, width=0.6, shape="dome", clump=(2.5, 2), spacing=1.3,
                              bark={"stripped_birch": 5}, leaves={"birch": 1}),
    "eucalyptus_gomphocephala": dict(CROWN, h=(30, 40), bole=0.35, width=0.7, shape="dome", clump=(3, 2.5),
                                     spacing=1.0, stout=0.05, bark={"acacia": 4, "oak": 1}, leaves={"birch": 1}),
    "xanthorrhoea": dict(form="grasstree", h=(1, 5), bark={"dark_oak": 1}, leaves={"jungle": 1}),
    # New Zealand
    "nothofagus_fusca": dict(CROWN, h=(25, 35), bole=0.4, width=0.6, limbs=(4, 6), clump=(3, 1.6), style="pad",
                             spacing=0.9, stout=0.06, bark={"spruce": 1}, leaves={"oak": 1}),
    "nothofagus_solandri": dict(CROWN, h=(15, 25), bole=0.35, width=0.55, clump=(2.5, 1.5), style="pad", spacing=1.0,
                                bark={"dark_oak": 1}, leaves={"dark_oak": 1}),
    "nothofagus_cliffortioides": dict(CROWN, h=(6, 15), stems=(1, 2), crook=0.3, bole=0.3, width=0.6, clump=(2.5, 1.5),
                                      style="pad", spacing=1.0, bark={"spruce": 1, "dark_oak": 1}, leaves={"dark_oak": 1}),
    "cordyline_australis": dict(CROWN, h=(6, 20), bole=0.45, width=0.6, shape="umbrella", limbs=(2, 4), crook=0.15,
                                clump=(2, 2), style="star", spacing=1.0, stout=0.03, bark={"acacia": 1},
                                leaves={"jungle": 1}),
    # South America
    "nothofagus_pumilio": dict(CROWN, h=(20, 30), bole=0.4, width=0.55, clump=(3, 1.8), style="pad", spacing=1.0,
                               bark={"acacia": 2, "oak": 1}, leaves={"oak": 1}),
    "nothofagus_dombeyi": dict(CROWN, h=(40, 50), bole=0.55, width=0.45, shape="umbrella", limbs=(4, 6),
                               clump=(3.5, 1.4), style="pad", spacing=1.0, stout=0.045, bark={"spruce": 1},
                               leaves={"dark_oak": 1}),
    "polylepis": dict(CROWN, h=(4, 12), stems=(1, 3), lean=0.15, crook=0.5, bole=0.25, width=0.8, shape="dome",
                      clump=(2.5, 1.6), spacing=1.1, bark={"stripped_mangrove": 3, "mangrove": 1, "stripped_acacia": 1},
                      leaves={"azalea": 1}),
    "alnus_acuminata": dict(CROWN, h=(15, 30), bole=0.35, width=0.4, shape="cone", leader=True, clump=(2.5, 2),
                            spacing=1.0, bark={"acacia": 1}, leaves={"oak": 1}),
    "podocarpus_oleifolius": dict(CROWN, h=(15, 25), bole=0.4, width=0.45, clump=(2.5, 2), spacing=0.9,
                                  bark={"spruce": 1}, leaves={"dark_oak": 1}),
    "cecropia": dict(CROWN, h=(10, 25), bole=0.6, width=0.5, shape="umbrella", limbs=(3, 5), depth=1, crook=0.1,
                     rise=0.25, clump=(3, 1), style="pad", spacing=1.0, stout=0.03, bark={"birch": 1},
                     leaves={"jungle": 1}),
    "cariniana_legalis": dict(CROWN, h=(40, 60), bole=0.6, width=0.5, shape="umbrella", limbs=(4, 6), spacing=1.1,
                              stout=0.05, bark={"jungle": 2, "spruce": 1}, leaves={"jungle": 1}),
    "austrocedrus_chilensis": dict(CONIFER, h=(10, 24), bole=0.08, width=0.3, taper=0.6, whorl=1, droop=0.1,
                                   bark={"spruce": 1}, leaves={"spruce": 1}),
    # North America
    "picea_engelmannii": dict(CONIFER, h=(25, 50), bole=0.08, width=0.32, whorl=3, droop=0.5, bark={"spruce": 1},
                              leaves={"spruce": 1}),
    "abies_lasiocarpa": dict(CONIFER, h=(15, 30), bole=0.04, width=0.22, taper=1.2, droop=0.15,
                             bark={"acacia": 1}, leaves={"spruce": 1}),
    "abies_fraseri": dict(CONIFER, h=(10, 25), width=0.38, droop=0.15, bark={"acacia": 1, "spruce": 1}, leaves={"spruce": 1}),
    "betula_alleghaniensis": dict(CROWN, h=(20, 30), bole=0.35, width=0.6, clump=(3, 2.2), spacing=1.0,
                                  bark={"stripped_birch": 3, "stripped_oak": 2}, stocking=({"oak": 1}, 0.15),
                                  leaves={"birch": 1}),
    "pinus_resinosa": dict(CROWN, h=(20, 35), bole=0.6, width=0.4, leader=True, clump=(2.5, 1.6), spacing=0.9,
                           bark={"mangrove": 1}, leaves={"spruce": 1}),
    "quercus_macrocarpa": dict(CROWN, h=(20, 30), bole=0.25, width=1.0, shape="dome", limbs=(4, 6), depth=3, crook=0.3,
                               rise=0.0, clump=(3, 2.2), spacing=1.0, stout=0.07, bark={"oak": 2, "dark_oak": 1},
                               leaves={"oak": 1}),
    "quercus_gambelii": dict(CROWN, h=(3, 10), stems=(2, 4), lean=0.1, crook=0.35, bole=0.25, width=0.8, shape="dome",
                             limbs=(2, 3), clump=(2.5, 2), spacing=1.0, bark={"acacia": 1}, leaves={"oak": 1}),
    "pinus_hartwegii": dict(CROWN, h=(20, 30), bole=0.5, width=0.45, leader=True, crook=0.3, clump=(3, 1.6), spacing=1.0,
                            bark={"dark_oak": 2, "spruce": 1}, leaves={"spruce": 1}),
    "pinus_montezumae": dict(CROWN, h=(20, 35), bole=0.5, width=0.45, leader=True, clump=(3, 1.8), spacing=1.0,
                             bark={"dark_oak": 1}, leaves={"spruce": 1}),
    "pinus_pseudostrobus": dict(CROWN, h=(20, 40), bole=0.55, width=0.4, leader=True, clump=(3, 1.8), spacing=1.0,
                                bark={"spruce": 1}, leaves={"spruce": 1}),
    "pinus_oocarpa": dict(CROWN, h=(15, 25), bole=0.45, width=0.5, crook=0.3, clump=(2.5, 1.6), spacing=1.0,
                          bark={"dark_oak": 1, "spruce": 1}, leaves={"spruce": 1}),
    "quercus_rugosa": dict(CROWN, h=(8, 20), bole=0.3, width=0.75, shape="dome", crook=0.3, clump=(3, 2.2), spacing=0.9,
                           bark={"dark_oak": 1}, leaves={"dark_oak": 1}),
    "quercus_laurina": dict(CROWN, h=(15, 30), bole=0.4, width=0.6, clump=(3, 2.2), spacing=0.9,
                            bark={"spruce": 1, "dark_oak": 1}, leaves={"dark_oak": 1}),
    # Europe
    "pinus_halepensis": dict(CROWN, h=(10, 25), lean=0.12, crook=0.35, bole=0.45, width=0.65, clump=(3, 2),
                             spacing=0.9, bark={"spruce": 2, "mangrove": 1}, leaves={"spruce": 1}),
    # For Earth's biomes that lacked their own trees (docs/BIOMES-PLAN.md): the Zagros, Baluchistan, the Colorado Plateau
    "quercus_brantii": dict(CROWN, h=(5, 15), stems=(1, 3), lean=0.1, crook=0.35, bole=0.25, width=0.9, shape="dome",
                            limbs=(3, 5), clump=(2.5, 1.8), spacing=1.1, bark={"dark_oak": 2, "oak": 1}, leaves={"oak": 1}),
    "juniperus_excelsa": dict(CROWN, h=(6, 18), lean=0.1, crook=0.4, bole=0.2, width=0.55, shape="cone", leader=True,
                              clump=(2, 1.8), spacing=0.9, bark={"stripped_spruce": 2, "spruce": 1}, leaves={"spruce": 1}),
    "juniperus_osteosperma": dict(CROWN, h=(3, 9), stems=(1, 3), lean=0.15, crook=0.5, bole=0.15, width=0.9, shape="dome",
                                  limbs=(2, 4), clump=(2, 1.6), spacing=0.9, bark={"stripped_spruce": 2, "stripped_birch": 1},
                                  leaves={"spruce": 1}),
    "pinus_edulis": dict(CROWN, h=(4, 12), stems=(1, 2), lean=0.1, crook=0.4, bole=0.2, width=0.85, shape="dome",
                         limbs=(3, 5), clump=(2.5, 1.8), spacing=0.9, bark={"spruce": 2, "dark_oak": 1}, leaves={"spruce": 1}),
    "pinus_tabuliformis": dict(CROWN, h=(10, 25), lean=0.1, crook=0.35, bole=0.5, width=0.7, shape="umbrella",
                               clump=(3, 1.6), spacing=0.9, bark={"stripped_spruce": 1, "spruce": 1}, leaves={"spruce": 1}),
    # North America
    "quercus_garryana": dict(CROWN, h=(10, 22), bole=0.3, width=0.9, shape="dome", limbs=(4, 6), depth=3, crook=0.4,
                             clump=(3, 2.2), spacing=1.0, stout=0.06, bark={"acacia": 2, "stripped_birch": 1},
                             leaves={"oak": 1}),
    "pinus_echinata": dict(CROWN, h=(20, 32), bole=0.6, width=0.4, leader=True, clump=(2.5, 1.6), spacing=0.9,
                           bark={"mangrove": 1, "spruce": 1}, leaves={"spruce": 1}),
    # The tropics' mountains
    "pinus_kesiya": dict(CROWN, h=(20, 35), bole=0.55, width=0.45, leader=True, crook=0.25, clump=(3, 1.8), spacing=1.0,
                         bark={"dark_oak": 1, "mangrove": 1}, leaves={"spruce": 1}),
    "quercus_costaricensis": dict(CROWN, h=(20, 35), bole=0.45, width=0.7, shape="dome", limbs=(4, 6), crook=0.3,
                                  clump=(3, 2), spacing=1.0, stout=0.05, bark={"dark_oak": 2, "stripped_dark_oak": 1},
                                  leaves={"dark_oak": 1}),
    "cyathea": dict(form="grasstree", h=(3, 10), bark={"dark_oak": 1}, leaves={"jungle": 1}),
    "dacrycarpus_compactus": dict(CONIFER, h=(5, 15), bole=0.1, width=0.4, taper=0.7, whorl=1, droop=0.1,
                                  bark={"dark_oak": 1}, leaves={"spruce": 1}),
    # The Arctic: dwarf birch and willow, knee-high
    "betula_nana": dict(CROWN, h=(1, 2), stems=(2, 4), lean=0.2, crook=0.5, bole=0.1, width=1.4, shape="dome", limbs=(2, 3),
                        depth=1, clump=(1.5, 1), spacing=0.9, stout=0.02, bark={"birch": 1, "dark_oak": 1}, leaves={"birch": 1}),
    "salix_arctica": dict(CROWN, h=(1, 2), stems=(2, 4), lean=0.25, crook=0.5, bole=0.05, width=1.6, shape="dome",
                          limbs=(2, 3), depth=1, clump=(1.5, 1), spacing=0.9, stout=0.02, bark={"mangrove": 1},
                          leaves={"birch": 1}),
    # Southern Africa: the Karoo, the Highveld, the fynbos, the Albany thicket
    "vachellia_karroo": dict(CROWN, h=(4, 12), stems=(1, 2), lean=0.1, crook=0.4, bole=0.3, width=0.9, shape="umbrella",
                             limbs=(3, 5), clump=(2.5, 1.5), spacing=1.0, bark={"dark_oak": 2, "acacia": 1},
                             leaves={"acacia": 1}),
    "boscia_albitrunca": dict(CROWN, h=(4, 8), bole=0.3, width=0.9, shape="dome", crook=0.35, clump=(2.5, 2),
                              spacing=0.9, bark={"stripped_birch": 3}, leaves={"oak": 1}),
    "searsia_lancea": dict(CROWN, h=(4, 9), stems=(1, 2), lean=0.1, crook=0.4, bole=0.2, width=1.0, shape="dome",
                           limbs=(3, 5), clump=(2.5, 1.8), spacing=1.0, bark={"dark_oak": 1}, leaves={"dark_oak": 1}),
    "protea_nitida": dict(CROWN, h=(3, 8), stems=(1, 2), lean=0.15, crook=0.55, bole=0.25, width=0.9, shape="dome",
                          limbs=(2, 4), clump=(2, 1.6), spacing=1.0, bark={"dark_oak": 2, "stripped_dark_oak": 1},
                          leaves={"spruce": 1}),
    "leucadendron_argenteum": dict(CROWN, h=(5, 10), bole=0.35, width=0.5, shape="ellipsoid", crook=0.2, clump=(2, 1.8),
                                   spacing=0.9, bark={"stripped_birch": 1, "acacia": 1}, leaves={"spruce": 1}),
    # The South Atlantic's and Indian Ocean's tussock islands: Tristan da Cunha, Gough, Amsterdam Island
    "phylica_arborea": dict(CROWN, h=(3, 6), stems=(1, 3), lean=0.3, crook=0.6, bole=0.2, width=1.0, shape="dome",
                            limbs=(2, 4), clump=(2, 1.5), spacing=0.9, stout=0.05, bark={"dark_oak": 1, "spruce": 1},
                            leaves={"spruce": 1}),
    "portulacaria_afra": dict(CROWN, h=(2, 5), stems=(2, 4), lean=0.15, crook=0.5, bole=0.15, width=1.1, shape="dome",
                              limbs=(3, 5), clump=(2, 1.6), spacing=0.8, stout=0.03, bark={"mangrove": 1}, leaves={"jungle": 1}),
}


def pick(rng, weights):
    r = rng.uniform(0, sum(weights.values()))
    for key, w in weights.items():
        r -= w
        if r <= 0:
            break
    return key


def rounded(p):
    return tuple(int(math.floor(c + 0.5)) for c in p)


def line(a, b):
    """The blocks from a to b, each touching the one before by a face."""
    a, b = rounded(a), rounded(b)
    n = max(abs(b[i] - a[i]) for i in range(3))
    out = [a]
    for k in range(1, n + 1):
        q = rounded([a[i] + (b[i] - a[i]) * k / n for i in range(3)])
        p = list(out[-1])
        for i in (1, 0, 2):  # up first, then out
            if p[i] != q[i]:
                p[i] = q[i]
                out.append(tuple(p))
    return out


def path(rng, a, b, crook, rise=0.0):
    """A crooked line from a to b: bent every few blocks by up to crook of a piece's length, bowed up by rise."""
    length = math.dist(a, b)
    n = max(1, int(length / 4))
    points = [a]
    for k in range(1, n):
        t, off = k / n, crook * length / n
        points.append((a[0] + (b[0] - a[0]) * t + rng.uniform(-off, off),
                       a[1] + (b[1] - a[1]) * t + rng.uniform(-off, off) / 2 + rise * length * math.sin(math.pi * t),
                       a[2] + (b[2] - a[2]) * t + rng.uniform(-off, off)))
    points.append(b)
    out = []
    for p, q in zip(points, points[1:]):
        out += line(p, q)[1 if out else 0:]
    return out


def by_azimuth(rng, centre, tips, k):
    """tips in k groups of neighbouring directions round centre."""
    turn = rng.uniform(0, 2 * math.pi)
    tips = sorted(tips, key=lambda q: (math.atan2(q[2] - centre[2], q[0] - centre[0]) - turn) % (2 * math.pi))
    return [g for g in (tips[i * len(tips) // k:(i + 1) * len(tips) // k] for i in range(k)) if g]


def centroid(points):
    return tuple(sum(p[i] for p in points) / len(points) for i in range(3))


def vary(rng, sp):
    """This tree's own proportions, so no two of a species are built alike."""
    v = dict(sp, width=sp["width"] * rng.uniform(0.8, 1.2), bole=min(0.85, max(0.03, sp["bole"] + rng.uniform(-0.08, 0.08))))
    v["lean"] = sp.get("lean", 0.0) + rng.uniform(0.0, 0.05)
    v["density"] = rng.uniform(0.75, 1.2)  # how full this one's foliage is: a crown in the open or a starved one
    if "limbs" in sp:
        v["limbs"] = (max(2, sp["limbs"][0] + rng.randint(-1, 0)), sp["limbs"][1] + rng.randint(0, 1))
    if "clump" in sp:
        k = rng.uniform(0.85, 1.15)
        v.update(clump=(sp["clump"][0] * k, sp["clump"][1] * k), spacing=sp["spacing"] * rng.uniform(0.85, 1.15))
    if "taper" in sp:
        v.update(taper=sp["taper"] * rng.uniform(0.85, 1.15), droop=sp["droop"] * rng.uniform(0.7, 1.3))
    return v


def lopsided(rng, strength=1.0):
    """How far a crown reaches each way, as a share of its radius: fuller to one side and flatter to another, as light,
    wind and neighbours shape real trees."""
    a1, p1 = rng.uniform(0.1, 0.3) * strength, rng.uniform(0, 2 * math.pi)
    a2, p2 = rng.uniform(0.0, 0.15) * strength, rng.uniform(0, 2 * math.pi)
    return lambda a: 1 + a1 * math.cos(a - p1) + a2 * math.cos(2 * (a - p2))


class Tree:
    def __init__(self, rng, sp, height):
        self.rng, self.sp, self.blocks = rng, sp, {}
        stocking = sp.get("stocking")
        self.stocking = (stocking[0], stocking[1] * height) if stocking else None

    def bark(self, p, kind="wood"):
        old = self.blocks.get(p)
        if old and convert.is_log(old[0]):
            return
        weights = self.stocking[0] if self.stocking and p[1] < self.stocking[1] else self.sp["bark"]
        self.blocks[p] = ["minecraft:%s_%s" % (pick(self.rng, weights), kind), {"axis": "y"}, None]

    def leaf(self, p):
        if p[1] >= 0 and p not in self.blocks:  # never below the foot of the trunk, in the ground
            self.blocks[p] = ["minecraft:%s_leaves" % pick(self.rng, self.sp["leaves"]), {}, None]

    def trunk(self, points, d0, d1):
        """Logs along points, d0 blocks across at the first narrowing to d1 at the last, on soil."""
        y0, y1 = points[0][1], points[-1][1]
        for x, y, z in points:
            for dx, dz in DISCS[max(1, int(d0 + (d1 - d0) * (y - y0) / max(1, y1 - y0) + 0.5))]:
                self.bark((x + dx, y, z + dz), "log")
        if y0 == 0:
            for dx, dz in DISCS[d0]:
                self.blocks[(points[0][0] + dx, -1, points[0][2] + dz)] = ["minecraft:dirt", {}, None]
            if d0 >= 2:  # a big trunk flares into roots at its foot
                x0, z0, half = points[0][0] + (d0 - 1) / 2, points[0][2] + (d0 - 1) / 2, d0 / 2
                for _ in range(self.rng.randint(3, 6)):
                    a = self.rng.uniform(0, 2 * math.pi)
                    for i in range(1, self.rng.randint(1, d0) + 1):
                        self.bark(rounded((x0 + (half + i - 0.5) * math.cos(a), 0, z0 + (half + i - 0.5) * math.sin(a))))

    def branch(self, a, b, thick=False):
        points = path(self.rng, a, b, self.sp["crook"], self.sp.get("rise", 0.0))
        for x, y, z in points:
            for dx, dz in DISCS[2 if thick else 1]:
                self.bark((x + dx, y, z + dz))
        return points

    def clump(self, centre, rh, rv, style):
        rng, c = self.rng, rounded(centre)
        self.bark(c)
        if style == "star":  # leaves raying out, the level ones drooping at the end
            n = max(1, int(rh + 0.5))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        if (dx or dy or dz) and n + min(dy, 0) > 0:
                            k = n + min(dy, 0)
                            end = (c[0] + dx * k, c[1] + dy * k - (dy == 0 and k > 1), c[2] + dz * k)
                            for p in line(c, end)[1:]:
                                self.leaf(p)
            return
        # Not one ball of leaves but a cloud of small sprays round the branch's end: a ragged edge with holes in it,
        # fuller on top where the light is and thin underneath; a pad's sprays lie flat, their outer ones hanging.
        pad = style == "pad"
        for _ in range(max(3, int(1.4 * rh * rh * self.sp.get("density", 1.0) + 0.5))):
            while True:
                x, y, z = rng.uniform(-1, 1), rng.uniform(-0.6, 0.5) if pad else rng.uniform(-0.7, 1), rng.uniform(-1, 1)
                if x * x + y * y + z * z <= 1:
                    break
            out = math.sqrt(rng.uniform(0.2, 1.0))  # more towards the skin than the middle
            sx, sy, sz = c[0] + x * rh * out, c[1] + y * rv * out, c[2] + z * rh * out
            if pad and math.hypot(x, z) > 0.6:
                sy -= 0.7
            r = rng.uniform(0.9, 1.7) * min(1.0, 0.3 + rh / 2)
            v = r * (rng.uniform(0.45, 0.65) if pad else rng.uniform(0.6, 0.9))
            R, V = int(math.ceil(r)), int(math.ceil(v))
            for dx in range(-R, R + 1):
                for dy in range(-V, V + 1):
                    for dz in range(-R, R + 1):
                        q = rounded((sx + dx, sy + dy, sz + dz))
                        if q[1] >= 1 and (dx / r) ** 2 + (dy / v) ** 2 + (dz / r) ** 2 <= 1 - rng.random() * 0.35:
                            self.leaf(q)  # never at the foot, on the ground
            if math.dist((sx, sy, sz), c) > 2.8:  # a far spray on a twig of its own, so its leaves are in reach of wood
                for p in line(c, (sx, sy, sz))[1:-1]:
                    if p[1] >= 1:
                        self.bark(p)


def crown(rng, height, sp):
    sp = vary(rng, sp)
    tree = Tree(rng, sp, height)
    width = min(sp["width"] * height / 2, MAX_RADIUS)
    rh = max(1.5, min(sp["clump"][0], 0.6 * width))  # a small crown has smaller clumps
    rv = max(1.2, rh * sp["clump"][1] / sp["clump"][0])
    base = max(1, int(height * sp["bole"] + 0.5))
    top = max(base + 1, height - rv)
    radius = max(1.0, width - rh * 0.6)
    lean, phi = sp["lean"] * height, rng.uniform(0, 2 * math.pi)

    def axis(y):
        return lean * math.cos(phi) * y / height, lean * math.sin(phi) * y / height

    # Clumps through the crown's outline, kept apart, more towards its skin than its middle. The outline is lopsided,
    # and its bottom higher on one side, where lower branches died off in the shade.
    low, shape, gap = min(base + rv * 0.5, top - 1), SHAPES[sp["shape"]], sp["spacing"] * (rh + rv) * 0.75
    side, shade, lift = lopsided(rng), rng.uniform(0, 2 * math.pi), rng.uniform(0, 0.3) * (top - low)
    tips = [(axis(top)[0] + rng.uniform(-1, 1), top, axis(top)[1] + rng.uniform(-1, 1))]
    misses, enough = 0, 2 if height < 8 else 5
    while len(tips) < 200 and (misses < 300 or len(tips) < enough and gap > 1):
        if misses >= 300:  # a thin crown: closer clumps, rather than a bare pole
            misses, gap = 0, gap * 0.75
        y, a = rng.uniform(low, top), rng.uniform(0, 2 * math.pi)
        if y < low + lift * (1 + math.cos(a - shade)) / 2:
            misses += 1
            continue
        r = min(radius * shape((y - low) / max(1e-6, top - low)) * side(a), MAX_RADIUS - rh) * math.sqrt(rng.uniform(0.1, 1.0))
        ax, az = axis(y)
        p = (ax + r * math.cos(a), y, az + r * math.sin(a))
        if all(math.dist(p, q) >= gap for q in tips):
            tips.append(p)
            misses = 0
        else:
            misses += 1

    stems = rng.randint(*sp["stems"]) if height >= 4 else 1
    d0 = min(4, max(1, int(height * sp["stout"] * (0.75 if stems > 1 else 1) + 0.5)))
    d1 = max(1, d0 - 1)
    ax, az = axis(base)
    for (fx, fz), group in zip(((0, 0), (1, 0), (0, 1), (-1, 0)), by_azimuth(rng, (ax, 0, az), tips, stems)):
        fork = (ax, base, az)
        if stems > 1:  # stems from one rootstock lean out towards their own share of the crown
            c = centroid(group)
            out = math.hypot(c[0] - ax, c[2] - az) or 1
            reach = min(0.35 * base, out * 0.5)
            fork = (fx + ax + (c[0] - ax) / out * reach, base * rng.uniform(0.85, 1.1), fz + az + (c[2] - az) / out * reach)
        tree.trunk(path(rng, (fx, 0, fz), fork, sp["crook"] / 3), d0, d1)
        if sp["leader"]:
            leader = path(rng, fork, (axis(top)[0], top - 1, axis(top)[1]), sp["crook"] / 3)
            tree.trunk(leader, d1, 1)
            for q in group:
                lx, lz = axis(q[1])
                y = min(max(q[1] - 0.4 * math.hypot(q[0] - lx, q[2] - lz) - 1, fork[1]), top - 1)
                tree.branch(min(leader, key=lambda p: abs(p[1] - y)), q)
        else:  # the trunk runs on a little among its limbs, so they part at different heights
            stem = line(fork, (fork[0], fork[1] + rng.uniform(0, 0.2) * (top - fork[1]), fork[2]))
            tree.trunk(stem, d1, d1)
            grow(tree, stem, group, sp["depth"], d0 >= 3, rng.randint(*sp["limbs"]))
    for q in tips:
        if sp["style"] != "star" and q is not tips[0] and rng.random() < 0.06:
            continue  # a dead branch, bare
        k = rng.uniform(0.75, 1.15)
        tree.clump(q, max(1.2, rh * k), max(1.0, rv * k), sp["style"])
    return tree.blocks


def grow(tree, starts, tips, depth, thick, limbs):
    """Branches from any of starts reaching every tip: limbs towards groups of neighbouring tips, forking again."""
    rng = tree.rng
    if depth <= 0 or len(tips) <= 1:
        for q in tips:
            tree.branch(rng.choice(starts), q)
        return
    for group in by_azimuth(rng, starts[0], tips, min(len(tips), limbs)):
        start, c = rng.choice(starts), centroid(group)
        f = rng.uniform(0.45, 0.65)
        end = (start[0] + (c[0] - start[0]) * f, max(start[1], start[1] + (c[1] - start[1]) * f),
               start[2] + (c[2] - start[2]) * f)
        tree.branch(start, end, thick)
        grow(tree, [end], group, depth - 1, False, rng.randint(2, 3))


def conifer(rng, height, sp):
    sp = vary(rng, sp)
    tree = Tree(rng, sp, height)
    top = height - 2
    lean, phi = sp["lean"] * height * 0.5, rng.uniform(0, 2 * math.pi)  # a slight sweep
    trunk = path(rng, (0, 0, 0), (lean * math.cos(phi), top, lean * math.sin(phi)), 0.05)
    tree.trunk(trunk, max(1, int(height * sp["stout"] + 0.5)), 1)
    centre = {y: (x, z) for x, y, z in trunk}
    base = max(1, int(height * sp["bole"] + 0.5))
    radius, side = min(sp["width"] * height / 2, MAX_RADIUS), lopsided(rng, 0.8)
    whorl = base
    for y in range(base, top + 1):
        cx, cz = centre[y]
        r = radius * (1 - (y - base) / max(1, top - base)) ** sp["taper"] * rng.uniform(0.85, 1.1)
        near = max(1.0, r * 0.4 * rng.uniform(0.8, 1.1))  # foliage close round the trunk, narrowing to the spire
        for dx in range(-int(near), int(near) + 1):
            for dz in range(-int(near), int(near) + 1):
                if dx * dx + dz * dz <= near * near and (dx == dz == 0 or rng.random() < 0.85):
                    tree.leaf((cx + dx, y, cz + dz))
        if r < 1 or y < whorl:
            continue
        whorl = y + sp["whorl"] + (rng.random() < 0.2)  # now and then a longer gap
        n = rng.randint(5, 8) if r >= 3 else 4
        if rng.random() < 0.1:  # a thin whorl
            n = max(2, n - 3)
        turn = rng.uniform(0, 2 * math.pi)
        for i in range(n):
            if rng.random() < 0.08:
                continue  # a branch broken off
            a = turn + 2 * math.pi * i / n + rng.uniform(-0.4, 0.4)
            length = min(max(1.0, r * side(a) * rng.uniform(0.55, 1.15)), MAX_RADIUS)
            # Level near the trunk, drooping towards the tip.
            mid = (cx + 0.6 * length * math.cos(a), max(1, y - sp["droop"] * length * 0.15), cz + 0.6 * length * math.sin(a))
            end = (cx + length * math.cos(a), max(1, y - sp["droop"] * length * rng.uniform(0.4, 0.7)), cz + length * math.sin(a))
            limb = line((cx, y, cz), mid)[1:] + line(mid, end)[1:]
            for k, (x, by, z) in enumerate(limb):
                tree.bark((x, by, z))
                for dx, dy, dz in SPRAY:
                    if rng.random() < 0.85:
                        tree.leaf((x + dx, by + dy, z + dz))
                if k >= len(limb) // 3 and k % 2 == 1 and rng.random() < 0.5:  # a side twig, leafy
                    side_a = a + rng.choice((-1, 1)) * math.pi / 2
                    for j in range(1, rng.randint(1, 2) + 1):
                        tx, tz = rounded((x + j * math.cos(side_a), 0, z + j * math.sin(side_a)))[::2]
                        for dx, dy, dz in SPRAY:
                            if rng.random() < 0.7:
                                tree.leaf((tx + dx, by - (j > 1) + dy, tz + dz))
    # Below the crown, the stubs of branches that died in the shade.
    for y in range(max(1, base - 4), base):
        if rng.random() < 0.3:
            cx, cz = centre[y]
            a = rng.uniform(0, 2 * math.pi)
            for x, by, z in line((cx, y, cz), (cx + rng.uniform(1, 3) * math.cos(a), y, cz + rng.uniform(1, 3) * math.sin(a)))[1:]:
                tree.bark((x, by, z))
    cx, cz = centre[top]
    for dx, dy, dz in [(0, 1, 0), (0, 2, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)]:
        tree.leaf((cx + dx, top + dy, cz + dz))
    if height >= 12 and rng.random() < 0.08:  # now and then a forked top, where the leader was lost
        ox, oz = rng.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
        for dy in range(0, 3):
            tree.bark((cx + ox, top - 1 + dy, cz + oz), "log")
        for dx, dy, dz in SPRAY:
            tree.leaf((cx + ox + dx, top + 2 + dy, cz + oz + dz))
    return tree.blocks


def grasstree(rng, height, sp):
    tree = Tree(rng, sp, height)
    tuft = 2 if height >= 4 else 1
    stem = max(0, height - 1 - tuft)
    tree.trunk([(0, y, 0) for y in range(stem + 1)], 1, 1)
    heads = [(0, stem, 0)]
    if stem >= 2 and rng.random() < 0.35:  # old ones fork
        heads = [(dx, stem, dz) for dx, dz in rng.sample([(1, 0), (-1, 0), (0, 1), (0, -1)], rng.randint(2, 3))]
        for h in heads:
            tree.bark((h[0], stem - 1, h[2]), "log")
            tree.bark(h, "log")
    for x, y, z in heads:
        tree.clump((x, y, z), tuft, 1, "star")
        if rng.random() < 0.4:  # in flower: a spike above the tuft
            for dy in range(1, tuft + rng.randint(1, 2)):
                tree.blocks[(x, y + dy, z)] = ["minecraft:bamboo_fence", {}, None]
    return tree.blocks


FORMS = {"crown": crown, "conifer": conifer, "grasstree": grasstree}


def heights(name):
    """The heights its versions grow to, in blocks at a block a metre: scattered from young to its tallest, so a stand
    finds several versions near any height."""
    sp, rng = SPECIES[name], random.Random(name)
    lo, hi = sp["h"]
    lo = lo if sp["form"] == "grasstree" else max(3.0, lo / 2)
    return [int(lo * (hi / lo) ** ((i + rng.random()) / VERSIONS) + 0.5) for i in range(VERSIONS)]


def grow_tree(name, n, height):
    blocks = FORMS[SPECIES[name]["form"]](random.Random("%s:%d" % (name, n)), height, SPECIES[name])
    convert.relink_leaves(blocks)
    for p in [p for p, b in blocks.items() if convert.is_leaves(b[0]) and b[1]["distance"] == "7"]:
        del blocks[p]
    return blocks


def main(out):
    target = os.path.join(out, PACK)
    os.makedirs(target, exist_ok=True)
    for old in os.listdir(target):
        if old.endswith(".nbt"):
            os.remove(os.path.join(target, old))
    trees, skipped = [], []
    for name, sp in SPECIES.items():
        for n, height in enumerate(heights(name), 1):
            file = "%s_%d.nbt" % (name, n)
            try:
                template, info = convert.convert(grow_tree(name, n, height), None)
            except convert.Skip as e:
                skipped.append({"source": file, "reason": str(e)})
                continue
            convert.write_nbt(os.path.join(target, file), template)
            trees.append(dict(file=file, source="generate.py", **info))
    with open(os.path.join(target, "trees.json"), "w", encoding="utf-8") as f:
        json.dump({"pack": PACK, "trees": trees, "skipped": skipped}, f, indent=1)
    print("%s: %d trees of %d species, %d skipped" % (PACK, len(trees), len(SPECIES), len(skipped)))
    for s in skipped:
        print("  skipped %s: %s" % (s["source"], s["reason"]))


# Previews: each version of a species side by side, seen from the south, nearer blocks brighter.
BARK_COLOURS = {"oak": (109, 85, 50), "spruce": (58, 37, 17), "birch": (216, 215, 210), "jungle": (86, 68, 26),
                "acacia": (103, 96, 86), "dark_oak": (60, 46, 26), "mangrove": (84, 52, 40), "cherry": (54, 33, 44),
                "pale_oak": (88, 80, 76)}
STRIPPED_COLOURS = {"oak": (177, 144, 86), "spruce": (116, 90, 52), "birch": (196, 176, 118), "jungle": (171, 132, 84),
                    "acacia": (175, 92, 59), "dark_oak": (96, 76, 49), "mangrove": (119, 54, 47),
                    "cherry": (215, 145, 148), "pale_oak": (238, 230, 226)}
LEAF_COLOURS = {"oak": (60, 120, 30), "spruce": (61, 94, 61), "birch": (128, 167, 85), "jungle": (48, 140, 20),
                "acacia": (90, 130, 30), "dark_oak": (40, 95, 20), "mangrove": (80, 120, 30), "cherry": (230, 170, 200),
                "azalea": (90, 120, 45), "pale_oak": (130, 135, 120)}


def colour(name):
    n = name.split(":")[-1]
    if n.endswith("_leaves"):
        return LEAF_COLOURS.get(n[:-7], (80, 140, 60))
    if n.endswith(("_log", "_wood")):
        wood = n.rsplit("_", 1)[0]
        if wood.startswith("stripped_"):
            return STRIPPED_COLOURS.get(wood[9:], (150, 120, 80))
        return BARK_COLOURS.get(wood, (90, 70, 50))
    return None if n == "dirt" else (200, 190, 120)


def render(trees, scale=3):
    """Side views of trees ({(x, y, z): [name, ...]} each) next to each other, as rows of RGB bytes."""
    views, top = [], 1
    for blocks in trees:
        xs, zs = [p[0] for p in blocks], [p[2] for p in blocks]
        front = {}
        for (x, y, z), b in blocks.items():
            c = colour(b[0])
            if c and y >= 0 and ((x, y) not in front or z > front[(x, y)][0]):
                front[(x, y)] = (z, c)
        z0, z1 = min(zs), max(zs)
        views.append((min(xs), max(xs) - min(xs) + 1, {k: tuple(int(ch * (0.55 + 0.45 * (z - z0) / max(1, z1 - z0))) for ch in c)
                                                      for k, (z, c) in front.items()}))
        top = max([top] + [y + 1 for _, y in front])
    width = sum(w + 3 for _, w, _ in views) + 3
    rows = [bytearray(b"\xc8\xdc\xf0" * width * scale) for _ in range((top + 2) * scale)]
    left = 3
    for x0, w, front in views:
        for (x, y), c in front.items():
            for sy in range(scale):
                row = rows[(top - 1 - y) * scale + sy]
                for sx in range(scale):
                    i = ((left + x - x0) * scale + sx) * 3
                    row[i:i + 3] = bytes(c)
        left += w + 3
    for row in rows[top * scale:]:
        row[:] = b"\x6e\x55\x32" * width * scale
    return rows


def write_png(path, rows):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", len(rows[0]) // 3, len(rows), 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(b"".join(b"\x00" + bytes(r) for r in rows), 9)) + chunk(b"IEND", b""))


def preview(folder, names):
    os.makedirs(folder, exist_ok=True)
    for name in names or SPECIES:
        trees = [grow_tree(name, n, h) for n, h in enumerate(heights(name), 1)][::3]
        write_png(os.path.join(folder, name + ".png"), render(trees, 4 if max(SPECIES[name]["h"]) <= 40 else 3))


def self_test():
    for name, sp in SPECIES.items():
        for weights, valid in ((sp["bark"], WOODS), (sp.get("stocking", ({},))[0], WOODS), (sp["leaves"], LEAVES)):
            for key in weights:
                assert key.replace("stripped_", "", 1) in valid, (name, key)
    for name in ("eucalyptus_pauciflora", "pinus_resinosa", "abies_lasiocarpa", "cordyline_australis", "xanthorrhoea"):
        for n, height in enumerate(heights(name), 1):
            template, info = convert.convert(grow_tree(name, n, height), None)
            assert info["ground"] == "soil" and info["stuck_leaves"] == 0 and info["leaf_count"] > 0, (name, height, info)
            assert info["depth"] == 0 and info["reach"] <= convert.MAX_REACH, (name, height, info)
            assert abs(info["height"] - height) <= max(2, height // 4), (name, height, info["height"])
    assert grow_tree("polylepis", 3, 8) == grow_tree("polylepis", 3, 8)
    assert line((0, 0, 0), (3, 2, -1))[-1] == (3, 2, -1)
    assert all(sum(abs(p[i] - q[i]) for i in range(3)) == 1 for p, q in zip(line((0, 0, 0), (5, 3, 4)), line((0, 0, 0), (5, 3, 4))[1:]))
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif sys.argv[1:2] == ["--preview"] and len(sys.argv) >= 3:
        preview(sys.argv[2], sys.argv[3:])
    elif len(sys.argv) == 2:
        main(sys.argv[1])
    else:
        sys.exit(__doc__)
