package net.apotheoticstudios.gaiaearth.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.world.OnEarth;

/** While a level ticks, whether it is Earth (OnEarth): its weather, freezing and snow golems read biome temperatures. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void realearth$enter(BooleanSupplier hasTime, CallbackInfo ci) {
		OnEarth.enter(RealEarth.isEarth((ServerLevel) (Object) this));
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void realearth$leave(BooleanSupplier hasTime, CallbackInfo ci) {
		OnEarth.leave();
	}
}
