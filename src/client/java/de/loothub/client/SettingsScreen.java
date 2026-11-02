package de.loothub.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import de.loothub.client.jobs.JobTracker;
import de.loothub.client.jobs.JobWidgetEditScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Einstellungen von Loothub+ – öffnen mit {@code /loothub} oder dem ⚙-Knopf im Loothub+-Menü.
 * Enthält das Job-Widget (an/aus, verschieben, Sitzung zurücksetzen), Tooltip-Preise, Statistik und Updates.
 */
public class SettingsScreen extends Screen {
	private static final int PANEL = 0xD0101018;
	private static final int BORDER = 0xFF3C3C4C;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFB0B0C0;
	private static final int FAINT = 0xFF808090;
	private static final int GOLD = 0xFFFFC040;

	private static final int BUTTON_H = 20;
	private static final int ROW_GAP = 4;
	private static final int SECTION_GAP = 14;

	private Button resetButton;
	private Button updateButton;
	private long resetFeedbackUntil;

	private int panelX, panelY, panelW, panelH;
	/** Überschriften der Abschnitte mit ihrer y-Position. */
	private final java.util.List<Object[]> headers = new java.util.ArrayList<>();
	private int updateInfoY;

	public SettingsScreen() {
		super(Component.translatable("loothub.settings.title"));
	}

	@Override
	protected void init() {
		headers.clear();
		WidgetConfig c = WidgetConfig.get();
		updateButton = addRenderableWidget(Tabs.updateButton(4));

		panelW = Mth.clamp(width - 40, 260, 340);
		panelX = (width - panelW) / 2;
		int inner = panelW - 24;
		int half = (inner - ROW_GAP) / 2;
		int x = panelX + 12;

		// Gesamthöhe vorab: 4 Abschnitte
		int contentH = 4 * (10 + 4) + 4 * BUTTON_H + 3 * ROW_GAP + 3 * SECTION_GAP + 14 + 10;
		panelH = contentH + 24;
		panelY = Math.max(24, (height - panelH - BUTTON_H - 10) / 2);
		int y = panelY + 10;

		// --- Job-Widget
		y = header(y, "loothub.settings.section.job_widget");
		addRenderableWidget(toggle(x, y, half, "loothub.settings.job_widget",
				() -> c.jobWidgetEnabled, v -> c.jobWidgetEnabled = v,
				"loothub.settings.job_widget.tooltip"));
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
						b -> minecraft.setScreen(new JobWidgetEditScreen(this)))
				.bounds(x + half + ROW_GAP, y, half, BUTTON_H)
				.tooltip(Tooltip.create(Component.translatable("loothub.settings.job_widget.move.tooltip")))
				.build());
		y += BUTTON_H + ROW_GAP;
		resetButton = addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_session.reset"), b -> {
					JobTracker.reset();
					resetFeedbackUntil = System.currentTimeMillis() + 2000;
					b.setMessage(Component.translatable("loothub.settings.job_session.reset.done").withStyle(ChatFormatting.GREEN));
				})
				.bounds(x, y, inner, BUTTON_H)
				.tooltip(Tooltip.create(Component.translatable("loothub.settings.job_session.reset.tooltip")))
				.build());
		y += BUTTON_H + SECTION_GAP;

		// --- Marktpreise
		y = header(y, "loothub.settings.section.market");
		addRenderableWidget(toggle(x, y, inner, "loothub.settings.tooltip_prices",
				() -> c.tooltipPrices, v -> c.tooltipPrices = v, "loothub.settings.tooltip_prices.tooltip"));
		y += BUTTON_H + SECTION_GAP;

		// --- Statistik
		y = header(y, "loothub.settings.section.stats");
		addRenderableWidget(toggle(x, y, inner, "loothub.settings.stats",
				() -> c.statsEnabled, v -> c.statsEnabled = v, "loothub.settings.stats.tooltip"));
		y += BUTTON_H + SECTION_GAP;

		// --- Updates
		y = header(y, "loothub.settings.section.updates");
		addRenderableWidget(toggle(x, y, half, "loothub.settings.update_check",
				() -> c.updateCheck, v -> c.updateCheck = v, "loothub.settings.update_check.tooltip"));
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.update_now"), b -> Updater.checkAgain())
				.bounds(x + half + ROW_GAP, y, half, BUTTON_H).build());
		y += BUTTON_H + 4;
		updateInfoY = y;

		// --- Unten: Menü öffnen und Fertig
		int by = panelY + panelH + 8;
		int bw = (panelW - ROW_GAP) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.open_menu"),
						b -> Tabs.open(Tabs.Tab.HISTORY))
				.bounds(panelX, by, bw, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.done"), b -> onClose())
				.bounds(panelX + bw + ROW_GAP, by, bw, BUTTON_H).build());
	}

	private int header(int y, String key) {
		headers.add(new Object[]{Component.translatable(key), y});
		return y + 14;
	}

	/** Ein-/Aus-Knopf, der die Einstellung sofort speichert. */
	private Button toggle(int x, int y, int w, String labelKey, BooleanSupplier get, Consumer<Boolean> set, String tooltipKey) {
		Button b = Button.builder(toggleLabel(labelKey, get.getAsBoolean()), btn -> {
					set.accept(!get.getAsBoolean());
					WidgetConfig.get().save();
					btn.setMessage(toggleLabel(labelKey, get.getAsBoolean()));
				})
				.bounds(x, y, w, BUTTON_H)
				.tooltip(Tooltip.create(Component.translatable(tooltipKey)))
				.build();
		return b;
	}

	private static Component toggleLabel(String labelKey, boolean on) {
		return Component.translatable(labelKey).append(": ")
				.append(Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off")
						.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	@Override
	public void tick() {
		super.tick();
		Tabs.refreshUpdateButton(updateButton);
		if (resetFeedbackUntil > 0 && System.currentTimeMillis() > resetFeedbackUntil) {
			resetFeedbackUntil = 0;
			resetButton.setMessage(Component.translatable("loothub.settings.job_session.reset"));
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Panel zuerst, damit die Knöpfe darüber liegen
		g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
		g.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
		g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);
		g.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER);
		g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, BORDER);

		super.render(g, mouseX, mouseY, delta);

		Component t = getTitle();
		g.drawString(font, t, (width - font.width(t)) / 2, Math.max(4, panelY - 14), TEXT, true);
		for (Object[] h : headers) {
			g.drawString(font, (Component) h[0], panelX + 12, (int) h[1], GOLD, false);
		}

		// Update-Stand
		String current = Updater.currentVersion();
		String latest = Updater.latestVersion();
		Component info = switch (Updater.state()) {
			case CHECKING -> Component.translatable("loothub.settings.update.checking", current);
			case UP_TO_DATE -> Component.translatable("loothub.settings.update.up_to_date", current);
			case AVAILABLE, DOWNLOADING, INSTALLED -> Component.translatable("loothub.settings.update.available", current, latest);
			case ERROR -> Component.translatable("loothub.settings.update.error", current);
			default -> Component.translatable("loothub.settings.update.idle", current);
		};
		g.drawString(font, info, panelX + 12, updateInfoY, Updater.state() == Updater.State.AVAILABLE ? GOLD : MUTED, false);

		Component hint = Component.translatable("loothub.settings.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, height - 12, FAINT, false);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.nextStratum();
		g.fill(0, 0, width, height, 0xE00B0D12);
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
