package net.apotheoticstudios.gaiaearth.client;

import com.dtteam.dynamictrees.block.leaves.DynamicLeavesBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.LeavesBlock;

/** The baked leaf model and its tint both come from the original primitive leaf material. */
final class DynamicLeafColours {
    private DynamicLeafColours() {}

    static void install() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            net.apotheoticstudios.gaiaearth.world.DynamicTreeSupport.configure();
            for (var block:BuiltInRegistries.BLOCK) {
                if (!(block instanceof DynamicLeavesBlock leaves)) continue;
                var primitive=leaves.getLeavesProperties().getPrimitiveLeaves();
                if (primitive.isAir() || primitive.getBlock() instanceof DynamicLeavesBlock) continue;
                var original=ColorProviderRegistry.BLOCK.get(block);
                ColorProviderRegistry.BLOCK.register((state,view,pos,tint) -> tint==0
                        ? Minecraft.getInstance().getBlockColors().getColor(leaves.getLeavesProperties().getPrimitiveLeaves(),view,pos,tint)
                        : original==null ? -1 : original.getColor(state,view,pos,tint),block);
            }
        });
    }
}
