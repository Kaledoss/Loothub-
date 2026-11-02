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

	private final List<Card> cards = new ArrayList<>();
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
		WidgetConfig c = WidgetConfig.get();
		labelW = Math.min(130, cw / 3);

		// Versionsblock
		versionY = ct;
		int bw = 100;
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.update_now"), b -> Updater.checkAgain())
				.bounds(cr - 8 - bw, versionY + 24, bw, 16).build());

		int y = versionY + 50;
		y = card(y, 40, "loothub.settings.update_check", "loothub.settings.update_check.tooltip",
				() -> c.updateCheck, v -> c.updateCheck = v);
		y = card(y, 40, "loothub.settings.tooltip_prices", "loothub.settings.tooltip_prices.tooltip",
				() -> c.tooltipPrices, v -> c.tooltipPrices = v);
		y = card(y, 40, "loothub.settings.stats", "loothub.settings.stats.tooltip",
				() -> c.statsEnabled, v -> c.statsEnabled = v);

		// Job-Widget: Schalter + zwei Knöpfe unter der Beschreibung
		int jobY = y;
		card(jobY, 58, "loothub.settings.section.job_widget", "loothub.settings.job_widget.tooltip",
				() -> c.jobWidgetEnabled, v -> c.jobWidgetEnabled = v);
		int descX = cl + labelW + 12;
		int half = (cr - 8 - descX - 4) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
						b -> minecraft.setScreen(new JobWidgetEditScreen(this)))
				.bounds(descX, jobY + 36, half, 16).build());
		resetButton = addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_session.reset"), b -> {
					JobTracker.reset();
					resetFeedbackUntil = System.currentTimeMillis() + 2000;
					b.setMessage(Component.translatable("loothub.settings.job_session.reset.done").withStyle(ChatFormatting.GREEN));
				})
				.bounds(descX + half + 4, jobY + 36, half, 16).build());
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
		box(g, cl, versionY, cw, 44);
		for (Card card : cards) {
			box(g, cl, card.y(), cw, card.h());
			g.fill(cl + labelW + 6, card.y() + 5, cl + labelW + 7, card.y() + card.h() - 5, CARD_EDGE);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
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
		if (event.button() == 0) {
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
	public void onClose() {
		WidgetConfig.get().save();
		super.onClose();
	}
}
