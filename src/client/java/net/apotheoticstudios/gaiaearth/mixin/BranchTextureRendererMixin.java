package net.apotheoticstudios.gaiaearth.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Xaero's World Map builds its zoomed-out textures by shrinking four zoomed-in ones onto a texture
 * cleared to opaque black, so the unexplored parts of a square with anything explored in it were black
 * and hid Project Gaia: Earth's map underneath ({@code XaeroMapBackground}). Cleared to transparent instead,
 * which the map's shader skips: where nothing is underneath, the map's own black frame shows, as
 * before. Textures the map already saved keep their black until it remakes them. Listed by
 * {@code XaeroMixins} with the world map.
 */
@Mixin(targets = "xaero.map.region.texture.BranchTextureRenderer")
public abstract class BranchTextureRendererMixin {
	@ModifyArg(method = "render", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/platform/GlStateManager;_clearColor(FFFF)V"), index = 3)
	private float realearth$transparent(float alpha) {
		return 0.0F;
	}
}
