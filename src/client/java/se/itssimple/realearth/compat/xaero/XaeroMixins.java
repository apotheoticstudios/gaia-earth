package se.itssimple.realearth.compat.xaero;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/** Lists the mixin into Xaero's World Map only when it is installed, so a game without it logs no missing class. */
public final class XaeroMixins implements IMixinConfigPlugin {
	@Override
	public List<String> getMixins() {
		return FabricLoader.getInstance().isModLoaded("xaeroworldmap") ? List.of("GuiMapMixin") : null;
	}
}
