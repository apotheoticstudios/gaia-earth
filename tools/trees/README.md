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
python3 convert.py sources.tsv paleozoey-europe packs/paleozoey-europe out --unapproved
```

That writes `out/paleozoey-europe/` (ignored by git): each tree as a `.nbt` template, and `trees.json` with
each tree's size, the foot of its trunk, how the ground was found, its reach, main log and leaf blocks and
leaf counts, plus every file skipped and why. Check the summary it prints: trees whose ground was found from
the `trunk` or `bottom` are worth a look in game, and many leaves out of reach of a log means a pack isn't
survival-friendly.

`python3 convert.py --self-test` converts a few made-up trees and checks the result.
