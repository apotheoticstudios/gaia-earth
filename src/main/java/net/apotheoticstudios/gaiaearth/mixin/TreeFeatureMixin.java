package net.apotheoticstudios.gaiaearth.mixin;

import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.apotheoticstudios.gaiaearth.world.EarthTrees;

/** Earth grows real species in place of vanilla's trees (see {@link EarthTrees}). */
@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void realearth$realTree(FeaturePlaceContext<TreeConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
		Boolean grown = EarthTrees.grow(context);
		if (grown != null) {
			cir.setReturnValue(grown);
		}
	}
}
