package net.apotheoticstudios.gaiaearth.world;

import com.dtteam.dynamictrees.tree.family.Family;
import java.util.Set;

/** Native tree settings required by the already baked branch networks. */
public final class DynamicTreeSupport {
    private static final Set<String> WOODS=Set.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry");
    private DynamicTreeSupport() {}

    public static void configure() {
        for(var properties:com.dtteam.dynamictrees.block.leaves.LeavesProperties.REGISTRY.getAll()) {
            String path=properties.getRegistryName().getPath();
            if(!path.matches(".*__wheat_age_[0-7]")) continue;
            int age=path.charAt(path.length()-1)-'0';
            ((net.apotheoticstudios.gaiaearth.mixin.LeafPrimitiveStateAccessor)properties).gaia$setPrimitiveLeaves(
                    net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE,age));
        }
        for (var family:Family.REGISTRY.getAll()) {
            var id=family.getRegistryName();
            if (id.getNamespace().equals("gaia_trees") || id.getNamespace().equals("dynamictrees") && WOODS.contains(id.getPath()))
                family.setMaxSignalDepth(Math.max(512,family.getMaxSignalDepth()));
        }
    }
}
