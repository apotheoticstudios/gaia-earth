package net.apotheoticstudios.gaiaearth.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A chiseled bookshelf's books, set straight: setItem updates the block in a level, and a shelf put into a chunk while it is generated has none yet. */
@Mixin(ChiseledBookShelfBlockEntity.class)
public interface ChiseledBookShelfAccessor {
	@Accessor("items")
	NonNullList<ItemStack> realearth$items();
}
