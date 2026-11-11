package net.apotheoticstudios.gaiaearth.client;

import com.dtteam.dynamictrees.block.leaves.DynamicLeavesBlock;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Uses the actual loaded client colour registry and baked models, without opening a saved world. */
final class TreeColourTest {
    private static boolean done;
    private TreeColourTest() {}

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done || client.getOverlay()!=null) return;
            done=true;
            try { check(client); }
            catch (Throwable error) { RealEarth.LOGGER.error("TREE_COLOURS_FAIL",error); }
            finally { client.stop(); }
        });
    }

    private static void check(Minecraft client) {
        int blocks=0,states=0,models=0,barkModels=0;
        var colours=client.getBlockColors();
        var sodiumColours=new net.caffeinemc.mods.sodium.client.model.color.ColorProviderRegistry(colours);
        for (var block:BuiltInRegistries.BLOCK) {
            if (!(block instanceof DynamicLeavesBlock leaves)) continue;
            var primitive=leaves.getLeavesProperties().getPrimitiveLeaves();
            if (primitive.isAir() || primitive.getBlock() instanceof DynamicLeavesBlock) continue;
            if (sodiumColours.getColorProvider(block)==null) throw new IllegalStateException("Missing Sodium leaf colour provider: "+BuiltInRegistries.BLOCK.getKey(block));
            for (var state:block.getStateDefinition().getPossibleStates()) {
                for (var view:new BlockAndTintGetter[]{null,new TintView(0x48B518),new TintView(0x6A7039),new TintView(0xAEA42A)}) {
                    int actual=colours.getColor(state,view,BlockPos.ZERO,0);
                    int expected=colours.getColor(primitive,view,BlockPos.ZERO,0);
                    if (actual!=expected) throw new IllegalStateException("Incorrect leaf tint: "+BuiltInRegistries.BLOCK.getKey(block)
                            +" actual="+Integer.toHexString(actual)+" expected="+Integer.toHexString(expected));
                    states++;
                }
            }
            if (BuiltInRegistries.BLOCK.getKey(block).getPath().startsWith("gaia_source_")) {
                var actual=client.getBlockRenderer().getBlockModel(block.defaultBlockState());
                var expected=client.getBlockRenderer().getBlockModel(primitive);
                if (actual.getParticleIcon().contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation()))
                    throw new IllegalStateException("Missing leaf texture: "+BuiltInRegistries.BLOCK.getKey(block));
                if (!actual.getParticleIcon().contents().name().equals(expected.getParticleIcon().contents().name()))
                    throw new IllegalStateException("Incorrect source leaf texture: "+BuiltInRegistries.BLOCK.getKey(block));
                boolean actualTint=false,expectedTint=false;
                for (Direction face:new Direction[]{null,Direction.UP,Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                    actualTint|=actual.getQuads(block.defaultBlockState(),face,RandomSource.create(1)).stream().anyMatch(q -> q.isTinted() && q.getTintIndex()==0);
                    expectedTint|=expected.getQuads(primitive,face,RandomSource.create(1)).stream().anyMatch(q -> q.isTinted() && q.getTintIndex()==0);
                }
                if (actualTint!=expectedTint) throw new IllegalStateException("Leaf model lost its tint layer: "+BuiltInRegistries.BLOCK.getKey(block));
                models++;
            }
            blocks++;
        }
        if (models<63) throw new IllegalStateException("Source leaf models did not load: "+models);
        for(var family:com.dtteam.dynamictrees.tree.family.Family.REGISTRY.getAll()) {
            if(!family.getRegistryName().getNamespace().equals("gaia_trees") || !family.getRegistryName().getPath().startsWith("wide_")) continue;
            for(boolean stripped:new boolean[]{false,true}) {
                var branch=stripped?family.getStrippedBranch():family.getBranch();
                var primitive=stripped?family.getPrimitiveStrippedLog():family.getPrimitiveLog();
                if(branch.isEmpty() || primitive.isEmpty()) continue;
                var actual=client.getBlockRenderer().getBlockModel(branch.get().defaultBlockState());
                var expected=client.getBlockRenderer().getBlockModel(primitive.get().defaultBlockState());
                if(!actual.getParticleIcon().contents().name().equals(expected.getParticleIcon().contents().name()))
                    throw new IllegalStateException("Incorrect wide trunk bark texture: "+BuiltInRegistries.BLOCK.getKey(branch.get()));
                barkModels++;
            }
        }
        RealEarth.LOGGER.info("TREE_COLOURS_PASS: {} leaf blocks, {} state/biome tint comparisons, {} original leaf models, {} wide bark models",blocks,states,models,barkModels);
    }

    private record TintView(int colour) implements BlockAndTintGetter {
        public float getShade(Direction direction,boolean shade) { return 1; }
        public LevelLightEngine getLightEngine() { return null; }
        public int getBlockTint(BlockPos pos,ColorResolver resolver) { return colour; }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
