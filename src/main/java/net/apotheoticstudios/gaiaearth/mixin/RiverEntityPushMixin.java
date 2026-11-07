package net.apotheoticstudios.gaiaearth.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.apotheoticstudios.gaiaearth.hydrology.CurrentSettings;
import net.apotheoticstudios.gaiaearth.hydrology.MappedCurrents;
import net.apotheoticstudios.gaiaearth.hydrology.RiverCurrentDemo;

/** Mapped rivers push entities at their real speed: vanilla's push, scaled by the river speed of the cells it sampled. */
@Mixin(Entity.class)
public abstract class RiverEntityPushMixin {
	@Unique private double realearth$summedSpeed;
	@Unique private int realearth$riverContacts;
	@Unique private int realearth$vanillaContacts;

	@Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("HEAD"))
	private void realearth$resetSamples(TagKey<Fluid> tag, double scale, CallbackInfoReturnable<Boolean> cir) {
		realearth$summedSpeed = 0;
		realearth$riverContacts = 0;
		realearth$vanillaContacts = 0;
	}

	@WrapOperation(method = "updateFluidHeightAndDoFluidPushing", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 realearth$sampleRiverSpeed(FluidState fluid, BlockGetter view, BlockPos pos, Operation<Vec3> original) {
		Vec3 flow = original.call(fluid, view, pos);
		if (!(view instanceof Level level) || !fluid.is(FluidTags.WATER) || flow.lengthSqr() == 0) return flow;
		double speed = fluid.is(Fluids.WATER) ? MappedCurrents.speedAt(level, pos) : 0;
		if (speed > 0 && view.getBlockState(pos).is(Blocks.WATER) && RiverCurrentDemo.flowAt(view, pos) == Vec3.ZERO) {
			realearth$summedSpeed += speed;
			realearth$riverContacts++;
		} else {
			realearth$vanillaContacts++;
		}
		return flow;
	}

	@WrapMethod(method = "updateFluidHeightAndDoFluidPushing")
	private boolean realearth$scaleMappedPush(TagKey<Fluid> tag, double scale, Operation<Boolean> original) {
		Entity entity = (Entity) (Object) this;
		Vec3 before = entity.getDeltaMovement();
		boolean inside = original.call(tag, scale);
		if (realearth$riverContacts == 0 || !tag.equals(FluidTags.WATER)) return inside;
		int strength = CurrentSettings.strength(entity.level());
		double multiplier = (realearth$summedSpeed * (strength / 100.0) + realearth$vanillaContacts)
			/ (realearth$riverContacts + realearth$vanillaContacts);
		// Scale the impulse after vanilla's normalization and minimum force, using only the cells
		// vanilla actually sampled. This avoids a second scan of the entity's bounding box.
		if (multiplier != 1) entity.setDeltaMovement(before.add(entity.getDeltaMovement().subtract(before).scale(multiplier)));
		return inside;
	}
}
