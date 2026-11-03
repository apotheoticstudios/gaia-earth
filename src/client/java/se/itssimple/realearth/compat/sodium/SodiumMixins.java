package se.itssimple.realearth.compat.sodium;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

public final class SodiumMixins implements IMixinConfigPlugin {
	@Override
	public List<String> getMixins() {
		return FabricLoader.getInstance().isModLoaded("sodium") ? List.of("CanopySodiumSliceMixin") : null;
	}
}
