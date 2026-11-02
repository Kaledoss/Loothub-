package de.loothub.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.JobWidgetEditScreen;

/** Reiter oben links im Loothub+-Menü: „Markt“ und „Job-Widget“. */
public final class Tabs {
	public enum Tab {
		MARKET("loothub.tab.market"),
		JOBS("loothub.tab.jobs");

		private final String translationKey;

		Tab(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	public static final int HEIGHT = 16;
	private static final int WIDTH = 72;
	private static final int GAP = 4;

	private Tabs() {
	}

	/** Erzeugt die Reiter-Knöpfe; der aktuelle Reiter ist ausgegraut. Der Aufrufer fügt sie per addRenderableWidget hinzu. */
	public static List<Button> create(Tab current, int x, int y) {
		Tab[] tabs = Tab.values();
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

	public static void open(Tab tab) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(tab == Tab.MARKET ? new MarketScreen() : new JobWidgetEditScreen());
	}
}
