package se.itssimple.realearth.mixin;

import java.util.ArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.compat.xaero.XaeroWorldMapCompat;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Real Earth's teleport in Xaero's World Map's right-click menu ({@link XaeroWorldMapCompat}). The map
 * has no way for other mods to add to the menu. Its own mixin config, not required, which lists it
 * only when the world map is installed ({@code XaeroMixins}); with one that has changed, it is left
 * out and the game goes on.
 */
@Mixin(targets = "xaero.map.gui.GuiMap")
public abstract class GuiMapMixin {
	@Shadow
	private int rightClickX;
	@Shadow
	private int rightClickZ;
	@Shadow
	private ResourceKey<Level> rightClickDim;

	@Inject(method = "getRightClickOptions", at = @At("RETURN"))
	private void realearth$teleport(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
		XaeroWorldMapCompat.addTeleport(cir.getReturnValue(), (IRightClickableElement) this, rightClickX, rightClickZ, rightClickDim);
	}
}
