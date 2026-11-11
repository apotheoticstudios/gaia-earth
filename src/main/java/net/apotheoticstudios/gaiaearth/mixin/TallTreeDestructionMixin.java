package net.apotheoticstudios.gaiaearth.mixin;

import com.dtteam.dynamictrees.api.network.BranchDestructionData;
import com.dtteam.dynamictrees.api.voxmap.BlockPosBounds;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native position codec supports -64..191; its old capture bounds stop at +64. */
@Mixin(value = BranchDestructionData.class, remap = false)
public abstract class TallTreeDestructionMixin {
    @Shadow @Final @Mutable private static BlockPosBounds bounds;
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void gaia$expandCapture(CallbackInfo ci) {
        bounds = new BlockPosBounds(new BlockPos(-64, -64, -64), new BlockPos(191, 191, 191));
    }
}
