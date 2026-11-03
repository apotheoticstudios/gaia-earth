package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import se.itssimple.realearth.hydrology.CurrentSettings;
import se.itssimple.realearth.hydrology.MappedCurrents;
import se.itssimple.realearth.hydrology.RiverCurrentDemo;

@Mixin(EntityFluidInteraction.class)
public abstract class RiverEntityPushMixin {
	@Unique private double realearth$summedSpeed;
	@Unique private int realearth$riverContacts;
	@Unique private int realearth$vanillaContacts;

	@Inject(method = "update", at = @At("HEAD"))
	private void realearth$resetSamples(Entity entity, boolean skipPush, CallbackInfo ci) {
		realearth$summedSpeed = 0;
		realearth$riverContacts = 0;
		realearth$vanillaContacts = 0;
	}

	@WrapOperation(method = "update", at = @At(value = "INVOKE",
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

	@WrapMethod(method = "applyCurrentTo")
	private void realearth$scaleMappedPush(TagKey<Fluid> tag, Entity entity, double scale, Operation<Void> original) {
		if (realearth$riverContacts == 0 || !tag.equals(FluidTags.WATER)) {
			original.call(tag, entity, scale);
			return;
		}
		int strength = CurrentSettings.strength(entity.level());
		double multiplier = (realearth$summedSpeed * (strength / 100.0) + realearth$vanillaContacts)
			/ (realearth$riverContacts + realearth$vanillaContacts);
		Vec3 before = entity.getDeltaMovement();
		original.call(tag, entity, scale);
		// Scale the impulse after vanilla's normalization and minimum force, using only the cells
		// vanilla actually sampled. This avoids a second scan of the entity's bounding box.
		if (multiplier != 1) entity.setDeltaMovement(before.add(entity.getDeltaMovement().subtract(before).scale(multiplier)));
	}
}
