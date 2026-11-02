package de.loothub.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.JobHistoryScreen;
import de.loothub.client.jobs.JobWidgetEditScreen;

/** Reiter oben mittig im Loothub+-Menü: „Verlauf“, „Markt“ und „Job-Widget“. */
public final class Tabs {
	public enum Tab {
		HISTORY("loothub.tab.history"),
		MARKET("loothub.tab.market"),
		JOBS("loothub.tab.jobs");

		private final String translationKey;

		Tab(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	public static final int HEIGHT = 16;
	private static final int WIDTH = 64;
	private static final int GAP = 4;

	private Tabs() {
	}

	/**
	 * Erzeugt die Reiter-Knöpfe, oben mittig auf dem Bildschirm; der aktuelle Reiter ist ausgegraut.
	 * Der Aufrufer fügt sie per addRenderableWidget hinzu.
	 */
	public static List<Button> create(Tab current, int screenWidth, int y) {
		Tab[] tabs = Tab.values();
		int total = tabs.length * WIDTH + (tabs.length - 1) * GAP;
		int x = (screenWidth - total) / 2;
		Button[] buttons = new Button[tabs.length];
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			Button b = Button.builder(Component.translatable(tab.translationKey), btn -> open(tab))
					.bounds(x + i * (WIDTH + GAP), y, WIDTH, HEIGHT)
					.build();
			b.active = tab != current;
			buttons[i] = b;
		}
		return List.of(buttons);
	}

	/** Knopf oben links: erscheint nur, wenn ein Update verfügbar ist. */
	public static Button updateButton(int y) {
		Button b = Button.builder(Component.empty(), btn -> Updater.installAsync())
				.bounds(8, y, 100, HEIGHT)
				.build();
		refreshUpdateButton(b);
		return b;
	}

	/** Beschriftung und Sichtbarkeit an den Update-Zustand anpassen (jeden Tick aufrufen). */
	public static void refreshUpdateButton(Button b) {
		String version = Updater.latestVersion();
		switch (Updater.state()) {
			case AVAILABLE -> {
				b.visible = true;
				b.active = true;
				b.setMessage(Component.translatable("loothub.update.install", version));
				b.setTooltip(Tooltip.create(Component.translatable("loothub.update.install.tooltip",
						Updater.currentVersion(), version)));
			}
			case DOWNLOADING -> {
				b.visible = true;
				b.active = false;
				b.setMessage(Component.translatable("loothub.update.downloading"));
			}
			case INSTALLED -> {
				b.visible = true;
				b.active = false;
				b.setMessage(Component.translatable("loothub.update.restart", version));
				b.setTooltip(Tooltip.create(Component.translatable("loothub.update.restart.tooltip")));
			}
			case ERROR -> {
				// Nur anzeigen, wenn ein Update gefunden, aber nicht installiert werden konnte
				b.visible = version != null;
				b.active = false;
				b.setMessage(Component.translatable("loothub.update.failed"));
				String msg = Updater.errorMessage();
				b.setTooltip(Tooltip.create(Component.literal(msg == null ? "" : msg)));
			}
			default -> b.visible = false;
		}
	}

	public static void open(Tab tab) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(switch (tab) {
			case MARKET -> new MarketScreen();
			case JOBS -> new JobWidgetEditScreen();
			case HISTORY -> new JobHistoryScreen();
		});
	}
}
