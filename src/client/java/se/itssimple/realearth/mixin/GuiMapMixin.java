package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.compat.xaero.XaeroMapBackground;
import se.itssimple.realearth.compat.xaero.XaeroWorldMapCompat;
import xaero.map.MapProcessor;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Real Earth's teleport in Xaero's World Map's right-click menu ({@link XaeroWorldMapCompat}), and the
 * real map under the explored one ({@link XaeroMapBackground}). The map has no way for other mods to
 * add to the menu or draw under it. Its own mixin config, not required, which lists it only when the
 * world map is installed ({@code XaeroMixins}); with one that has changed, it is left out and the game
 * goes on.
 */
@Mixin(targets = "xaero.map.gui.GuiMap")
public abstract class GuiMapMixin {
	@Shadow
	private int rightClickX;
	@Shadow
	private int rightClickZ;
	@Shadow
	private ResourceKey<Level> rightClickDim;
	@Shadow
	private double scale;
	@Shadow
	private MapProcessor mapProcessor;

	@Inject(method = "getRightClickOptions", at = @At("RETURN"))
	private void realearth$teleport(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
		XaeroWorldMapCompat.addTeleport(cir.getReturnValue(), (IRightClickableElement) this, rightClickX, rightClickZ, rightClickDim);
	}

	/** Just before the map adds its regions to the batch, after it has cleared its frame and set up the batch. */
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/region/LeveledRegion;setComparison(IIIII)V"))
	private void realearth$background(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci,
		@Local Matrix4f matrix, @Local(name = "withLightRenderer") MultiTextureRenderTypeRenderer renderer,
		@Local(name = "flooredCameraX") int cameraX, @Local(name = "flooredCameraZ") int cameraZ,
		@Local(name = "leftBorder") double left, @Local(name = "rightBorder") double right,
		@Local(name = "topBorder") double top, @Local(name = "bottomBorder") double bottom,
		@Local(name = "renderedCaveLayer") int caveLayer) {
		XaeroMapBackground.draw(matrix, renderer, cameraX, cameraZ, left, right, top, bottom, scale,
			mapProcessor.getMapWorld().getCurrentDimension().getDimId(), caveLayer != Integer.MAX_VALUE);
	}

	@Inject(method = "renderPreDropdown", at = @At("HEAD"))
	private void realearth$credit(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		Screen screen = (Screen) (Object) this;
		XaeroMapBackground.credit(graphics, screen.width, screen.height);
	}
}
