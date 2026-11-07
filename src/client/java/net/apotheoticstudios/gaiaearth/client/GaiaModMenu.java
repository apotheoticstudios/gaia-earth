package net.apotheoticstudios.gaiaearth.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.apotheoticstudios.gaiaearth.EarthConfig;
import net.apotheoticstudios.gaiaearth.RealEarth;

public final class GaiaModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return RiverSettings::new;
	}

	private static final class RiverSettings extends Screen {
		private enum Page { CURRENT, PARTICLES, SOUND }

		private final Screen parent;
		private final Path file = FabricLoader.getInstance().getConfigDir().resolve("realearth.json");
		private final EarthConfig draft;
		private Component status = Component.literal("Saved settings apply after restart.");
		private boolean readable = true;
		private Page page = Page.CURRENT;

		private RiverSettings(Screen parent) {
			super(Component.literal("Gaia Earth River Settings"));
			this.parent = parent;
			EarthConfig loaded;
			try {
				loaded = EarthConfig.read(file);
			} catch (IOException | RuntimeException e) {
				RealEarth.LOGGER.warn("Could not read river settings from {}", file, e);
				status = Component.literal("Could not read realearth.json.");
				readable = false;
				loaded = new EarthConfig();
			}
			draft = loaded;
		}

		@Override
		protected void init() {
			int x = width / 2 - 100;
			int top = controlsTop();
			tab(x, top, "Current", Page.CURRENT);
			tab(x + 67, top, "Particles", Page.PARTICLES);
			tab(x + 134, top, "Sound", Page.SOUND);
			int row = top + 24;
			switch (page) {
				case CURRENT -> {
					toggle(x, row, "Mapped currents", () -> draft.mappedRiverCurrents, value -> draft.mappedRiverCurrents = value);
					slider(x, row + 22, "Current strength", "%", 0, 200, () -> draft.riverCurrentStrength, value -> draft.riverCurrentStrength = value);
					slider(x, row + 44, "Current depth", " blocks", 1, 16, () -> draft.riverCurrentDepth, value -> draft.riverCurrentDepth = value);
				}
				case PARTICLES -> {
					toggle(x, row, "Downstream textures", () -> draft.riverVisualFlow, value -> draft.riverVisualFlow = value);
					slider(x, row + 22, "Wake particles per tick", "", 0, 12, () -> draft.riverParticlesPerTick, value -> draft.riverParticlesPerTick = value);
					slider(x, row + 44, "Wake drift speed", "%", 0, 200, () -> draft.riverWakeSpeed, value -> draft.riverWakeSpeed = value);
					slider(x, row + 66, "Effects distance", " blocks", 4, 32, () -> draft.riverEffectRadius, value -> draft.riverEffectRadius = value);
				}
				case SOUND -> {
					toggle(x, row, "River sounds", () -> draft.riverSounds, value -> draft.riverSounds = value);
					slider(x, row + 22, "River sound volume", "%", 0, 100, () -> draft.riverSoundVolume, value -> draft.riverSoundVolume = value);
				}
			}
			addRenderableWidget(Button.builder(Component.literal("Save"), button -> save()).bounds(x, top + 114, 98, 20).build()).active = readable;
			addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose()).bounds(x + 102, top + 114, 98, 20).build());
		}

		private int controlsTop() {
			return Math.max(30, height / 2 - 56);
		}

		private void tab(int x, int y, String label, Page target) {
			addRenderableWidget(Button.builder(Component.literal(label), button -> {
				page = target;
				rebuildWidgets();
			}).bounds(x, y, 66, 20).build()).active = page != target;
		}

		private void slider(int x, int y, String label, String unit, int min, int max, IntSupplier setting, IntConsumer change) {
			addRenderableWidget(new AbstractSliderButton(x, y, 200, 20, sliderLabel(label, unit, setting.getAsInt()),
				(setting.getAsInt() - min) / (double) (max - min)) {
				@Override
				protected void updateMessage() {
					setMessage(sliderLabel(label, unit, setting.getAsInt()));
				}

				@Override
				protected void applyValue() {
					change.accept(min + (int) Math.round(this.value * (max - min)));
				}
			});
		}

		private static Component sliderLabel(String label, String unit, int value) {
			return Component.literal(label + "  " + value + unit);
		}

		private void toggle(int x, int y, String label, Supplier<Boolean> value, Consumer<Boolean> change) {
			addRenderableWidget(Button.builder(toggleLabel(label, value.get()), button -> {
				boolean next = !value.get();
				change.accept(next);
				button.setMessage(toggleLabel(label, next));
			}).bounds(x, y, 200, 20).build());
		}

		private static Component toggleLabel(String label, boolean enabled) {
			return Component.literal(label + (enabled ? "  On" : "  Off"));
		}

		private void save() {
			try {
				draft.saveRiverSettings(file);
				onClose();
			} catch (IOException | RuntimeException e) {
				RealEarth.LOGGER.warn("Could not save river settings to {}", file, e);
				status = Component.literal("Could not save realearth.json.");
			}
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			super.render(graphics, mouseX, mouseY, partialTick);
			int top = controlsTop();
			graphics.drawCenteredString(font, title, width / 2, top - 25, 0xFFFFFFFF);
			graphics.drawCenteredString(font, status, width / 2, top - 12, 0xFFCCCCCC);
		}

		@Override
		public void onClose() {
			minecraft.setScreen(parent);
		}
	}
}
