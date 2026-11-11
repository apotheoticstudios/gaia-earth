package net.apotheoticstudios.gaiaearth.mixin;

import com.dtteam.dynamictrees.block.leaves.LeavesProperties;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=LeavesProperties.class,remap=false)
public interface LeafPrimitiveStateAccessor {
    @Accessor("primitiveLeaves") void gaia$setPrimitiveLeaves(BlockState state);
}
