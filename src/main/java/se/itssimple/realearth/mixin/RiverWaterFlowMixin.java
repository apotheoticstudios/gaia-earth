// SPDX-License-Identifier: MPL-2.0
// Adapted from Dynamic Waters' FlowingFluidMixin.java. See META-INF/NOTICE-DYNAMIC-WATERS.txt.
package se.itssimple.realearth.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import se.itssimple.realearth.hydrology.RiverCurrentDemo;

@Mixin(FlowingFluid.class)
public abstract class RiverWaterFlowMixin {
	@Inject(method = "getFlow", at = @At("RETURN"), cancellable = true)
	private void realearth$riverFlow(BlockGetter level, BlockPos pos, FluidState state, CallbackInfoReturnable<Vec3> cir) {
		if (!state.is(Fluids.WATER)) return;
		Vec3 flow = RiverCurrentDemo.flowAt(level, pos);
		if (flow != Vec3.ZERO) cir.setReturnValue(flow);
	}
}
