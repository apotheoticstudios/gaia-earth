# Mountain heights (relief.bin)

How high the highest ground within 150 km of each place reaches, every 0.2 degrees. New worlds keep land true
to scale wherever that fits under the build limit, and scale a range that doesn't down by one factor worked
out from it (`terrain/VerticalScale`).

```
python build_relief.py <cache.npy> <out.bin>
```

Needs Python 3.10+ with numpy, scipy and pillow (with WebP). Reads CHELSA V2.1's elevation grid in full (3.6 GB,
saved to `<cache.npy>` as the highest point of each 0.1 degree cell, so a second run skips it) and about 15,000
elevation tiles at the regional summits. The steps, and why, are in the script's header. It checks that no
ground in the grid stands above its own regional height, and prints a few summits against their surveyed
heights. Copy the output to `src/main/resources/realearth/relief.bin`.
