package se.itssimple.realearth.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import se.itssimple.realearth.hydrology.RiverCurrentDemo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class SodiumRiverRenderRegionMixin implements RiverCurrentDemo.RenderView {
	@Shadow @Final private ClientLevel level;

	@Override
	public Level realearth$currentLevel() {
		return level;
	}
}
