# Gaia Dynamic Trees conversion

Gaia contains **4,454 verified Dynamic Trees variants** across 750 species labels. This recovers 607 variants that the earlier conversion excluded. The original 4,456 schematics and builder credits are preserved in the converter's `build/gaia-library/originals` snapshot.

The converter supports branches up to 128 blocks tall, roots up to eight blocks below the trunk foot, and a 24-block horizontal reach. The tallest recovered tree is 114 blocks high. Thick sections preserve solid trunks, and several connected cores can represent a trunk wider than a single Dynamic Trees section.

All 91 trees identified by the earlier thin-trunk audit were rebuilt. The final library passed the proportion audit with zero outliers. The six final repairs prioritise trunk proportions and have sparser crowns than the earlier conversions. Their foliage uses the original materials. Mixed wood palettes use the main trunk’s family in 1,014 variants, as requested.

## Validation

- The Minecraft server loaded all 4,454 assets. Its placement check covered 111 Dynamic Trees species profiles and 904 placements, including tall trees, spreading roots and thick trunks.
- Fresh Gaia terrain generated 4,883 dynamic branches and 76 saved root species. The terrain check found zero static tree blocks.
- The client verified 20,944 colour comparisons across 187 foliage blocks, 173 original foliage models and 22 wide trunk bark models.
- The built mod contains 4,454 referenced templates, including 249 bare specimens and 325 thick trunk feet. The archive audit found zero orphan templates or static tree blocks.

## Excluded source entries

| Entry | Source contents |
| --- | --- |
| `malkazaryt-100-trees/burntforest.nbt` | Two coal ore blocks |
| `paleozoey-tropical-asia/rafflesia_arnoldii1.nbt` | A rafflesia flower made from red mushroom blocks and crimson roots |

[Per-template conversion report](src/main/resources/realearth/trees/dynamic-conversion-report.json)

[Gaia Earth 0.2.0 for Fabric 1.21.1](build/libs/gaia-earth-0.2.0.jar)
