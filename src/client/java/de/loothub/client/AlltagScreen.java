package de.loothub.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.JobHistory;
import de.loothub.client.jobs.JobHistoryScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.quick.AuctionWatch;
import de.loothub.client.quick.Timers;

/**
 * Seite „Alltag“: eigene Timer (z. B. Tageswechsel, Farm-Erinnerung) und der Job-Vergleich (Geld/h und XP/h je Job
 * aus dem Verlauf).
 */
public class AlltagScreen extends LoothubScreen {
	private static final int GREEN = 0xFF55E06A;
	private static final int GOLD = 0xFFF2C14E;
	private static final int ROW_H = 22;
	private static final int DD_ROW = 13;
	private static boolean jobsTab;

	private EditBox nameBox, valueBox;
	private String mode = "interval";
	private boolean repeat;
	private Button modeButton;
	private boolean modeOpen;
	private boolean soundOpen;
	private Button soundButton;
	private int listTop;
	/** Hinweis „höchstens 5 eingeblendet“ bis zu diesem Zeitpunkt zeigen. */
	private long limitUntil;
	private int scroll;

	private record JobRow(String job, double money, double xp, long ms) {
		double moneyH() {
			return ms <= 0 ? 0 : money * 3_600_000.0 / ms;
		}

		double xpH() {
			return ms <= 0 ? 0 : xp * 3_600_000.0 / ms;
		}
	}

	private List<JobRow> jobRows = List.of();

	public AlltagScreen() {
		super(Page.ALLTAG);
	}

	@Override
	protected void initPage() {
		int tw = 110;
		addRenderableWidget(Button.builder(tab("loothub.alltag.tab.timers", !jobsTab), b -> {
			jobsTab = false;
			rebuildWidgets();
		}).bounds(cl, ct, tw, 16).build());
		addRenderableWidget(Button.builder(tab("loothub.alltag.tab.jobs", jobsTab), b -> {
			jobsTab = true;
			rebuildWidgets();
		}).bounds(cl + tw + 4, ct, tw, 16).build());
		modeOpen = false;
		soundOpen = false;
		soundButton = null;
		if (jobsTab) {
			buildJobs();
			listTop = ct + 40;
			return;
		}
		WidgetConfig c = WidgetConfig.get();
		int y = ct + 24;
		// Kopf: an/aus, Ton, HUD verschieben
		addRenderableWidget(Button.builder(onOff("loothub.alltag.hud", c.timersEnabled), b -> {
			c.timersEnabled = !c.timersEnabled;
			c.save();
			b.setMessage(onOff("loothub.alltag.hud", c.timersEnabled));
		}).bounds(cl, y, 120, 16).build());
		addRenderableWidget(Button.builder(onOff("loothub.alltag.sound", c.timerSound), b -> {
			c.timerSound = !c.timerSound;
			c.save();
			b.setMessage(onOff("loothub.alltag.sound", c.timerSound));
		}).bounds(cl + 124, y, 90, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.alltag.move"),
				b -> minecraft.setScreen(new de.loothub.client.quick.TimerHudEditScreen(this)))
				.bounds(cl + 218, y, 90, 16).build());
		soundButton = addRenderableWidget(Button.builder(soundLabel(), b -> soundOpen = !soundOpen)
				.bounds(cl + 312, y, Math.max(70, Math.min(120, cr - cl - 312)), 16).build());

		// Neuer Timer: Name, Art (Liste), Minuten bzw. Uhrzeit, Wiederholen, Hinzufügen
		y += 24;
		String oldName = nameBox != null ? nameBox.getValue() : "";
		String oldValue = valueBox != null ? valueBox.getValue() : "30";
		nameBox = addRenderableWidget(new EditBox(font, cl, y, 100, 16, Component.translatable("loothub.alltag.name")));
		nameBox.setMaxLength(24);
		nameBox.setHint(Component.translatable("loothub.alltag.name"));
		nameBox.setValue(oldName);
		modeButton = addRenderableWidget(Button.builder(modeLabel(), b -> modeOpen = !modeOpen)
				.bounds(cl + 104, y, 96, 16).build());
		valueBox = addRenderableWidget(new EditBox(font, cl + 204, y, 46, 16, Component.empty()));
		valueBox.setMaxLength(5);
		valueBox.setValue(oldValue);
		valueBox.setHint(Component.literal("daily".equals(mode) ? "HH:mm" : "Min."));
		addRenderableWidget(Button.builder(onOff("loothub.alltag.repeat", repeat), b -> {
			repeat = !repeat;
			b.setMessage(onOff("loothub.alltag.repeat", repeat));
		}).bounds(cl + 254, y, 90, 16).build()).active = !"daily".equals(mode);
		addRenderableWidget(Button.builder(Component.translatable("loothub.alltag.add"), b -> add())
				.bounds(cl + 348, y, Math.max(40, Math.min(90, cr - cl - 348)), 16).build());

		// Vorlagen
		y += 20;
		addRenderableWidget(Button.builder(Component.translatable("loothub.alltag.preset.reset"), b -> {
			Timers.Timer t = new Timers.Timer();
			t.name = Component.translatable("loothub.alltag.preset.reset_name").getString();
			t.mode = "daily";
			t.time = "00:00";
			addTimer(t);
		}).bounds(cl, y, 150, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.alltag.preset.hour"), b -> {
			Timers.Timer t = new Timers.Timer();
			t.name = Component.translatable("loothub.alltag.preset.hour_name").getString();
			t.minutes = 60;
			t.repeat = true;
			addTimer(t);
		}).bounds(cl + 154, y, 150, 16).build());

		listTop = y + 26;
		List<Timers.Timer> list = Timers.list();
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visible)));
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			Timers.Timer t = list.get(i + scroll);
			int ry = listTop + i * ROW_H;
			// Haken: in der Anzeige ein-/ausblenden (höchstens 5 gleichzeitig)
			addRenderableWidget(Button.builder(Component.literal(t.shown ? "✔" : ""), b -> {
				if (!Timers.setShown(t, !t.shown)) {
					limitUntil = System.currentTimeMillis() + 3_000L;
					return;
				}
				WidgetConfig.get().save();
				b.setMessage(Component.literal(t.shown ? "✔" : ""));
			}).bounds(cl + 5, ry + 2, 16, 16)
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.alltag.shown.tip",
							Timers.MAX_SHOWN))).build());
			if (t.isAuction()) {
				addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
					AuctionWatch.forget(t.auction);
					Timers.list().remove(t);
					WidgetConfig.get().save();
					rebuildWidgets();
				}).bounds(cr - 20, ry + 2, 18, 16).build());
				continue;
			}
			addRenderableWidget(Button.builder(Component.translatable(t.enabled ? "loothub.alltag.stop" : "loothub.alltag.start"), b -> {
				if (t.enabled) {
					t.enabled = false;
					t.nextAt = 0;
				} else {
					Timers.start(t);
				}
				WidgetConfig.get().save();
				rebuildWidgets();
			}).bounds(cr - 82, ry + 2, 58, 16).build());
			addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
				Timers.list().remove(t);
				WidgetConfig.get().save();
				rebuildWidgets();
			}).bounds(cr - 20, ry + 2, 18, 16).build());
		}
	}

	private Component tab(String key, boolean selected) {
		Component t = Component.translatable(key);
		return selected ? Component.literal("▶ ").append(t) : t;
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private static Component soundLabel() {
		return Component.translatable("loothub.settings.timer_sound.pick")
				.append(Component.translatable("loothub.sound.timer." + WidgetConfig.get().timerSoundType)).append(" ▾");
	}

	private Component modeLabel() {
		return Component.translatable("loothub.alltag.mode." + mode).append(" ▾");
	}

	private void add() {
		Timers.Timer t = new Timers.Timer();
		String name = nameBox.getValue().strip();
		t.name = name.isEmpty() ? Component.translatable("loothub.alltag.default_name").getString() : name;
		t.mode = mode;
		String v = valueBox.getValue().strip();
		if ("daily".equals(mode)) {
			t.time = String.format("%02d:%02d", Timers.parseTime(v).getHour(), Timers.parseTime(v).getMinute());
		} else {
			try {
				t.minutes = Math.max(1, Math.min(7 * 24 * 60, Integer.parseInt(v)));
			} catch (NumberFormatException e) {
				valueBox.setTextColor(0xFFFF5555);
				return;
			}
			t.repeat = repeat;
		}
		nameBox.setValue("");
		addTimer(t);
	}

	private void addTimer(Timers.Timer t) {
		Timers.start(t);
		Timers.list().add(t);
		WidgetConfig c = WidgetConfig.get();
		c.timersEnabled = true;
		c.save();
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ Job-Vergleich

	private void buildJobs() {
		Map<String, double[]> sums = new LinkedHashMap<>();
		for (JobHistory.Session s : JobHistory.all()) {
			for (JobHistory.JobEntry e : s.jobs) {
				if (e.job == null) {
					continue;
				}
				double[] a = sums.computeIfAbsent(JobHistoryScreen.jobName(e.job), k -> new double[3]);
				a[0] += e.money;
				a[1] += e.xp;
				a[2] += e.activeMs;
			}
		}
		List<JobRow> rows = new ArrayList<>();
		sums.forEach((job, a) -> {
			if (a[2] >= 60_000) { // weniger als eine Minute sagt nichts aus
				rows.add(new JobRow(job, a[0], a[1], (long) a[2]));
			}
		});
		rows.sort((x, y) -> Double.compare(y.moneyH(), x.moneyH()));
		jobRows = rows;
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (jobsTab) {
			renderJobs(g);
			return;
		}
		List<Timers.Timer> list = Timers.list();
		if (list.isEmpty()) {
			Component m = Component.translatable("loothub.alltag.empty");
			g.drawString(font, m, cl + (cw - font.width(m)) / 2, listTop + 12, FAINT, false);
		}
		long now = System.currentTimeMillis();
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			Timers.Timer t = list.get(i + scroll);
			int ry = listTop + i * ROW_H;
			g.fill(cl, ry, cr, ry + ROW_H - 2, 0x18FFFFFF);
			g.fill(cl, ry, cl + 2, ry + ROW_H - 2, t.isAuction() ? GOLD : t.enabled ? GREEN : FAINT);
			int nx = cl + 26;
			String title = font.plainSubstrByWidth(t.name, Math.max(40, cr - 150 - nx));
			g.drawString(font, title, nx, ry + 2, TEXT, false);
			if (t.isAuction()) {
				String info = Component.translatable("loothub.alltag.info.auction").getString()
						+ (t.seller == null || t.seller.isEmpty() ? "" : " · " + t.seller)
						+ (t.price == null || t.price.isEmpty() ? "" : " · " + t.price);
				g.drawString(font, font.plainSubstrByWidth(info, Math.max(40, cr - 150 - nx)), nx, ry + 11, FAINT, false);
				long l = t.nextAt - now;
				String left = l <= 0 ? Component.translatable("loothub.alltag.auction.ended").getString()
						: (t.approx ? "≥ " : "") + Timers.format(l);
				g.drawString(font, left, cr - 28 - font.width(left), ry + 6, l <= 0 ? FAINT : GOLD, false);
				continue;
			}
			String info = t.daily() ? Component.translatable("loothub.alltag.info.daily", t.time).getString()
					: Component.translatable(t.repeat ? "loothub.alltag.info.repeat" : "loothub.alltag.info.once", t.minutes).getString();
			g.drawString(font, info, nx, ry + 11, FAINT, false);
			String left = t.enabled && t.nextAt > 0 ? Timers.format(t.nextAt - now) : "–";
			g.drawString(font, left, cr - 90 - font.width(left), ry + 6, t.enabled ? GOLD : FAINT, false);
		}
		if (System.currentTimeMillis() < limitUntil) {
			Component m = Component.translatable("loothub.alltag.shown.limit", Timers.MAX_SHOWN);
			g.drawString(font, m, cr - font.width(m), listTop - 11, 0xFFFF6B6B, false);
		} else {
			Component m = Component.translatable("loothub.alltag.shown.count", Timers.shownCount(), Timers.MAX_SHOWN);
			g.drawString(font, m, cr - font.width(m), listTop - 11, FAINT, false);
		}
		if (soundOpen && soundButton != null) {
			g.nextStratum();
			int x = soundButton.getX(), y = soundButton.getY() + 17, w = soundButton.getWidth();
			String[] opts = de.loothub.client.Sounds.TIMER_SOUNDS;
			g.fill(x - 1, y - 1, x + w + 1, y + opts.length * DD_ROW + 1, RED);
			g.fill(x, y, x + w, y + opts.length * DD_ROW, 0xF0140B0D);
			for (int i = 0; i < opts.length; i++) {
				int ry = y + i * DD_ROW;
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + DD_ROW;
				if (hover) {
					g.fill(x, ry, x + w, ry + DD_ROW, 0x40E23B3B);
				}
				boolean sel = opts[i].equals(WidgetConfig.get().timerSoundType);
				g.drawString(font, (sel ? "✔ " : "   ") + Component.translatable("loothub.sound.timer." + opts[i]).getString(),
						x + 4, ry + 3, sel ? TEXT : MUTED, false);
			}
		}
		if (modeOpen) {
			g.nextStratum();
			int x = modeButton.getX(), y = modeButton.getY() + 17, w = modeButton.getWidth();
			String[] opts = {"interval", "daily"};
			g.fill(x - 1, y - 1, x + w + 1, y + opts.length * DD_ROW + 1, RED);
			g.fill(x, y, x + w, y + opts.length * DD_ROW, 0xF0140B0D);
			for (int i = 0; i < opts.length; i++) {
				int ry = y + i * DD_ROW;
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + DD_ROW;
				if (hover) {
					g.fill(x, ry, x + w, ry + DD_ROW, 0x40E23B3B);
				}
				boolean sel = opts[i].equals(mode);
				g.drawString(font, (sel ? "✔ " : "   ") + Component.translatable("loothub.alltag.mode." + opts[i]).getString(),
						x + 4, ry + 3, sel ? TEXT : MUTED, false);
			}
		}
	}

	private void renderJobs(GuiGraphics g) {
		int y = ct + 24;
		g.drawString(font, Component.translatable("loothub.alltag.jobs.hint"), cl, y, FAINT, false);
		if (jobRows.isEmpty()) {
			Component m = Component.translatable("loothub.alltag.jobs.empty");
			g.drawString(font, m, cl + (cw - font.width(m)) / 2, listTop + 12, FAINT, false);
			return;
		}
		int cJob = cl + 22, cMoney = cl + Math.max(130, cw * 40 / 100), cXp = cl + Math.max(220, cw * 62 / 100), cTime = cl + Math.max(300, cw * 82 / 100);
		g.drawString(font, Component.translatable("loothub.history.col.job"), cJob, listTop - 12, FAINT, false);
		g.drawString(font, Component.translatable("loothub.alltag.jobs.money_h"), cMoney, listTop - 12, FAINT, false);
		g.drawString(font, Component.translatable("loothub.alltag.jobs.xp_h"), cXp, listTop - 12, FAINT, false);
		g.drawString(font, Component.translatable("loothub.alltag.jobs.time"), cTime, listTop - 12, FAINT, false);
		double best = jobRows.get(0).moneyH();
		double bestXp = 0;
		for (JobRow r : jobRows) {
			bestXp = Math.max(bestXp, r.xpH());
		}
		int visible = Math.max(1, (cb - listTop) / 20);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, jobRows.size() - visible)));
		for (int i = 0; i < visible && i + scroll < jobRows.size(); i++) {
			JobRow r = jobRows.get(i + scroll);
			int ry = listTop + i * 20;
			g.fill(cl, ry, cr, ry + 18, 0x18FFFFFF);
			int color = JobHistoryScreen.jobColor(r.job());
			g.fill(cl, ry, cl + 2, ry + 18, color);
			g.renderItem(new ItemStack(JobHistoryScreen.jobIcon(r.job())), cl + 4, ry + 1);
			g.drawString(font, r.job(), cJob, ry + 5, color, false);
			// Balken zeigen den Abstand zum besten Job
			int barW = Math.max(1, cXp - cMoney - 10);
			g.fill(cMoney, ry + 14, cMoney + (int) (barW * (best <= 0 ? 0 : r.moneyH() / best)), ry + 16, GREEN);
			g.drawString(font, Fmt.money(r.moneyH()), cMoney, ry + 3, i + scroll == 0 ? GREEN : TEXT, false);
			int barX = Math.max(1, cTime - cXp - 10);
			g.fill(cXp, ry + 14, cXp + (int) (barX * (bestXp <= 0 ? 0 : r.xpH() / bestXp)), ry + 16, GOLD);
			g.drawString(font, Fmt.compact(r.xpH()), cXp, ry + 3, r.xpH() >= bestXp ? GOLD : TEXT, false);
			g.drawString(font, Fmt.duration(r.ms()), cTime, ry + 5, MUTED, false);
		}
	}

	private int lastState = -1;

	@Override
	public void tick() {
		super.tick();
		// Läuft ein Timer ab, während die Seite offen ist, die Knöpfe (Start/Stopp) neu beschriften
		int state = 0;
		for (Timers.Timer t : Timers.list()) {
			state = state * 31 + (t.enabled ? 1 : 0) + (t.shown ? 2 : 0) + (t.auction != null ? 4 : 0);
		}
		state = state * 31 + Timers.list().size();
		if (!jobsTab && lastState != -1 && state != lastState) {
			rebuildWidgets();
		}
		lastState = state;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (soundOpen && soundButton != null) {
			int x = soundButton.getX(), y = soundButton.getY() + 17, w = soundButton.getWidth();
			String[] opts = de.loothub.client.Sounds.TIMER_SOUNDS;
			soundOpen = false;
			if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + opts.length * DD_ROW) {
				WidgetConfig c = WidgetConfig.get();
				c.timerSoundType = opts[(int) ((event.y() - y) / DD_ROW)];
				c.save();
				soundButton.setMessage(soundLabel());
				de.loothub.client.Sounds.timer(c.timerSoundType); // gleich anhören
				return true;
			}
			if (soundButton.isMouseOver(event.x(), event.y())) {
				return true;
			}
		}
		if (modeOpen) {
			int x = modeButton.getX(), y = modeButton.getY() + 17, w = modeButton.getWidth();
			String[] opts = {"interval", "daily"};
			modeOpen = false;
			if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + opts.length * DD_ROW) {
				mode = opts[(int) ((event.y() - y) / DD_ROW)];
				if (valueBox != null) {
					valueBox.setValue("daily".equals(mode) ? "00:00" : "30");
				}
				rebuildWidgets();
				return true;
			}
			if (modeButton.isMouseOver(event.x(), event.y())) {
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical));
			if (!jobsTab) {
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
