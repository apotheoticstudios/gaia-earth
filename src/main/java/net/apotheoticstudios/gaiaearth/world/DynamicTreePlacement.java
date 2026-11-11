package net.apotheoticstudios.gaiaearth.world;

import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import java.util.ArrayList;
import java.util.List;
import net.apotheoticstudios.gaiaearth.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Places an already baked tree, including its root species; never converts or grows a schematic. */
public final class DynamicTreePlacement {
    private DynamicTreePlacement() {}

    public static boolean place(WorldGenLevel level, StructureTemplate template, BlockPos base, BlockPos foot,
                                Mirror mirror, Rotation rotation, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, boolean paved) {
        var palettes = ((StructureTemplateAccessor) template).realearth$palettes();
        if (palettes.isEmpty()) return false;
        BlockPos anchor = StructureTemplate.transform(base, mirror, rotation, BlockPos.ZERO);
        List<StructureTemplate.StructureBlockInfo> placed = new ArrayList<>();
        List<StructureTemplate.StructureBlockInfo> shells = new ArrayList<>();
        for (var info : palettes.get(0).blocks()) {
            if (info.state().isAir()) continue;
            BlockPos at = foot.offset(StructureTemplate.transform(info.pos(), mirror, rotation, BlockPos.ZERO).subtract(anchor));
            // The entire living network must fit. Clipping a branch can detach a crown or its root.
            if (level.isOutsideBuildHeight(at) || at.getX() >> 4 < minChunkX || at.getX() >> 4 > maxChunkX
                    || at.getZ() >> 4 < minChunkZ || at.getZ() >> 4 > maxChunkZ) return false;
            BlockState state = transformState(info.state(), mirror, rotation);
            BlockState there = level.getBlockState(at);
            boolean below = info.pos().getY() < base.getY();
            boolean wood = TreeHelper.isBranch(state) || state.getBlock() instanceof TrunkShellBlock;
            boolean replaceable = there.isAir() || there.is(BlockTags.REPLACEABLE_BY_TREES)
                    || there.is(BlockTags.LEAVES) && wood;
            boolean rootPit = paved && at.equals(foot.below()) && TreeHelper.getRooty(there) == null
                    && !there.is(BlockTags.LOGS) && !there.is(BlockTags.LEAVES);
            if (below ? !(natural(there) || rootPit) : !replaceable) {
                if (below || wood) return false;
                continue;
            }
            var placement=new StructureTemplate.StructureBlockInfo(at,state,info.nbt());
            if (state.getBlock() instanceof TrunkShellBlock) shells.add(placement);
            else placed.add(placement);
        }
        if (placed.stream().noneMatch(info -> info.pos().equals(foot.below()) && TreeHelper.getRooty(info.state()) != null)
                || placed.stream().noneMatch(info -> info.pos().equals(foot) && TreeHelper.isBranch(info.state()))) return false;
        placed.addAll(shells);
        for (var info : placed) {
            level.setBlock(info.pos(),info.state(),Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            if (info.nbt() != null) {
                var entity = level.getBlockEntity(info.pos());
                if (entity == null) throw new IllegalStateException("Missing tree block entity at " + info.pos());
                entity.loadWithComponents(info.nbt().copy(), level.registryAccess());
                entity.setChanged();
            }
        }
        return true;
    }

    private static BlockState transformState(BlockState state, Mirror mirror, Rotation rotation) {
        BlockState result = state.mirror(mirror).rotate(rotation);
        if (state.getBlock() instanceof TrunkShellBlock) {
            BlockPos offset = StructureTemplate.transform(state.getValue(TrunkShellBlock.CORE_DIR).getOffsetPos(), mirror, rotation, BlockPos.ZERO);
            for (var direction : com.dtteam.dynamictrees.utility.CoordUtils.Surround.values()) {
                if (direction.getOffsetPos().equals(offset)) return result.setValue(TrunkShellBlock.CORE_DIR, direction);
            }
            throw new IllegalStateException("Invalid transformed trunk shell direction");
        }
        return result;
    }

    private static boolean natural(BlockState state) {
        return TreeHelper.getRooty(state) == null && (state.isAir() || state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(BlockTags.REPLACEABLE_BY_TREES));
    }
}
