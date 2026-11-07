package net.apotheoticstudios.gaiaearth;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.apotheoticstudios.gaiaearth.command.AreaDownload;
import net.apotheoticstudios.gaiaearth.command.EarthCommand;
import net.apotheoticstudios.gaiaearth.data.DiskCache;
import net.apotheoticstudios.gaiaearth.data.TileStore;
import net.apotheoticstudios.gaiaearth.geo.Projection;
import net.apotheoticstudios.gaiaearth.landcover.WorldCover;
import net.apotheoticstudios.gaiaearth.osm.OsmWater;
import net.apotheoticstudios.gaiaearth.portal.EarthPortalBlock;
import net.apotheoticstudios.gaiaearth.portal.PortalFrame;
import net.apotheoticstudios.gaiaearth.portal.PortalTravel;
import net.apotheoticstudios.gaiaearth.terrain.TerrainField;
import net.apotheoticstudios.gaiaearth.terrain.VerticalScale;
import net.apotheoticstudios.gaiaearth.world.DegradedChunks;
import net.apotheoticstudios.gaiaearth.world.EarthBiomeSource;
import net.apotheoticstudios.gaiaearth.world.EarthChunkGenerator;

public class RealEarth implements ModInitializer {
	public static final String MOD_ID = "gaia-earth";
	public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
		.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	public static final Logger LOGGER = LoggerFactory.getLogger("RealEarth");

	public static final ResourceKey<Level> EARTH = ResourceKey.create(Registries.DIMENSION, id("earth"));
	public static final ResourceKey<DimensionType> EARTH_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, id("earth"));

	public static final Block PORTAL = Registry.register(BuiltInRegistries.BLOCK, id("earth_portal"), new EarthPortalBlock(
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_BLUE)
			.noCollission()
			.strength(-1.0F)
			.sound(SoundType.GLASS)
			.lightLevel(state -> 11)
			.pushReaction(PushReaction.BLOCK)
			.noLootTable()
	));

	private static EarthConfig config = new EarthConfig();
	private static volatile TerrainField terrain;
	private static volatile MinecraftServer server;
	private static ExecutorService auxDownloads;

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	public static MinecraftServer server() {
		return server;
	}

	/** True in a world created with the Project Gaia: Earth world type: the Overworld itself is Earth. */
	public static boolean earthIsOverworld(MinecraftServer server) {
		return server.overworld().getChunkSource().getGenerator() instanceof EarthChunkGenerator;
	}

	/** Where Earth is in this world: the Overworld for the Earth world type, else the Earth dimension. */
	public static ServerLevel earthLevel(MinecraftServer server) {
		return earthIsOverworld(server) ? server.overworld() : server.getLevel(EARTH);
	}

	/** True for any level built by the Earth generator, on either side. */
	public static boolean isEarth(Level level) {
		return level.dimensionTypeRegistration().is(EARTH_TYPE);
	}

	public static EarthConfig config() {
		return config;
	}

	/** The running server's terrain, or null when no server is running. */
	public static TerrainField terrain() {
		return terrain;
	}

	@Override
	public void onInitialize() {
		config = EarthConfig.load(FabricLoader.getInstance().getConfigDir().resolve("realearth.json"));

		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.STRUCTURE_PLACEMENT, id("built_up"), net.apotheoticstudios.gaiaearth.world.BuiltUpPlacement.TYPE);
		Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, id("town_map"), net.apotheoticstudios.gaiaearth.world.TownMapFunction.TYPE);

		net.apotheoticstudios.gaiaearth.world.HereLink.register();
		net.apotheoticstudios.gaiaearth.world.MapWaypoints.register();
		net.apotheoticstudios.gaiaearth.world.ClimateLink.register();
		net.apotheoticstudios.gaiaearth.hydrology.RiverCurrentDemo.register();
		net.apotheoticstudios.gaiaearth.hydrology.MappedCurrents.register();

		ServerLifecycleEvents.SERVER_STARTING.register(RealEarth::start);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			stop();
			net.apotheoticstudios.gaiaearth.world.Places.clear();
			net.apotheoticstudios.gaiaearth.world.Milestones.clear();
			net.apotheoticstudios.gaiaearth.world.PerfLog.clear();
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
			EarthCommand.register(dispatcher, config.teleportPermissionLevel));
		UseBlockCallback.EVENT.register(RealEarth::onUseBlock);
		UseBlockCallback.EVENT.register(RealEarth::onBlockChange);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
			if (isEarth(level)) {
				DegradedChunks.touch(level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4);
			}
		});
		// Attribution where the data is seen: arriving on Earth through a portal.
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, from, to) -> {
			if (isEarth(to) && !isEarth(from)) {
				player.sendSystemMessage(Credits.greeting());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 1200 == 0) {
				DegradedChunks.save();
				net.apotheoticstudios.gaiaearth.data.Net.logTrouble();
			}
			net.apotheoticstudios.gaiaearth.world.Places.tick(server);
			net.apotheoticstudios.gaiaearth.world.MapWaypoints.tick(server);
			net.apotheoticstudios.gaiaearth.world.PerfLog.tick(server);
			net.apotheoticstudios.gaiaearth.world.Townsfolk.tick(server);
			net.apotheoticstudios.gaiaearth.world.Milestones.tick(server);
			// Building detail for the map round each player, before chunks there need it.
			TerrainField field = terrain;
			ServerLevel earth = server.getTickCount() % 100 == 0 && field != null ? earthLevel(server) : null;
			if (earth != null) {
				for (var player : earth.players()) {
					field.prefetchDetail(player.getX(), player.getZ());
				}
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (isEarth(handler.player.level())) {
				handler.player.sendSystemMessage(Credits.greeting());
			}
			int degraded = DegradedChunks.count();
			if (degraded > 0 && server.getPlayerList().isOp(handler.player.getGameProfile())) {
				handler.player.sendSystemMessage(Component.literal("[Project Gaia: Earth] " + degraded + " chunk" + (degraded == 1 ? " was" : "s were")
					+ " generated from placeholder data while offline. Run /earth repair when online to regenerate them.").withStyle(ChatFormatting.GOLD));
			}
		});

		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			try {
				net.apotheoticstudios.gaiaearth.compat.dh.DistantHorizonsCompat.init();
			} catch (Throwable e) {
				LOGGER.warn("Could not hook into Distant Horizons; it will generate Earth's distant terrain the slow way", e);
			}
		}

		if (Boolean.getBoolean("realearth.selftest")) {
			SelfTest.install();
		}
	}

	private static void start(MinecraftServer server) {
		RealEarth.server = server;
		config = EarthConfig.load(FabricLoader.getInstance().getConfigDir().resolve("realearth.json"));
		Path worldDir = server.getWorldPath(LevelResource.ROOT);
		WorldSettings world = WorldSettings.loadOrCreate(worldDir.resolve("realearth.json"), config);

		Path cacheRoot = FabricLoader.getInstance().getGameDir().resolve(config.cacheDirectory);
		DiskCache.install(cacheRoot, config.cacheLimitMegabytes << 20);
		DegradedChunks.load(worldDir.resolve("realearth-degraded.json"));
		Path cache = cacheRoot.resolve("terrarium");
		TileStore store = new TileStore(config.tileUrl, world.lidarUrl, cache, config.downloadThreads, 160);
		AtomicInteger threads = new AtomicInteger();
		auxDownloads = Executors.newFixedThreadPool(Math.max(2, config.downloadThreads), r -> {
			Thread t = new Thread(r, "RealEarth-Data-" + threads.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		net.apotheoticstudios.gaiaearth.data.Net.pool("map and land cover", auxDownloads);
		OsmWater map = config.useMapWater ? new OsmWater(config.mapTileJson, cacheRoot.resolve("openfreemap"), auxDownloads) : null;
		if (map != null) {
			map.setRoadVersion(world.roadVersion);
		}
		if (map != null && config.useBuildings && config.useBuildingDetail) {
			map.enableDetail(config.buildingDetailUrl, config.buildingDetailRelease, config.buildingDetailCatalog, cacheRoot.resolve("overture"));
			map.detail().useOverlays(config.buildingOverlays);
			if (config.useMappedTrees) {
				map.enableTrees(config.mappedTreesUrl, cacheRoot.resolve("overture-trees"));
			}
			if (config.useRoadDetail) {
				map.enableRoads(config.roadDetailUrl, cacheRoot.resolve("overture-roads"));
			}
		}
		WorldCover cover = config.useLandCover ? new WorldCover(config.landCoverUrl, cacheRoot.resolve("worldcover"), auxDownloads) : null;
		Projection projection = world.toProjection();
		terrain = new TerrainField(store, config, projection,
			new VerticalScale(world.verticalLayout, config.compressAboveMeters, config.compressBelowMeters), map, cover, world.waterSurfaceVersion);
		terrain.setStart(world.startLatitude, world.startLongitude);
		net.apotheoticstudios.gaiaearth.world.ClimateLink.serve(projection, world.verticalLayout, config.compressAboveMeters, config.compressBelowMeters);
		PortalTravel.load(worldDir.resolve("realearth-portals.json"));

		LOGGER.info("Project Gaia: Earth: {} projection, start {}, {} (block {}, {}); tiles cached in {}", projection.id(),
			world.startLatitude, world.startLongitude, terrain.startX(), terrain.startZ(), cache);
	}

	private static void stop() {
		AreaDownload.cancel();
		DegradedChunks.unload();
		DiskCache.uninstall();
		TerrainField t = terrain;
		terrain = null;
		server = null;
		net.apotheoticstudios.gaiaearth.world.ClimateLink.stop();
		if (auxDownloads != null) {
			auxDownloads.shutdownNow();
			auxDownloads = null;
		}
		if (t != null) {
			t.close();
		}
	}

	/**
	 * Height of a heightmap at a column, loading (and if need be generating) the chunk first.
	 * Level.getHeight alone answers the bottom of the world for chunks that are not loaded.
	 */
	public static int surfaceY(Level level, Heightmap.Types type, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		return level.getHeight(type, x, z);
	}

	/**
	 * Right-clicking a block may place one, here or next to it. Either way the chunk counts as changed
	 * by a player, so repair leaves it alone if it was generated from placeholders.
	 */
	private static InteractionResult onBlockChange(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() && isEarth(level) && !player.getItemInHand(hand).isEmpty()) {
			String id = level.dimension().location().toString();
			BlockPos at = hit.getBlockPos();
			BlockPos next = at.relative(hit.getDirection());
			DegradedChunks.touch(id, at.getX() >> 4, at.getZ() >> 4);
			DegradedChunks.touch(id, next.getX() >> 4, next.getZ() >> 4);
		}
		return InteractionResult.PASS;
	}

	/** Using a Globe Banner Pattern on a complete lapis frame lights it. */
	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!player.getItemInHand(hand).is(PortalFrame.ACTIVATORS)) {
			return InteractionResult.PASS;
		}
		BlockPos clicked = hit.getBlockPos();
		if (!PortalFrame.isFrame(level.getBlockState(clicked))) {
			return InteractionResult.PASS;
		}
		Optional<PortalFrame.Rect> rect = PortalFrame.find(level, clicked.relative(hit.getDirection()));
		if (rect.isEmpty()) {
			return InteractionResult.PASS;
		}
		// In the Earth world type the Overworld already is Earth; a portal to Earth would lead nowhere.
		if (level.dimension() == Level.OVERWORLD && isEarth(level)) {
			player.displayClientMessage(Component.literal("You are already on Earth."), true);
			return InteractionResult.FAIL;
		}
		if (!level.isClientSide()) {
			PortalFrame.light(level, rect.get());
			level.playSound(null, clicked, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.6F, 1.4F);
		}
		return InteractionResult.SUCCESS;
	}
}
