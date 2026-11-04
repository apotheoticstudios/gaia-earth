package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.client.CanopyLight;

@Mixin(value = LevelSlice.class, remap = false)
public abstract class CanopySodiumSliceMixin {
	@Shadow @Final private ClientLevel level;

	@Unique private CanopyLight.SectionCache realearth$canopyLight;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void realearth$initCanopyLight(CallbackInfo ci) {
		if (RealEarth.isEarth(level)) {
			realearth$canopyLight = new CanopyLight.SectionCache(level);
		}
	}

	@Inject(method = "copyData", at = @At("TAIL"))
	private void realearth$resetCanopyLight(ChunkRenderContext context, CallbackInfo ci) {
		if (realearth$canopyLight != null) {
			realearth$canopyLight.reset(context);
		}
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void realearth$releaseCanopyLight(CallbackInfo ci) {
		if (realearth$canopyLight != null) {
			realearth$canopyLight.clear();
		}
	}

	@ModifyReturnValue(method = "getBrightness", at = @At("RETURN"))
	private int realearth$canopySky(int vanilla, LightLayer layer, BlockPos pos) {
		return layer == LightLayer.SKY && realearth$canopyLight != null
			? realearth$canopyLight.skyLight(pos, vanilla) : vanilla;
	}
}
