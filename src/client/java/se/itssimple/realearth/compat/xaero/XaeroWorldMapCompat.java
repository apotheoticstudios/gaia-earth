package se.itssimple.realearth.compat.xaero;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import se.itssimple.realearth.RealEarth;
import se.itssimple.realearth.client.ClientHere;
import se.itssimple.realearth.world.MapWaypoints;
import xaero.lib.XaeroLib;
import xaero.map.WorldMap;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.misc.Misc;

/**
 * Xaero's World Map: the towns and stations round the player ({@link MapWaypoints}) on the map, and
 * "Teleport to the ground here" in its right-click menu on the Earth.
 *
 * <p>With Xaero's Minimap installed too, the world map already shows the minimap's waypoints, Real
 * Earth's among them ({@link XaeroWaypoints}); alone, it gets them from {@link Marks}, a square in the
 * waypoint's colour with its letter, and the name while the mouse is over it.
 *
 * <p>The world map's own teleport needs a height, and it has none for places it has not seen, or
 * guesses one; in a dimension 4,000 blocks deep that is often under the ground or high in the air. Real
 * Earth's entry sends the column to the server instead ({@code /earth tp block}), which finds the
 * ground there the way {@code /earth tp} does, generating it first if need be. It is shown when the
 * server would allow it (the command is in the player's command tree only then) and the map shows the
 * Earth. Added by {@code GuiMapMixin}. Only loaded when the world map is.
 */
public final class XaeroWorldMapCompat {
	private static boolean added;

	private XaeroWorldMapCompat() {
	}

	public static void install() {
		if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
			return;
		}
		// The world map sets up its renderers in its own initialiser, which may run after ours.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (added || WorldMap.mapElementRenderHandler == null) {
				return;
			}
			added = true;
			WorldMap.mapElementRenderHandler.add(new Marks());
			RealEarth.LOGGER.info("Added Real Earth's waypoints to Xaero's World Map");
		});
	}

	/** For the world map's right-click menu: the teleport, after the map's own. */
	public static void addTeleport(List<RightClickOption> options, IRightClickableElement map, int x, int z, ResourceKey<Level> dimension) {
		Minecraft mc = Minecraft.getInstance();
		if (options == null || mc.level == null || mc.getConnection() == null
			|| !(dimension == RealEarth.EARTH || dimension == mc.level.dimension() && RealEarth.isEarth(mc.level))) {
			return;
		}
		CommandNode<?> earth = mc.getConnection().getCommands().getRoot().getChild("earth");
		CommandNode<?> tp = earth == null ? null : earth.getChild("tp");
		if (tp == null || tp.getChild("block") == null) {
			return;
		}
		int after = options.size();
		for (int i = 0; i < options.size(); i++) {
			if (options.get(i).getDisplayName().getContents() instanceof TranslatableContents t && t.getKey().contains("teleport")) {
				after = i + 1;
			}
		}
		options.add(after, new RightClickOption("gui.realearth.xaero.teleport", after, map) {
			@Override
			public void onAction(Screen screen) {
				mc.setScreen(null);
				mc.getConnection().sendCommand("earth tp block " + x + " " + z);
			}
		});
	}

	/** Font pixels to the map element's units, which are a third of one at the world map's usual waypoint size. */
	private static final int UNIT = 3;
	/** Half the square's side, in font pixels. */
	private static final int HALF = 5;

	private static int colour(MapWaypoints.Mark m) {
		ChatFormatting c = switch (m.kind()) {
			case MapWaypoints.CAPITAL -> ChatFormatting.GOLD;
			case MapWaypoints.CITY -> ChatFormatting.YELLOW;
			case MapWaypoints.TOWN -> ChatFormatting.WHITE;
			case MapWaypoints.STATION -> ChatFormatting.AQUA;
			default -> ChatFormatting.GRAY;
		};
		return c.getColor();
	}

	/** Real Earth's marks, drawn on the world map; only when it shows the dimension the player is in, which they are for. */
	private static final class Marks extends ElementRenderer<MapWaypoints.Mark, Void, Marks> {
		private VertexConsumer fill;
		/** Whether the map shows the player's dimension, for the provider, which is not told. */
		private static boolean here;

		Marks() {
			super(null, new Provider(), new Reader());
		}

		@Override
		public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
			// Not the map's own waypoint settings: without the minimap they are always off.
			return !shadow && RealEarth.config().mapWaypoints;
		}

		@Override
		public void preRender(ElementRenderInfo info, BufferSource vanilla, MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
			fill = XaeroLib.INSTANCE.getClient().getBufferProvider().getBuffer(CustomRenderTypes.MAP_ELEMENT_TEXT_BG);
			Minecraft mc = Minecraft.getInstance();
			here = mc.level != null && info.mapDimension == mc.level.dimension();
		}

		@Override
		public void postRender(ElementRenderInfo info, BufferSource vanilla, MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
			XaeroLib.INSTANCE.getClient().getBufferProvider().endBatch();
		}

		@Override
		public void renderElementShadow(MapWaypoints.Mark m, boolean hovered, float scale, double partialX, double partialY, ElementRenderInfo info,
			MapElementGraphics graphics, BufferSource vanilla, MultiTextureRenderTypeRendererProvider provider) {
		}

		@Override
		public boolean renderElement(MapWaypoints.Mark m, boolean hovered, double depth, float scale, double partialX, double partialY,
			ElementRenderInfo info, MapElementGraphics graphics, BufferSource vanilla, MultiTextureRenderTypeRendererProvider provider) {
			PoseStack pose = graphics.pose();
			pose.translate(partialX, partialY, 0.0);
			pose.scale(scale * UNIT, scale * UNIT, 1.0F);
			int c = colour(m);
			MapRenderHelper.fillIntoExistingBuffer(pose.last().pose(), fill, -HALF, -HALF, HALF + 1, HALF + 1,
				(c >> 16 & 0xFF) / 255.0F, (c >> 8 & 0xFF) / 255.0F, (c & 0xFF) / 255.0F, 1.0F);
			var font = Minecraft.getInstance().font;
			String letter = m.kind() == MapWaypoints.STATION ? "St" : m.name().substring(0, 1).toUpperCase(Locale.ROOT);
			if (hovered) {
				int half = font.width(m.name()) / 2 + 2;
				MapRenderHelper.fillIntoExistingBuffer(pose.last().pose(), fill, -half, -HALF - 11, half, -HALF - 1, 0.0F, 0.0F, 0.0F, 0.8F);
			}
			// Text on top of the squares, which are drawn first.
			pose.translate(0.0F, 0.0F, 1.0F);
			Misc.drawNormalText(pose, letter, -(font.width(letter) - 1) / 2.0F, -HALF + 2, 0xFF000000, false, vanilla);
			if (hovered) {
				Misc.drawNormalText(pose, m.name(), -(font.width(m.name()) - 1) / 2.0F, -HALF - 10, 0xFFFFFFFF, false, vanilla);
			}
			return false;
		}

		private static final class Provider extends ElementRenderProvider<MapWaypoints.Mark, Void> {
			private Iterator<MapWaypoints.Mark> next = Collections.emptyIterator();

			@Override
			public void begin(ElementRenderLocation location, Void context) {
				next = here ? ClientHere.marks().iterator() : Collections.emptyIterator();
			}

			@Override
			public boolean hasNext(ElementRenderLocation location, Void context) {
				return next.hasNext();
			}

			@Override
			public MapWaypoints.Mark getNext(ElementRenderLocation location, Void context) {
				return next.next();
			}

			@Override
			public void end(ElementRenderLocation location, Void context) {
			}
		}

		private static final class Reader extends ElementReader<MapWaypoints.Mark, Void, Marks> {
			@Override
			public boolean isHidden(MapWaypoints.Mark m, Void context) {
				return false;
			}

			/** For its name under the mouse; a right click on it still opens the map's menu, as it offers nothing of its own. */
			@Override
			public boolean isInteractable(ElementRenderLocation location, MapWaypoints.Mark m) {
				return true;
			}

			@Override
			public double getRenderX(MapWaypoints.Mark m, Void context, float partialTicks) {
				return m.x() + 0.5;
			}

			@Override
			public double getRenderZ(MapWaypoints.Mark m, Void context, float partialTicks) {
				return m.z() + 0.5;
			}

			@Override
			public int getInteractionBoxLeft(MapWaypoints.Mark m, Void context, float partialTicks) {
				return -HALF * UNIT;
			}

			@Override
			public int getInteractionBoxRight(MapWaypoints.Mark m, Void context, float partialTicks) {
				return (HALF + 1) * UNIT;
			}

			@Override
			public int getInteractionBoxTop(MapWaypoints.Mark m, Void context, float partialTicks) {
				return -HALF * UNIT;
			}

			@Override
			public int getInteractionBoxBottom(MapWaypoints.Mark m, Void context, float partialTicks) {
				return (HALF + 1) * UNIT;
			}

			@Override
			public int getRenderBoxLeft(MapWaypoints.Mark m, Void context, float partialTicks) {
				return -HALF * UNIT;
			}

			@Override
			public int getRenderBoxRight(MapWaypoints.Mark m, Void context, float partialTicks) {
				return (HALF + 1) * UNIT;
			}

			@Override
			public int getRenderBoxTop(MapWaypoints.Mark m, Void context, float partialTicks) {
				return -HALF * UNIT;
			}

			@Override
			public int getRenderBoxBottom(MapWaypoints.Mark m, Void context, float partialTicks) {
				return (HALF + 1) * UNIT;
			}

			@Override
			public int getLeftSideLength(MapWaypoints.Mark m, Minecraft mc) {
				return 9 + mc.font.width(m.name());
			}

			@Override
			public String getMenuName(MapWaypoints.Mark m) {
				return m.name();
			}

			@Override
			public String getFilterName(MapWaypoints.Mark m) {
				return m.name();
			}

			@Override
			public int getMenuTextFillLeftPadding(MapWaypoints.Mark m) {
				return 0;
			}

			@Override
			public int getRightClickTitleBackgroundColor(MapWaypoints.Mark m) {
				return 0xFF000000 | colour(m);
			}

			@Override
			public boolean shouldScaleBoxWithOptionalScale() {
				return true;
			}
		}
	}
}
