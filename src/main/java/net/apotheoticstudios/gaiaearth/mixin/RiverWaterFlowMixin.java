// SPDX-License-ResourceLocation: MPL-2.0
// Adapted from Dynamic Waters' FlowingFluidMixin.java. See META-INF/NOTICE-DYNAMIC-WATERS.txt.
package net.apotheoticstudios.gaiaearth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.hydrology.RiverCurrentDemo;
import net.apotheoticstudios.gaiaearth.hydrology.MappedCurrents;

@Mixin(FlowingFluid.class)
public abstract class RiverWaterFlowMixin {
	@Inject(method = "getFlow", at = @At("RETURN"), cancellable = true)
	private void realearth$riverFlow(BlockGetter level, BlockPos pos, FluidState state, CallbackInfoReturnable<Vec3> cir) {
		if (!state.is(Fluids.WATER)) return;
		if (level instanceof RiverCurrentDemo.RenderView && !RealEarth.config().riverVisualFlow) return;
		Vec3 flow = RiverCurrentDemo.flowAt(level, pos);
		if (flow == Vec3.ZERO) {
			Level world = level instanceof Level l ? l
				: level instanceof RiverCurrentDemo.RenderView render ? render.realearth$currentLevel() : null;
			flow = MappedCurrents.flowAt(world, pos);
			if (flow != Vec3.ZERO && !level.getBlockState(pos).is(Blocks.WATER)) return;
		}
		if (flow != Vec3.ZERO) cir.setReturnValue(flow);
	}
}
