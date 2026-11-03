package se.itssimple.realearth.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.client.CanopyLight;

@Mixin(RenderSectionRegion.class)
public abstract class CanopyRenderRegionMixin implements BlockAndLightGetter {
	@Shadow @Final private ClientLevel level;
	@Shadow @Final private int minSectionX;
	@Shadow @Final private int minSectionY;
	@Shadow @Final private int minSectionZ;

	@Unique private byte[] realearth$canopyLight;

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		int vanilla = BlockAndLightGetter.super.getBrightness(layer, pos);
		if (layer != LightLayer.SKY || vanilla >= CanopyLight.BRIGHTEST_SHADE || !RealEarth.isEarth(level)) {
			return vanilla;
		}

		int width = CanopyLight.SAMPLE_WIDTH;
		int minY = SectionPos.sectionToBlockCoord(minSectionY + 1) - 1;
		int x = pos.getX() - (SectionPos.sectionToBlockCoord(minSectionX + 1) - 1);
		int y = pos.getY() - minY;
		int z = pos.getZ() - (SectionPos.sectionToBlockCoord(minSectionZ + 1) - 1);
		if (x < 0 || x >= width || y < 0 || y >= width || z < 0 || z >= width) {
			return vanilla;
		}

		if (realearth$canopyLight == null) {
			realearth$canopyLight = new byte[width * width * width];
		}
		int column = (z * width + x) * width;
		if (realearth$canopyLight[column] == 0) {
			int worldX = pos.getX();
			int worldZ = pos.getZ();
			CanopyLight.fillColumn(level.getChunkSource().getChunk(worldX >> 4, worldZ >> 4, ChunkStatus.FULL, false),
				worldX & 15, worldZ & 15, minY, realearth$canopyLight, column);
		}
		return Math.max(vanilla, realearth$canopyLight[column + y] - 1);
	}
}
