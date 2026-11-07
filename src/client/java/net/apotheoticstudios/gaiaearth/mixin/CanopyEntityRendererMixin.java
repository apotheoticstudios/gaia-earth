package net.apotheoticstudios.gaiaearth.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.client.CanopyEntityLight;

@Mixin(EntityRenderer.class)
public abstract class CanopyEntityRendererMixin {
	@ModifyReturnValue(method = "getSkyLightLevel", at = @At("RETURN"))
	private int realearth$canopySky(int vanilla, Entity entity, BlockPos pos) {
		return entity.level() instanceof ClientLevel level && RealEarth.isEarth(level)
			? CanopyEntityLight.sample(level, pos, vanilla) : vanilla;
	}
}
