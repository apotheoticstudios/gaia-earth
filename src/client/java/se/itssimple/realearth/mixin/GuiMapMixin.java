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
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.compat.xaero.XaeroMapBackground;
import se.itssimple.realearth.compat.xaero.XaeroWorldMapCompat;
import xaero.map.MapProcessor;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

/**
 * Real Earth's teleport in Xaero's World Map's right-click menu ({@link XaeroWorldMapCompat}), and the
 * real map under the explored one ({@link XaeroMapBackground}), out to the whole Earth. The map has no
 * way for other mods to add to the menu or draw under it. Its own mixin config, not required, which lists it only when the
 * world map is installed ({@code XaeroMixins}); with one that has changed, it is left out and the game
 * goes on.
 */
@Mixin(targets = "xaero.map.gui.GuiMap")
public abstract class GuiMapMixin {
	private static final long MAX_REGIONS = 1 << 17;

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

	/** On the Earth, out to the whole globe, past the map's own furthest zoom (1/16, or 1/512 with its unlimited zoom out). */
	@ModifyConstant(method = "applyZoomLimits", constant = {@Constant(doubleValue = 0.0625), @Constant(doubleValue = 0.001953125)})
	private double realearth$wholeEarth(double limit) {
		MapWorld world = mapProcessor.getMapWorld();
		MapDimension dimension = world == null ? null : world.getCurrentDimension();
		return dimension == null ? limit : XaeroMapBackground.minZoom(limit, dimension.getDimId());
	}

	/**
	 * Zoomed out that far, the map would go through millions of its regions every frame; it goes through none
	 * instead, leaving Real Earth's picture: what has been explored is under a pixel there anyway. The most
	 * it goes through at its own furthest zoom is some 30,000.
	 */
	@ModifyVariable(method = "extractRenderState", at = @At("STORE"), name = "maxRegZ")
	private int realearth$fewerRegions(int maxRegZ, @Local(name = "minRegX") int minRegX, @Local(name = "maxRegX") int maxRegX,
		@Local(name = "minRegZ") int minRegZ) {
		return (long) (maxRegX - minRegX + 1) * (maxRegZ - minRegZ + 1) > MAX_REGIONS ? minRegZ - 1 : maxRegZ;
	}

	@Inject(method = "renderPreDropdown", at = @At("HEAD"))
	private void realearth$credit(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		Screen screen = (Screen) (Object) this;
		XaeroMapBackground.credit(graphics, screen.width, screen.height);
	}
}
