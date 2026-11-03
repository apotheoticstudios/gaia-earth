# Tree packs

Turns builders' tree schematics into vanilla structure templates for Earth's trees. Not part of the Gradle
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
`high` within 200 m of it), a latitude and longitude box, and a weight. Without trees installed, or where no
line fits, vanilla's trees grow.

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
