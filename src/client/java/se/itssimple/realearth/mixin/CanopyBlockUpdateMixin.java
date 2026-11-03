package se.itssimple.realearth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.client.CanopyLight;

@Mixin(Level.class)
public abstract class CanopyBlockUpdateMixin {
	@WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState realearth$refreshShadeBelow(LevelChunk chunk, BlockPos pos, BlockState state, int flags,
			Operation<BlockState> original) {
		if (!((Object) this instanceof ClientLevel level) || !RealEarth.isEarth(level)
				|| !CanopyLight.hasHeightmaps(chunk)) {
			return original.call(chunk, pos, state, flags);
		}

		int x = pos.getX() & 15;
		int z = pos.getZ() & 15;
		int oldTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		int oldBottom = CanopyLight.shadeBottom(chunk, x, z, oldTop);
		BlockState before = original.call(chunk, pos, state, flags);
		if (before == null || before == state) {
			return before;
		}
		int newTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		int newBottom = CanopyLight.shadeBottom(chunk, x, z, newTop);
		if (oldTop != newTop || oldBottom != newBottom || CanopyLight.changesTransmission(before, state)) {
			realearth$dirtyShade(level, pos, oldBottom, oldTop);
			if (oldTop != newTop || oldBottom != newBottom) {
				realearth$dirtyShade(level, pos, newBottom, newTop);
			}
		}
		return before;
	}

	@Unique
	private static void realearth$dirtyShade(ClientLevel level, BlockPos pos, int minY, int top) {
		int maxY = Math.min(top, pos.getY() - 1);
		if (minY > maxY) {
			return;
		}
		int x = pos.getX();
		int z = pos.getZ();
		// Neighbouring meshes also sample this column when shading their border faces.
		level.setSectionRangeDirty(
			SectionPos.blockToSectionCoord(x - 1), SectionPos.blockToSectionCoord(minY - 1), SectionPos.blockToSectionCoord(z - 1),
			SectionPos.blockToSectionCoord(x + 1), SectionPos.blockToSectionCoord(maxY + 1), SectionPos.blockToSectionCoord(z + 1));
	}
}
