package de.loothub.client;


import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;


/** Update-Knopf, der in der Titelleiste aller Loothub+-Seiten erscheint, sobald ein Update verfügbar ist. */
public final class Tabs {
	public static final int HEIGHT = 16;

	private Tabs() {
	}

	/** Erscheint nur, wenn ein Update verfügbar ist. Position setzt das Fenster. */
	public static Button updateButton(int y) {
		Button b = Button.builder(Component.empty(), btn -> Updater.installAsync())
				.bounds(8, y, 80, HEIGHT)
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
				b.setTooltip(Tooltip.create(Updater.errorReason()));
			}
			default -> b.visible = false;
		}
	}
}
