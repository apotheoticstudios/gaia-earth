# Earth's biomes

Writes Earth's own biomes into the mod's data pack: `src/main/resources/data/gaia-earth/worldgen/biome/`, their
places in vanilla's biome tags (`data/minecraft/tags/worldgen/biome/`), and their names (`assets/gaia-earth/lang`).
Where each biome is, and which trees grow in it, is `world/EarthBiomes.java`; this sets what each one is made from
(a vanilla parent's ground cover, mobs, music and weather) and its colours. The two lists must name the same biomes;
the "Earth's biomes" self-test checks they do.

```bash
python3 build_biomes.py ~/.gradle/caches/fabric-loom/26.1/minecraft-common.jar ../../src/main/resources
```

Run it again after changing `BIOMES` or updating Minecraft (it copies the parent biomes from the game's jar).
