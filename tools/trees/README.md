# Tree packs

Imports builders' tree schematics as intermediate vanilla structures, which the offline Dynamic Trees baker converts into Earth's playable tree assets. Not part of the Gradle
build. What the converter does to each tree is described at the top of `convert.py`.

`sources.tsv` lists every pack considered, with its page, the author's terms quoted as published, and a status:

- `ok`: the terms allow sharing it, so its trees may ship with the mod (credit the author anyway).
- `ask`: waiting for the author's permission. Convert it only for local testing, with `--unapproved`, and
  don't commit what it makes. Once permission comes, note where and when in the `note` column and change the
  status to `ok`.
- `no`: its terms rule it out. Kept so it isn't looked at again.

Needs only Python 3.9+. Download a pack from its page into its own folder under `packs/` (ignored by git),
then:

```bash
python3 convert.py sources.tsv paleozoey-europe packs/paleozoey-europe ../../src/main/resources/data/gaia-earth/structure/tree --unapproved
```

That writes the pack into the mod's resources, where the game finds it (`gaia-earth:tree/<pack id>/...`); the
folder is ignored by git until the packs' permissions are settled. Each tree is a `.nbt` template, and
`trees.json` has each tree's size, the foot of its trunk, how the ground was found, its reach, main log and
leaf blocks and leaf counts, plus every file skipped and why.

Which species grows where is `src/main/resources/realearth/trees/species.tsv`: per species (its file names
without the trailing number; a comma list, first named, joins trees named differently or from other packs
into one species, like `pinus_pinaster,l_mar,m_mar,s_mar`), the vanilla trees it stands in for (`spruce` for spruces and pines, `birch`,
`oak`, `swamp` for oaks in swamps, `dark_oak`, `acacia`, `jungle`, `mangrove`, `cherry`), the Köppen climates
it grows in (by prefix: `Cs` is every Mediterranean climate), its heights (`low`, `mid` from half the treeline,
`high` within 200 m of it), a latitude and longitude box, and a weight. The runtime accepts verified dynamic assets only. Where no geographic row fits, it chooses a verified dynamic fallback of the matching kind.

Check the summary the converter prints: trees whose ground was found by the `roots` rule or from the `bottom`
are worth a look in game, and many leaves out of reach of a log means a pack isn't survival-friendly. The
`ground` column in sources.tsv says how a pack's trees meet the ground: empty trusts a paste origin saved at
the foot of the trunk, `roots` ignores it for builders who always saved from the bottom (paleozoey), and
`bottom` stands every tree on its lowest layer.

`python3 convert.py --self-test` converts a few made-up trees and checks the result.

## Generated trees

Species no pack has are grown by `generate.py`: each is a few numbers in its `SPECIES` table (height, bare
trunk, crown width and shape, stems and limbs, leaf clumps, bark and leaf blocks), grown 24 times at scattered
heights, each tree with its own proportions and a lopsided crown, and saved through the converter's own steps. They are the project's own work, so they need no permission:

```bash
python3 generate.py ../../src/main/resources/data/gaia-earth/structure/tree
python3 generate.py --preview /tmp/trees eucalyptus_pauciflora   # side views as PNG
python3 generate.py --self-test
```

Every run makes the same trees, as each is seeded by its species and version. Change a species' numbers and
look at its preview before adding it to species.tsv.

## Dynamic Trees runtime library

Gaia's playable tree library now accepts only offline-baked Dynamic Trees assets. The schematic conversion and
plant-map species selection remain separate: source names and the species table retain their geographic roles.
The runtime places the saved branches, leaves and root species intact. It does not convert existing trees in
worlds, and it does not stretch the trunk of a baked tree to change its height. It chooses a verified variant
near the requested height instead. Forest and street-tree fallbacks also use the installed dynamic library.

The offline converter is the sibling project `../gaia-tree-converter`. To rebuild from a new source library,
keep the original vanilla templates outside the active resources and run, from that project:

```sh
python3 tools/prepare_gaia_library.py ../gaia-earth build/new-library --source build/gaia-library/originals
GAIA_BAKE_EULA=../gaia-earth/run-selftest/eula.txt \
GAIA_BAKE_MIXED_WOOD=trunk \
GAIA_BAKE_SPECIES_TABLE=../gaia-earth/src/main/resources/realearth/trees/species.tsv \
./bake --pack build/new-library/originals build/new-library/mapping.json build/new-library/converted
python3 tools/install_gaia_library.py ../gaia-earth build/new-library build/new-library/converted
```

Use new output/snapshot folders for each run. The installer validates the conversions and original hashes,
keeps the previous library under `build/tree-library-before-dynamic`, and installs the verified templates at
their original pack-relative paths. It also embeds their Dynamic Trees definitions and vanilla leaf-model
assets in Gaia's resources, so clients and servers load the same block and species definitions automatically.
No separate converter mod or manually installed resource pack is needed in Gaia.

`src/main/resources/realearth/trees/dynamic-conversion-report.json` records coverage and every excluded source.
Failed conversions never remain as static fallback templates. The originals and builder credits are retained
in the external snapshot; conversion does not change the source packs' sharing permissions. Their existing
ignored-resource policy still applies.

`./gradlew verifyDynamicTreeLibrary` rejects unverified active manifests. `./gradlew runDynamicTreeAssets`
uses the isolated `run-dynamic-tree-check` server to load every installed template, reject static tree blocks,
and check root-species restoration, eight placement orientations, blocked placements and complete felling
for a specimen of every installed Dynamic Trees species profile. Set up its accepted EULA as for the other
local development servers. The integration check prints `DYNAMIC_TREE_ASSETS_PASS` on success.

Mixed-wood schematics use the main trunk's wood family throughout the living network, while retaining their source leaf materials. The export records adapted wood positions explicitly; these are material adaptations rather than exact wood-palette matches. Source shapes that still cannot pass the native checks remain excluded and listed in the report.

The current local library contains **3,847 verified templates across 596 Gaia species** from 4,456 source templates.
853 conversions adapt mixed wood palettes to the main trunk family. 609 source variants remain excluded; their
individual reasons and original hashes are retained in the report and external source snapshot. Leafless source
trees remain bare, with fertility zero, instead of receiving an invented canopy.

Gaia explicitly applies the native analysis depth required by these assets at startup and after data reload,
because another mod's default tree pack can otherwise override the included family settings. Its plant map
places the baked trees; the independent Dynamic Trees world-generation feature is omitted on Earth.

Leaf faces delegate their tint to the original primitive leaf's vanilla colour provider, including biome tints,
fixed birch/spruce colours, and naturally coloured cherry/azalea textures. Run `./gradlew runTreeColours` for the
isolated client check: it verifies the real colour registry, Sodium providers, and actual loaded leaf models,
then closes without opening a saved world.
