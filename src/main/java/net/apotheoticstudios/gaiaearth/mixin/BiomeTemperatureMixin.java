package net.apotheoticstudios.gaiaearth.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.apotheoticstudios.gaiaearth.world.ClimateLink;
import net.apotheoticstudios.gaiaearth.world.OnEarth;

/**
 * Snow and ice on Earth by real elevation.
 *
 * Vanilla cools 0.05 temperature units per 40 blocks above sea level, which suits its squashed
 * mountains; at one block per metre it would bring snow and frozen lakes down to a few hundred
 * metres in ordinary forest. On Earth a block's temperature follows its real elevation instead --
 * blocks are not metres where the map is enlarged or shrunk, nor where heights are eased above the
 * knee -- so that rain turns to snow at the real snowline of its latitude, the same one the snowy
 * biomes follow (BiomePicker.heightTemperature). 1.21.1 doesn't say which level the block is in: OnEarth does.
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
	@Inject(method = "getHeightAdjustedTemperature", at = @At("RETURN"), cancellable = true)
	private void realearth$realisticLapseRate(BlockPos pos, CallbackInfoReturnable<Float> cir, @Local(ordinal = 0) float base) {
		ClimateLink.Shape shape = OnEarth.now() ? ClimateLink.current() : null;
		if (shape != null) {
			cir.setReturnValue(shape.temperature(base, pos));
		}
	}
}
