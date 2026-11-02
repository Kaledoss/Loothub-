package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.jobs.JobTracker;
import de.loothub.client.jobs.JobWidget;
import de.loothub.client.jobs.JobWidgetEditScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Seite „Einstellungen“ (auch über {@code /loothub}): oben Version und Update-Stand, darunter Karten mit
 * Schalter links und Beschreibung rechts – Auto-Update, Preise im Tooltip, Statistik und Job-Widget.
 */
public class SettingsScreen extends LoothubScreen {
	private static final int CARD = 0xE01C1315;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int TRACK_OFF = 0xFF3A2A2C;
	private static final int GREEN = 0xFF5BD68A;
	private static final int GOLD = 0xFFFFC040;
	private static final int GAP = 6;

	/** Ein Schalter auf einer Karte. */
	private record Toggle(int x, int y, BooleanSupplier get, Consumer<Boolean> set) {
		static final int W = 30;
		static final int H = 12;

		boolean contains(double mx, double my) {
			return mx >= x && mx < x + W && my >= y && my < y + H;
		}
	}

	/** Eine Karte: Titel + optionaler Schalter links, Beschreibung rechts. */
	private record Card(int y, int h, String titleKey, String descKey, Toggle toggle) {
	}

	/** Kästchen für eine Zeile des Job-Widgets. */
	private record Check(int x, int y, int w, String id) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y - 1 && my < y + 10;
		}
	}

	private final List<Card> cards = new ArrayList<>();
	private final List<Check> checks = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	/** Scroll-Position bleibt beim Neuaufbau (Fenstergröße, Scrollen) erhalten. */
	private static int scroll;
	private int contentH;
	private boolean needsRebuild;
	private int rowsLabelY;
	private Button resetButton;
	private long resetFeedbackUntil;
	private int versionY;
	private int labelW;

	public SettingsScreen() {
		super(Page.SETTINGS);
	}

	@Override
	protected void initPage() {
		cards.clear();
		checks.clear();
		buttons.clear();
		WidgetConfig c = WidgetConfig.get();
		labelW = Math.min(130, cw / 3);

		// Versionsblock
		versionY = ct - scroll;
		int bw = 100;
		buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.update_now"), b -> Updater.checkAgain())
				.bounds(cr - 8 - bw, versionY + 24, bw, 16).build()));

		int y = versionY + 50;
		y = card(y, 40, "loothub.settings.update_check", "loothub.settings.update_check.tooltip",
				() -> c.updateCheck, v -> c.updateCheck = v);
		y = card(y, 40, "loothub.settings.tooltip_prices", "loothub.settings.tooltip_prices.tooltip",
				() -> c.tooltipPrices, v -> c.tooltipPrices = v);
		y = card(y, 40, "loothub.settings.stats", "loothub.settings.stats.tooltip",
				() -> c.statsEnabled, v -> c.statsEnabled = v);

		y = card(y, 40, "loothub.settings.quick_panel", "loothub.settings.quick_panel.tooltip",
				() -> c.quickPanelEnabled, v -> c.quickPanelEnabled = v);
		y = card(y, 40, "loothub.settings.pulse", "loothub.settings.pulse.tooltip",
				() -> c.pulseBorders, v -> c.pulseBorders = v);

		// Job-Widget: Schalter + zwei Knöpfe unter der Beschreibung + Kästchen für jede Zeile
		int jobY = y;
		int end = card(jobY, 92, "loothub.settings.section.job_widget", "loothub.settings.job_widget.tooltip",
				() -> c.jobWidgetEnabled, v -> c.jobWidgetEnabled = v);
		int descX = cl + labelW + 12;
		int half = (cr - 8 - descX - 4) / 2;
		buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
						b -> minecraft.setScreen(new JobWidgetEditScreen(this)))
				.bounds(descX, jobY + 36, half, 16).build()));
		resetButton = addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_session.reset"), b -> {
					JobTracker.reset();
					resetFeedbackUntil = System.currentTimeMillis() + 2000;
					b.setMessage(Component.translatable("loothub.settings.job_session.reset.done").withStyle(ChatFormatting.GREEN));
				})
				.bounds(descX + half + 4, jobY + 36, half, 16).build());
		buttons.add(resetButton);

		rowsLabelY = jobY + 60;
		List<String> ids = JobWidget.ROW_IDS;
		int perRow = (ids.size() + 1) / 2;
		int colW = (cr - 8 - descX) / perRow;
		for (int i = 0; i < ids.size(); i++) {
			int col = i % perRow;
			int row = i / perRow;
			checks.add(new Check(descX + col * colW, jobY + 60 + row * 14, colW - 4, ids.get(i)));
		}

		// Scrollen: Inhalt höher als der sichtbare Bereich?
		contentH = end - GAP - versionY;
		int max = maxScroll();
		if (scroll > max) {
			scroll = max;
			needsRebuild = true;
		}
		for (Button b : buttons) {
			b.visible = b.getY() >= ct && b.getY() + b.getHeight() <= cb;
		}
	}

	private int maxScroll() {
		return Math.max(0, contentH - (cb - ct));
	}

	private static String rowLabel(String id) {
		String s = Component.translatable("loothub.job.row." + id).getString();
		return s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
	}

	private int card(int y, int h, String titleKey, String descKey, BooleanSupplier get, Consumer<Boolean> set) {
		int tx = cl + 8 + (labelW - Toggle.W) / 2;
		Toggle t = new Toggle(tx, y + 20, get, set);
		cards.add(new Card(y, h, titleKey, descKey, t));
		return y + h + GAP;
	}

	@Override
	public void tick() {
		super.tick();
		if (needsRebuild) {
			needsRebuild = false;
			rebuildWidgets();
			return;
		}
		if (resetFeedbackUntil > 0 && System.currentTimeMillis() > resetFeedbackUntil) {
			resetFeedbackUntil = 0;
			resetButton.setMessage(Component.translatable("loothub.settings.job_session.reset"));
		}
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.renderBackground(g, mouseX, mouseY, delta);
		// Karten-Hintergründe unter den Knöpfen
		g.enableScissor(cl, ct, cr, cb);
		box(g, cl, versionY, cw, 44);
		for (Card card : cards) {
			box(g, cl, card.y(), cw, card.h());
			g.fill(cl + labelW + 6, card.y() + 5, cl + labelW + 7, card.y() + card.h() - 5, CARD_EDGE);
		}
		g.disableScissor();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.enableScissor(cl, ct, cr, cb);
		renderVersion(g);
		for (Card card : cards) {
			Component title = Component.translatable(card.titleKey());
			g.drawString(font, title, cl + 8 + (labelW - font.width(title)) / 2, card.y() + 6, TEXT, false);
			drawToggle(g, card.toggle(), mouseX, mouseY);

			int descX = cl + labelW + 12;
			int y = card.y() + 6;
			for (FormattedCharSequence line : font.split(Component.translatable(card.descKey()), cr - 8 - descX)) {
				g.drawString(font, line, descX, y, MUTED, false);
				y += 10;
			}
		}

		// Zeilen des Job-Widgets
		Component rowsTitle = Component.translatable("loothub.settings.job_widget.rows");
		g.drawString(font, rowsTitle, cl + 8 + (labelW - font.width(rowsTitle)) / 2, rowsLabelY + 7, TEXT, false);
		WidgetConfig c = WidgetConfig.get();
		for (Check ch : checks) {
			boolean on = c.jobRowVisible(ch.id());
			boolean hover = ch.contains(mouseX, mouseY) && mouseY >= ct && mouseY < cb;
			g.fill(ch.x(), ch.y(), ch.x() + 9, ch.y() + 9, hover ? 0xFFFFFFFF : 0xFF000000);
			g.fill(ch.x() + 1, ch.y() + 1, ch.x() + 8, ch.y() + 8, on ? RED : TRACK_OFF);
			if (on) {
				g.fill(ch.x() + 3, ch.y() + 3, ch.x() + 6, ch.y() + 6, 0xFFE8E0E0);
			}
			g.drawString(font, rowLabel(ch.id()), ch.x() + 12, ch.y() + 1, on ? TEXT : FAINT, false);
		}
		g.disableScissor();

		// Scrollleiste
		int max = maxScroll();
		if (max > 0) {
			int trackH = cb - ct;
			int barH = Math.max(16, trackH * trackH / (trackH + max));
			int barY = ct + (trackH - barH) * scroll / max;
			g.fill(cr - 3, ct, cr - 1, cb, 0x40FFFFFF);
			g.fill(cr - 3, barY, cr - 1, barY + barH, RED);
		}
	}

	private void renderVersion(GuiGraphics g) {
		Component name = Component.literal("Loothub+ ").withStyle(ChatFormatting.BOLD)
				.append(Component.literal(Updater.currentVersion()).withStyle(ChatFormatting.BOLD))
				.withStyle(s -> s.withColor(RED & 0xFFFFFF));
		g.drawString(font, name, cl + 10, versionY + 8, TEXT, true);
		g.drawString(font, Component.translatable("loothub.settings.version.sub"), cl + 10, versionY + 22, FAINT, false);

		// Status-Kästchen oben rechts
		Component status;
		int color;
		switch (Updater.state()) {
			case CHECKING -> {
				status = Component.translatable("loothub.settings.status.checking");
				color = MUTED;
			}
			case UP_TO_DATE -> {
				status = Component.translatable("loothub.settings.status.up_to_date");
				color = GREEN;
			}
			case AVAILABLE, DOWNLOADING -> {
				status = Component.translatable("loothub.settings.status.available", Updater.latestVersion());
				color = GOLD;
			}
			case INSTALLED -> {
				status = Component.translatable("loothub.update.restart.tooltip");
				color = GREEN;
			}
			case ERROR -> {
				status = Updater.errorReason();
				color = RED;
			}
			default -> {
				status = Component.translatable("loothub.settings.status.idle");
				color = MUTED;
			}
		}
		int sw = font.width(status) + 10;
		int sx = cr - 8 - Math.max(sw, 100);
		int sy = versionY + 5;
		int w = Math.max(sw, 100);
		g.fill(sx, sy, sx + w, sy + 15, color);
		g.fill(sx + 1, sy + 1, sx + w - 1, sy + 14, 0xFF0F0A0B);
		g.drawString(font, status, sx + (w - font.width(status)) / 2, sy + 4, color, false);
	}

	private void drawToggle(GuiGraphics g, Toggle t, int mx, int my) {
		boolean on = t.get().getAsBoolean();
		boolean hover = t.contains(mx, my);
		int track = on ? RED : TRACK_OFF;
		g.fill(t.x(), t.y(), t.x() + Toggle.W, t.y() + Toggle.H, hover ? 0xFFFFFFFF : 0xFF000000);
		g.fill(t.x() + 1, t.y() + 1, t.x() + Toggle.W - 1, t.y() + Toggle.H - 1, track);
		int knobX = on ? t.x() + Toggle.W - 11 : t.x() + 1;
		g.fill(knobX, t.y() + 1, knobX + 10, t.y() + Toggle.H - 1, 0xFFE8E0E0);
		Component state = Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off");
		g.drawString(font, state, t.x() + Toggle.W + 5, t.y() + 2, on ? TEXT : FAINT, false);
	}

	private static void box(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, CARD);
		g.fill(x, y + h - 1, x + w, y + h, CARD_EDGE);
		g.fill(x, y, x + w, y + 1, 0xFF2A171A);
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && event.y() >= ct && event.y() < cb) {
			for (Check ch : checks) {
				if (ch.contains(event.x(), event.y())) {
					WidgetConfig c = WidgetConfig.get();
					c.setJobRowVisible(ch.id(), !c.jobRowVisible(ch.id()));
					c.save();
					return true;
				}
			}
			for (Card card : cards) {
				Toggle t = card.toggle();
				if (t.contains(event.x(), event.y())) {
					t.set().accept(!t.get().getAsBoolean());
					WidgetConfig.get().save();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (maxScroll() > 0 && mx >= cl && mx < cr && my >= ct && my < cb) {
			int next = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(vertical * 20)));
			if (next != scroll) {
				scroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		super.onClose();
	}
}
