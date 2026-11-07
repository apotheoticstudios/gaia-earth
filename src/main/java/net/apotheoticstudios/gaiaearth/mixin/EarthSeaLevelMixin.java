package net.apotheoticstudios.gaiaearth.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.terrain.VerticalScale;
import net.apotheoticstudios.gaiaearth.world.EarthChunkGenerator;

/**
 * Earth's sea level, Y -1800: 1.21.1 gives every level vanilla's 63 (later versions ask the generator). On the server
 * from Earth's generator; on the client Earth's layout's, the only one a 1.21.1 world can have.
 */
@Mixin(Level.class)
public abstract class EarthSeaLevelMixin {
	@Inject(method = "getSeaLevel", at = @At("HEAD"), cancellable = true)
	private void realearth$earthSea(CallbackInfoReturnable<Integer> cir) {
		Level level = (Level) (Object) this;
		if (level instanceof ServerLevel server) {
			if (server.getChunkSource().getGenerator() instanceof EarthChunkGenerator earth) cir.setReturnValue(earth.getSeaLevel());
		} else if (level.isClientSide() && RealEarth.isEarth(level)) {
			cir.setReturnValue(VerticalScale.SEA_LEVEL_Y);
		}
	}
}
