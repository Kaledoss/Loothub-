package de.loothub.client.streamer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.ConfirmScreen;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Seite „Streamer“ (nur bei eingeschaltetem Streamer-Modus): Twitch-Konto verbinden, Twitch-Chat im Minecraft-Chat,
 * Liste der eingelösten Kanalbelohnungen mit Uhrzeit zum Abhaken.
 */
public class StreamerScreen extends LoothubScreen {
	private static final int GREEN = 0xFF55E06A;
	private static final int GOLD = 0xFFF2C14E;
	private static final int BAD = 0xFFFF6B6B;
	private static final int PURPLE = 0xFF000000 | StreamerChat.PURPLE;
	private static final int ROW_H = 22;
	private static final int[] ROW_CHOICES = {3, 5, 8, 10, 15};
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");

	private static boolean showAll;
	private static boolean alertsTab;
	private static final int ALERT_ROW = 20;

	private int boxY;
	private int listTop;
	private int scroll;
	private TwitchAuth.Status shownStatus;
	private Redemptions.Status shownRedeem;

	public StreamerScreen() {
		super(Page.STREAMER);
	}

	@Override
	protected void initPage() {
		WidgetConfig c = WidgetConfig.get();
		shownStatus = TwitchAuth.status();
		shownRedeem = Redemptions.status();
		boxY = ct;
		int bw = 120;
		int bx = cr - 6 - bw;
		switch (shownStatus) {
			case NOT_CONNECTED, ERROR -> addRenderableWidget(Button.builder(
					Component.translatable("loothub.streamer.connect"), b -> TwitchAuth.startLogin())
					.bounds(bx, boxY + 14, bw, 16).build());
			case STARTING -> addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
					b -> TwitchAuth.cancelLogin()).bounds(bx, boxY + 14, bw, 16).build());
			case WAITING -> {
				int sw = 76;
				addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.open_page"),
						b -> Browser.open(TwitchAuth.verifyUri())).bounds(cr - 6 - 3 * sw - 8, boxY + 14, sw, 16).build());
				addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.copy_code"),
						b -> minecraft.keyboardHandler.setClipboard(TwitchAuth.userCode())).bounds(cr - 6 - 2 * sw - 4, boxY + 14, sw, 16).build());
				addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
						b -> TwitchAuth.cancelLogin()).bounds(cr - 6 - sw, boxY + 14, sw, 16).build());
			}
			case CONNECTED -> {
				addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.disconnect"),
						b -> minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.streamer.disconnect"),
								Component.translatable("loothub.streamer.disconnect.confirm", TwitchAuth.displayName()),
								TwitchAuth::logout)))
						.bounds(bx, boxY + 14, bw, 16).build());
				if (TwitchAuth.needsRelogin()) {
					addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.relogin")
									.withStyle(ChatFormatting.GOLD), b -> TwitchAuth.startLogin())
							.bounds(bx - bw - 4, boxY + 14, bw, 16).build());
				}
			}
			default -> {
				// NO_CLIENT_ID: nur Hinweis
			}
		}

		// Reiter und allgemeine Schalter
		int y = boxY + 52;
		int tw = 100;
		addRenderableWidget(Button.builder(tab("loothub.streamer.tab.rewards", !alertsTab), b -> {
			alertsTab = false;
			scroll = 0;
			rebuildWidgets();
		}).bounds(cl, y, tw, 16).build()).active = alertsTab;
		addRenderableWidget(Button.builder(tab("loothub.streamer.tab.alerts", alertsTab), b -> {
			alertsTab = true;
			scroll = 0;
			rebuildWidgets();
		}).bounds(cl + tw + 4, y, tw, 16).build()).active = !alertsTab;
		int gw = Math.min(130, (cw - 2 * tw - 16) / 2);
		addRenderableWidget(Button.builder(onOff("loothub.streamer.chat", c.streamerChat), b -> {
			c.streamerChat = !c.streamerChat;
			c.save();
			Streamer.apply();
			b.setMessage(onOff("loothub.streamer.chat", c.streamerChat));
		}).bounds(cr - 2 * gw - 4, y, gw, 16).build());
		addRenderableWidget(Button.builder(onOff("loothub.streamer.sound", c.streamerSound), b -> {
			c.streamerSound = !c.streamerSound;
			c.save();
			b.setMessage(onOff("loothub.streamer.sound", c.streamerSound));
		}).bounds(cr - gw, y, gw, 16).build());

		int fy = y + 22;
		if (alertsTab) {
			initAlerts(c, fy);
			return;
		}

		// Belohnungen: Filter, HUD, Aufräumen
		int n = 5;
		int w = (cw - (n - 1) * 4) / n;
		addRenderableWidget(Button.builder(tab("loothub.streamer.filter.open", !showAll), b -> {
			showAll = false;
			scroll = 0;
			rebuildWidgets();
		}).bounds(cl, fy, w, 16).build()).active = showAll;
		addRenderableWidget(Button.builder(tab("loothub.streamer.filter.all", showAll), b -> {
			showAll = true;
			scroll = 0;
			rebuildWidgets();
		}).bounds(cl + (w + 4), fy, w, 16).build()).active = !showAll;
		addRenderableWidget(Button.builder(onOff("loothub.streamer.hud", c.streamerHud), b -> {
			c.streamerHud = !c.streamerHud;
			c.save();
			b.setMessage(onOff("loothub.streamer.hud", c.streamerHud));
		}).bounds(cl + 2 * (w + 4), fy, w, 16).build());
		addRenderableWidget(Button.builder(rowsLabel(c), b -> {
			int next = ROW_CHOICES[0];
			for (int i = 0; i < ROW_CHOICES.length; i++) {
				if (ROW_CHOICES[i] == c.streamerHudRows) {
					next = ROW_CHOICES[(i + 1) % ROW_CHOICES.length];
				}
			}
			c.streamerHudRows = next;
			c.save();
			b.setMessage(rowsLabel(c));
		}).bounds(cl + 3 * (w + 4), fy, w, 16).build());
		Button clear = addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.clear_done"),
				b -> minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.streamer.clear_done"),
						Component.translatable("loothub.streamer.clear_done.confirm"), () -> {
							Redemptions.clearDone();
							scroll = 0;
							rebuildWidgets();
						})))
				.bounds(cl + 4 * (w + 4), fy, w, 16).build());
		clear.active = Redemptions.list().stream().anyMatch(e -> e.done);
		listTop = fy + 22;
	}

	/** Reiter „Einblendungen“: an/aus gesamt, je Art an/aus und „Test“. */
	private void initAlerts(WidgetConfig c, int fy) {
		addRenderableWidget(Button.builder(onOff("loothub.streamer.alerts", c.streamerAlerts), b -> {
			c.streamerAlerts = !c.streamerAlerts;
			c.save();
			Streamer.apply();
			rebuildWidgets();
		}).bounds(cl, fy, 160, 16).build());
		listTop = fy + 22;
		Alerts.Type[] types = Alerts.Type.values();
		int visible = Math.max(1, (cb - listTop) / ALERT_ROW);
		scroll = Math.max(0, Math.min(scroll, types.length - visible));
		for (int i = 0; i < visible && scroll + i < types.length; i++) {
			Alerts.Type t = types[scroll + i];
			int ry = listTop + i * ALERT_ROW;
			boolean on = !c.streamerAlertsOff.contains(t.id);
			Button toggle = addRenderableWidget(Button.builder(Component.translatable(on ? "options.on" : "options.off"), b -> {
				Alerts.setEnabled(t, c.streamerAlertsOff.contains(t.id));
				rebuildWidgets();
			}).bounds(cr - 130, ry + 1, 60, 16).build());
			toggle.active = c.streamerAlerts;
			addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.alert.test"), b -> Alerts.test(t))
					.bounds(cr - 66, ry + 1, 60, 16).build());
		}
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private static Component rowsLabel(WidgetConfig c) {
		return Component.translatable("loothub.streamer.rows", c.streamerHudRows);
	}

	private static Component tab(String key, boolean selected) {
		Component t = Component.translatable(key);
		return selected ? t.copy().withStyle(ChatFormatting.BOLD) : t;
	}

	@Override
	public void tick() {
		super.tick();
		// Anmeldung läuft im Hintergrund – Knöpfe passend zum neuen Stand neu aufbauen
		if (TwitchAuth.status() != shownStatus || Redemptions.status() != shownRedeem) {
			rebuildWidgets();
		}
	}

	private List<Redemptions.Entry> shown() {
		return showAll ? new ArrayList<>(Redemptions.list()) : Redemptions.open();
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		renderAccount(g);
		if (alertsTab) {
			renderAlerts(g);
		} else {
			renderList(g, mouseX, mouseY);
		}
	}

	private void renderAccount(GuiGraphics g) {
		g.fill(cl, boxY, cr, boxY + 44, 0x30000000);
		g.fill(cl, boxY, cl + 2, boxY + 44, PURPLE);
		int tx = cl + 8;
		switch (TwitchAuth.status()) {
			case NO_CLIENT_ID -> {
				g.drawString(font, Component.translatable("loothub.streamer.no_client"), tx, boxY + 8, GOLD, false);
				g.drawString(font, Component.translatable("loothub.streamer.no_client.sub"), tx, boxY + 22, FAINT, false);
			}
			case NOT_CONNECTED, ERROR -> {
				g.drawString(font, Component.translatable("loothub.streamer.not_connected"), tx, boxY + 8, TEXT, false);
				String err = TwitchAuth.error();
				g.drawString(font, err.isBlank() ? Component.translatable("loothub.streamer.not_connected.sub")
						: Component.literal(err), tx, boxY + 22, err.isBlank() ? FAINT : BAD, false);
			}
			case STARTING -> g.drawString(font, Component.translatable("loothub.streamer.starting"), tx, boxY + 8, MUTED, false);
			case WAITING -> {
				g.drawString(font, Component.translatable("loothub.streamer.enter_code"), tx, boxY + 6, MUTED, false);
				g.pose().pushMatrix();
				g.pose().translate(tx, boxY + 19);
				g.pose().scale(2f, 2f);
				g.drawString(font, TwitchAuth.userCode(), 0, 0, PURPLE | 0xFF000000, true);
				g.pose().popMatrix();
			}
			case CONNECTED -> {
				g.drawString(font, Component.translatable("loothub.streamer.connected", TwitchAuth.displayName()), tx, boxY + 8, GREEN, false);
				Component sub = TwitchAuth.needsRelogin()
						? Component.translatable("loothub.streamer.relogin.hint").withStyle(s -> s.withColor(GOLD & 0xFFFFFF))
						: switch (Redemptions.status()) {
					case CONNECTED -> Redemptions.error().isBlank() ? Component.translatable("loothub.streamer.live")
							: Component.literal(Redemptions.error()).withStyle(s -> s.withColor(GOLD & 0xFFFFFF));
					case CONNECTING -> Component.translatable("loothub.streamer.connecting");
					case NOT_ALLOWED, ERROR -> Component.literal(Redemptions.error()).withStyle(s -> s.withColor(BAD & 0xFFFFFF));
					default -> Component.empty();
				};
				g.drawString(font, sub, tx, boxY + 22, FAINT, false);
			}
		}
	}

	private void renderAlerts(GuiGraphics g) {
		Alerts.Type[] types = Alerts.Type.values();
		int visible = Math.max(1, (cb - listTop) / ALERT_ROW);
		for (int i = 0; i < visible && scroll + i < types.length; i++) {
			Alerts.Type t = types[scroll + i];
			int ry = listTop + i * ALERT_ROW;
			g.fill(cl, ry, cr, ry + ALERT_ROW - 2, 0x18FFFFFF);
			g.fill(cl, ry, cl + 2, ry + ALERT_ROW - 2, 0xFF000000 | t.color());
			g.drawString(font, Component.literal(t.icon() + "  ").append(Component.translatable(t.key())), cl + 8, ry + 5,
					0xFF000000 | t.color(), false);
			Component hint = Component.translatable(t.key() + ".hint");
			int hx = cl + 8 + 110;
			if (hx + font.width(hint) < cr - 136) {
				g.drawString(font, hint, hx, ry + 5, FAINT, false);
			}
		}
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		List<Redemptions.Entry> list = shown();
		if (list.isEmpty()) {
			Component m = Component.translatable(showAll ? "loothub.streamer.empty_all" : "loothub.streamer.empty_open");
			g.drawString(font, m, cl + (cw - font.width(m)) / 2, listTop + 20, FAINT, false);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, list.size() - visible));
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i < visible + 1 && scroll + i < list.size(); i++) {
			Redemptions.Entry e = list.get(scroll + i);
			int y = listTop + i * ROW_H;
			boolean hover = mx >= cl && mx < cr && my >= y && my < y + ROW_H - 2 && my < cb;
			g.fill(cl, y, cr, y + ROW_H - 2, hover ? 0x28FFFFFF : 0x18FFFFFF);
			// Kästchen
			int bx = cl + 5, by = y + 5;
			g.fill(bx, by, bx + 10, by + 10, hover ? 0xFFFFFFFF : 0xFF6A5A8A);
			g.fill(bx + 1, by + 1, bx + 9, by + 9, 0xFF0F0A0B);
			if (e.done) {
				g.drawString(font, "✔", bx + 1, by + 1, GREEN, false);
			}
			int tx = cl + 22;
			String when = WHEN.format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()));
			int whenX = cr - 6 - font.width(when);
			int textColor = e.done ? FAINT : TEXT;
			String head = e.user + "  –  " + e.reward + (e.cost > 0 ? "  (" + e.cost + ")" : "");
			g.drawString(font, RedemptionHud.fit(font, head, whenX - 8 - tx), tx, y + 2, textColor, false);
			if (e.input != null && !e.input.isBlank()) {
				g.drawString(font, RedemptionHud.fit(font, "„" + e.input + "“", cr - 6 - tx), tx, y + 11, e.done ? FAINT : MUTED, false);
			}
			g.drawString(font, when, whenX, y + 2, FAINT, false);
			if (e.done) {
				g.fill(tx, y + 6, tx + Math.min(font.width(head), whenX - 8 - tx), y + 7, 0x80FFFFFF);
			}
		}
		g.disableScissor();
		if (list.size() > visible) {
			int trackH = cb - listTop;
			int max = list.size() - visible;
			int barH = Math.max(16, trackH * visible / list.size());
			int barY = listTop + (trackH - barH) * scroll / Math.max(1, max);
			g.fill(cr + 1, listTop, cr + 3, cb, 0x40FFFFFF);
			g.fill(cr + 1, barY, cr + 3, barY + barH, RED);
		}
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (!alertsTab && e.button() == 0 && e.x() >= cl && e.x() < cr && e.y() >= listTop && e.y() < cb) {
			int i = (int) ((e.y() - listTop) / ROW_H);
			List<Redemptions.Entry> list = shown();
			if (scroll + i < list.size() && (e.y() - listTop) % ROW_H < ROW_H - 2) {
				Redemptions.toggle(list.get(scroll + i));
				if (!showAll) {
					scroll = Math.max(0, scroll);
				}
				rebuildWidgets(); // „Erledigte löschen“ an/aus
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical));
			if (alertsTab) {
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
