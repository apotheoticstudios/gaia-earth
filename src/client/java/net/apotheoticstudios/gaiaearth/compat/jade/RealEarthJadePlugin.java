package net.apotheoticstudios.gaiaearth.compat.jade;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.apotheoticstudios.gaiaearth.RealEarth;
import net.apotheoticstudios.gaiaearth.api.RealEarthApi;
import net.apotheoticstudios.gaiaearth.client.ClientHere;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade: under the block you look at on the Earth, the street, district and town it is in, the building
 * and room it belongs to, and its latitude, longitude and real elevation. The server works them out
 * (only it has the map) and Jade carries them to the client. Found by Jade through the "jade"
 * entrypoint; never loaded without Jade.
 *
 * In the client sources because Jade's tooltip reaches client classes, but loaded on a dedicated
 * server too, where only {@link #register} and {@link Data} run.
 */
public final class RealEarthJadePlugin implements IWailaPlugin {
	static final ResourceLocation PLACE = RealEarth.id("place");
	static final ResourceLocation COORDINATES = RealEarth.id("coordinates");

	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(Data.INSTANCE, Block.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerBlockComponent(Place.INSTANCE, Block.class);
		registration.registerBlockComponent(Coordinates.INSTANCE, Block.class);
	}

	/** On the server: what the map says about the block, as plain values. */
	enum Data implements IServerDataProvider<BlockAccessor> {
		INSTANCE;

		@Override
		public void appendServerData(CompoundTag data, BlockAccessor accessor) {
			Level level = accessor.getLevel();
			BlockPos pos = accessor.getPosition();
			if (!RealEarthApi.isEarth(level)) {
				return;
			}
			CompoundTag tag = new CompoundTag();
			RealEarthApi.latLon(level, pos.getX() + 0.5, pos.getZ() + 0.5).ifPresent(ll -> {
				tag.putDouble("lat", ll.latitude());
				tag.putDouble("lon", ll.longitude());
			});
			RealEarthApi.elevationMeters(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5).ifPresent(m -> tag.putDouble("meters", m));
			RealEarthApi.street(level, pos.getX(), pos.getZ()).ifPresent(s -> tag.putString("street", s));
			RealEarthApi.place(level, pos.getX() + 0.5, pos.getZ() + 0.5).ifPresent(p -> {
				tag.putString("town", p.town());
				if (p.district() != null) {
					tag.putString("district", p.district());
				}
			});
			RealEarthApi.building(level, pos).ifPresent(b -> tag.putString("building", describe(b)));
			data.put(RealEarth.MOD_ID, tag);
		}

		@Override
		public ResourceLocation getUid() {
			return PLACE;
		}
	}

	/** On the client: the street, town and building lines. */
	enum Place implements IBlockComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
			CompoundTag tag = accessor.getServerData().getCompound(RealEarth.MOD_ID);
			String building = tag.getString("building");
			if (!building.isEmpty()) {
				tooltip.add(Component.literal(building).withStyle(ChatFormatting.GRAY));
			}
			StringBuilder where = new StringBuilder();
			for (String key : new String[] {"street", "district", "town"}) {
				String s = tag.getString(key);
				if (!s.isEmpty() && where.indexOf(s) < 0) {
					where.append(where.isEmpty() ? "" : ", ").append(s);
				}
			}
			if (!where.isEmpty()) {
				tooltip.add(Component.literal(where.toString()).withStyle(ChatFormatting.GRAY));
			}
		}

		@Override
		public ResourceLocation getUid() {
			return PLACE;
		}
	}

	/** On the client: latitude, longitude and real elevation. */
	enum Coordinates implements IBlockComponentProvider {
		INSTANCE;

		@Override
		public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
			CompoundTag tag = accessor.getServerData().getCompound(RealEarth.MOD_ID);
			if (!tag.contains("lat")) {
				return;
			}
			String line = ClientHere.latLon(tag.getDouble("lat"), tag.getDouble("lon"));
			if (tag.contains("meters")) {
				line += String.format(Locale.ROOT, ", %d m", Math.round(tag.getDouble("meters")));
			}
			tooltip.add(Component.literal(line).withStyle(ChatFormatting.DARK_GRAY));
		}

		@Override
		public ResourceLocation getUid() {
			return COORDINATES;
		}

		@Override
		public int getDefaultPriority() {
			return 100; // after the place
		}
	}

	/** "Ica Nära (grocery), storey 1 of 4" or "Block of flats, kitchen, storey 3 of 6". */
	static String describe(RealEarthApi.Building b) {
		StringBuilder s = new StringBuilder();
		String what = b.hall() != null ? b.hall() : switch (b.use()) {
			case "flats" -> "block of flats";
			default -> b.use();
		};
		if (b.name() != null) {
			s.append(b.name());
			String kind = b.room() != null && b.room().trade() != null ? b.room().trade() : b.hall();
			if (kind != null) {
				s.append(" (").append(kind).append(')');
			}
		} else {
			s.append(Character.toUpperCase(what.charAt(0))).append(what.substring(1));
		}
		String room = b.room() == null ? null : switch (b.room().kind()) {
			case "hall" -> "hallway";
			case "bed" -> "bedroom";
			case "bath" -> "bathroom";
			case "living" -> "living room";
			case "store" -> "store room";
			case "hallroom" -> null;
			case "shop" -> b.name() == null && b.room().trade() != null ? b.room().trade() : null;
			default -> b.room().kind();
		};
		if (room != null) {
			s.append(", ").append(room);
		}
		if (b.floor() < 0) {
			s.append(", roof");
		} else if (b.floors() > 1) {
			s.append(", storey ").append(b.floor() + 1).append(" of ").append(b.floors());
		}
		return s.toString();
	}
}
