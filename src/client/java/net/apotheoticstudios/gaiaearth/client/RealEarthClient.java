package net.apotheoticstudios.gaiaearth.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.apotheoticstudios.gaiaearth.RealEarth;

public class RealEarthClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		net.apotheoticstudios.gaiaearth.world.OnEarth.client(Thread.currentThread(), () -> {
			var level = net.minecraft.client.Minecraft.getInstance().level;
			return level != null && RealEarth.isEarth(level);
		});
		// Earth's sky is the Overworld's, its clouds under the ground: at a block a metre, Y 192 would cut through every hill.
		net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry.registerDimensionEffects(RealEarth.id("earth"),
			new net.minecraft.client.renderer.DimensionSpecialEffects(-200.0F, true, net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL, false, false) {
				@Override
				public net.minecraft.world.phys.Vec3 getBrightnessDependentFogColor(net.minecraft.world.phys.Vec3 fog, float brightness) {
					return fog.multiply(brightness * 0.94F + 0.06F, brightness * 0.94F + 0.06F, brightness * 0.91F + 0.09F);
				}

				@Override
				public boolean isFoggyAt(int x, int z) {
					return false;
				}
			});
		ClientHere.install();
		ClientRiverCurrent.install();
		ClientPerfLog.install();
		CanopyMeshUpdates.install();
		if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
			try {
				net.apotheoticstudios.gaiaearth.compat.xaero.XaeroMinimapCompat.install();
			} catch (Throwable e) {
				RealEarth.LOGGER.warn("Could not add Project Gaia: Earth's lines to Xaero's Minimap", e);
			}
		}
		if (FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
			try {
				net.apotheoticstudios.gaiaearth.compat.xaero.XaeroWorldMapCompat.install();
			} catch (Throwable e) {
				RealEarth.LOGGER.warn("Could not add Project Gaia: Earth's waypoints to Xaero's World Map", e);
			}
		}
		if (Boolean.getBoolean("realearth.shots")) {
			ShotTour.install();
		}
		String playtest = System.getProperty("realearth.playtest", "");
		if (!playtest.isEmpty()) {
			PlayTest.install(playtest);
		}
	}
}
