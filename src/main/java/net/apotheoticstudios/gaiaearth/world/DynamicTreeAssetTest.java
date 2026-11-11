package net.apotheoticstudios.gaiaearth.world;

import com.dtteam.dynamictrees.api.network.BranchDestructionData;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Offline integration check, enabled only by the dedicated Gradle test run. */
public final class DynamicTreeAssetTest {
    private DynamicTreeAssetTest() {}

    public static void run(MinecraftServer server) {
        int count=0,orientations=0;
        Set<String> sampled=new HashSet<>();
        Set<String> testedShapes=new HashSet<>();
        var level=server.overworld();
        var foot=new BlockPos(0,128,0);
        try {
            clearPlot(level,BlockPos.betweenClosed(foot.offset(-28,-8,-28),foot.offset(28,136,28)));
            var resources=server.getResourceManager().listResources("structure/tree",id -> id.getNamespace().equals("gaia-earth") && id.getPath().endsWith("/trees.json"));
            for (var item:resources.entrySet()) {
                String path=item.getKey().getPath();
                String folder=path.substring("structure/".length(),path.length()-"trees.json".length());
                try (var reader=item.getValue().openAsReader()) {
                    var manifest=JsonParser.parseReader(reader).getAsJsonObject();
                    for (var value:manifest.getAsJsonArray("trees")) {
                        var tree=value.getAsJsonObject();
                        if (!tree.has("dynamic_species") || !tree.get("felling_verified").getAsBoolean()
                                || tree.get("verified_orientations").getAsInt()!=8) throw new IllegalStateException("Unconverted active tree in "+item.getKey());
                        String file=tree.get("file").getAsString();
                        var id=ResourceLocation.fromNamespaceAndPath("gaia-earth",folder+file.substring(0,file.length()-4));
                        var template=server.getStructureManager().get(id).orElseThrow();
                        var blocks=((StructureTemplateAccessor)template).realearth$palettes().get(0).blocks();
                        for (var block:blocks) {
                            var state=block.state();
                            if (state.is(BlockTags.LOGS) && !TreeHelper.isBranch(state)
                                    || state.is(BlockTags.LEAVES) && !(state.getBlock() instanceof DynamicLeavesBlock))
                                throw new IllegalStateException("Static tree block in "+id);
                        }
                        if (blocks.stream().noneMatch(info -> TreeHelper.isBranch(info.state()))
                                || tree.get("leaf_count").getAsInt()>0 && blocks.stream().noneMatch(info -> info.state().getBlock() instanceof DynamicLeavesBlock)
                                || blocks.stream().noneMatch(info -> TreeHelper.getRooty(info.state()) != null))
                            throw new IllegalStateException("Missing dynamic branch, foliage or root blocks in " + id);
                        for (var info:blocks) {
                            if (info.state().getBlock() instanceof DynamicLeavesBlock && !info.state().is(BlockTags.LEAVES))
                                throw new IllegalStateException("Dynamic foliage missing the leaves tag in " + id);
                        }
                        count++;
                        String species=tree.get("dynamic_species").getAsString();
                        boolean newProfile=sampled.add(species);
                        boolean tall=tree.get("height").getAsInt()>65 && testedShapes.add("tall");
                        boolean rooted=tree.get("depth").getAsInt()>1 && testedShapes.add("deep_roots");
                        boolean wide=blocks.stream().anyMatch(info -> info.state().getBlock() instanceof com.dtteam.dynamictrees.block.branch.TrunkShellBlock)
                                && testedShapes.add("wide_trunk");
                        if (!newProfile && !tall && !rooted && !wide) continue;
                        var base=tree.getAsJsonArray("base");
                        var anchor=new BlockPos(base.get(0).getAsInt(),base.get(1).getAsInt(),base.get(2).getAsInt());
                        for (var mirror:new Mirror[]{Mirror.NONE,Mirror.LEFT_RIGHT}) for (var rotation:Rotation.values()) {
                            Set<BlockPos> occupied=new HashSet<>(),branches=new HashSet<>();
                            var transformed=net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.transform(anchor,mirror,rotation,BlockPos.ZERO);
                            for (var block:blocks) {
                                var at=foot.offset(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.transform(block.pos(),mirror,rotation,BlockPos.ZERO).subtract(transformed));
                                occupied.add(at);
                                if (TreeHelper.isBranch(block.state())) branches.add(at);
                            }
                            try {
                                level.setBlock(foot,Blocks.STONE.defaultBlockState(),2);
                                if (DynamicTreePlacement.place(level,template,anchor,foot,mirror,rotation,-3,3,-3,3,true)
                                        || !level.getBlockState(foot).is(Blocks.STONE)
                                        || TreeHelper.getRooty(level.getBlockState(foot.below())) != null)
                                    throw new IllegalStateException("Blocked tree changed the world: " + id);
                                level.setBlock(foot,Blocks.AIR.defaultBlockState(),2);
                                // A paved street tree must keep rooty soil and its saved custom leaf species.
                                level.setBlock(foot.below(),Blocks.STONE.defaultBlockState(),2);
                                if (!DynamicTreePlacement.place(level,template,anchor,foot,mirror,rotation,-3,3,-3,3,true))
                                    throw new IllegalStateException("Placement failed: "+id);
                                if (!TreeHelper.getExactSpecies(level,foot.below()).getRegistryName().toString().equals(species))
                                    throw new IllegalStateException("Root species was lost: "+id);
                                for (var at:occupied) {
                                    var state=level.getBlockState(at);
                                    if (state.getBlock() instanceof com.dtteam.dynamictrees.block.branch.TrunkShellBlock shell) {
                                        var core=shell.getMuseUnchecked(level,state,at);
                                        if(core==null || core.getRadius()<=8) {
                                            var expected=at.offset(state.getValue(com.dtteam.dynamictrees.block.branch.TrunkShellBlock.CORE_DIR).getOffsetPos());
                                            throw new IllegalStateException("Detached trunk shell: "+id+" at "+at+" in "+mirror+"/"+rotation+" points to "+level.getBlockState(expected));
                                        }
                                    }
                                }
                                var branch=TreeHelper.getBranch(level.getBlockState(foot));
                                var oldMode=BranchBlock.destroyMode;
                                BranchDestructionData felled;
                                try { felled=branch.destroyBranchFromNode(level,foot,Direction.NORTH,false,null); }
                                finally { BranchBlock.destroyMode=oldMode; }
                                Set<BlockPos> removed=new HashSet<>();
                                felled.getPositions(BranchDestructionData.PosType.BRANCHES).forEach(removed::add);
                                if (!removed.equals(branches) || branches.stream().anyMatch(p -> TreeHelper.isBranch(level.getBlockState(p))))
                                    throw new IllegalStateException("Incomplete felling: "+id+" expected="+branches.size()+" removed="+removed.size()+" familyDepth="+branch.getFamily().getMaxSignalDepth());
                                orientations++;
                            } finally {
                                // Native felling can briefly create shells around its
                                // remaining cores. Clear those before reusing the plot.
                                for (var core:branches) {
                                    for (var direction:com.dtteam.dynamictrees.utility.CoordUtils.Surround.values()) {
                                        var at=core.offset(direction.getOffsetPos());
                                        if (level.getBlockState(at).getBlock() instanceof com.dtteam.dynamictrees.block.branch.TrunkShellBlock) occupied.add(at);
                                    }
                                }
                                clearPlot(level,occupied);
                            }
                        }
                    }
                }
            }
            if (count==0) throw new IllegalStateException("No dynamic tree assets were loaded");
            if (Boolean.getBoolean("realearth.dynamicWorldgenTest")) worldGeneration(server,count);
            RealEarth.LOGGER.info("DYNAMIC_TREE_ASSETS_PASS: {} assets, {} species profiles, {} placements and felling checks",count,sampled.size(),orientations);
        } catch (Exception error) { RealEarth.LOGGER.error("DYNAMIC_TREE_ASSETS_FAIL",error); }
        finally { server.halt(false); }
    }

    private static void clearPlot(net.minecraft.server.level.ServerLevel level, Iterable<BlockPos> positions) {
        var old=BranchBlock.destroyMode;
        BranchBlock.destroyMode=com.dtteam.dynamictrees.DynamicTrees.DestroyMode.IGNORE;
        try {
            for (var at:positions) if (!level.getBlockState(at).isAir())
                level.setBlock(at,Blocks.AIR.defaultBlockState(),Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS);
        } finally {
            BranchBlock.destroyMode=old;
        }
    }
    private static void worldGeneration(MinecraftServer server,int librarySize) {
        var earth=RealEarth.earthLevel(server);
        var field=RealEarth.terrain();
        if (earth==null || field==null) throw new IllegalStateException("Earth did not initialize");
        var generator=earth.getChunkSource().getGenerator();
        for (var biome:generator.getBiomeSource().possibleBiomes()) {
            for (var step:generator.getBiomeGenerationSettings(biome).features()) for (var feature:step)
                if (feature.value().feature().value().feature() instanceof com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature)
                    throw new IllegalStateException("Independent Dynamic Trees population bypasses Gaia's plant map");
        }
        int cx=0,cz=0,best=-1;
        var random=new java.util.Random(20261108L+librarySize);
        for (int attempt=0;attempt<24 && best<86;attempt++) {
            double lat=59.18+(random.nextDouble()-0.5)*0.04;
            double lon=18.30+(random.nextDouble()-0.5)*0.08;
            int x=(int)field.projection().blockX(lat,lon)>>4,z=(int)field.projection().blockZ(lat)>>4;
            var terrain=field.chunk(x,z).join();
            int wooded=0;
            for (int lx=0;lx<16;lx++) for (int lz=0;lz<16;lz++)
                if (terrain.coverAt(lx,lz)==net.apotheoticstudios.gaiaearth.landcover.WorldCover.TREES
                        && terrain.wood[net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain.index(lx,lz)]) wooded++;
            if (wooded>best) {best=wooded;cx=x;cz=z;}
        }
        if (best<1) throw new IllegalStateException("No mapped forest at the tree-test sites");
        int branches=0,roots=0,staticBlocks=0;
        for (int x=cx-1;x<=cx+1;x++) for (int z=cz-1;z<=cz+1;z++) {
            var terrain=field.chunk(x,z).join();
            var chunk=earth.getChunk(x,z);
            for (int lx=0;lx<16;lx++) for (int lz=0;lz<16;lz++) {
                int k=net.apotheoticstudios.gaiaearth.terrain.ChunkTerrain.index(lx,lz);
                if (terrain.building[k]!=0) continue;
                int ground=terrain.topSolidY[k];
                for (int y=Math.max(earth.getMinBuildHeight(),ground-8);y<=Math.min(earth.getMaxBuildHeight()-1,ground+136);y++) {
                    BlockPos at=new BlockPos(x*16+lx,y,z*16+lz);
                    var state=chunk.getBlockState(at);
                    if (TreeHelper.isBranch(state)) branches++;
                    if (TreeHelper.getRooty(state)!=null) {
                        roots++;
                        if (!TreeHelper.getExactSpecies(earth,at).isValid()) throw new IllegalStateException("Missing generated root species at "+at);
                    }
                    if (state.is(BlockTags.LOGS) && !TreeHelper.isBranch(state)
                            || state.is(BlockTags.LEAVES) && !(state.getBlock() instanceof DynamicLeavesBlock)) staticBlocks++;
                }
            }
        }
        RealEarth.LOGGER.info("Tree generation counters: {}",EarthTrees.placed());
        if (branches==0 || roots==0 || staticBlocks!=0)
            throw new IllegalStateException("Earth tree generation: "+branches+" dynamic branches, "+roots+" roots, "+staticBlocks+" static tree blocks");
        RealEarth.LOGGER.info("DYNAMIC_TREE_WORLDGEN_PASS: {} branches, {} saved root species, no static tree blocks",branches,roots);
    }

}
