package net.apotheoticstudios.gaiaearth.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.apotheoticstudios.gaiaearth.hydrology.RiverCurrentDemo;

@Mixin(RenderChunkRegion.class)
public abstract class RiverRenderRegionMixin implements RiverCurrentDemo.RenderView {
	@Shadow @Final protected Level level;

	@Override
	public Level realearth$currentLevel() {
		return level;
	}
}
