package se.itssimple.realearth.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndLightGetter;
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

@Mixin(RenderSectionRegion.class)
public abstract class CanopyRenderRegionMixin implements BlockAndLightGetter {
	@Shadow @Final private ClientLevel level;
	@Shadow @Final private int minSectionX;
	@Shadow @Final private int minSectionY;
	@Shadow @Final private int minSectionZ;

	@Unique private CanopyLight.SectionCache realearth$canopyLight;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void realearth$initCanopyLight(CallbackInfo ci) {
		if (RealEarth.isEarth(level)) {
			realearth$canopyLight = new CanopyLight.SectionCache(level, 18);
			realearth$canopyLight.reset(
				SectionPos.sectionToBlockCoord(minSectionX + 1) - 1,
				SectionPos.sectionToBlockCoord(minSectionY + 1) - 1,
				SectionPos.sectionToBlockCoord(minSectionZ + 1) - 1);
		}
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		int vanilla = BlockAndLightGetter.super.getBrightness(layer, pos);
		return layer == LightLayer.SKY && realearth$canopyLight != null
			? realearth$canopyLight.skyLight(pos, vanilla) : vanilla;
	}
}
