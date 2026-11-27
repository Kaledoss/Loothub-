package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.ConfirmScreen;
import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/** Menüseite „Quests“: Reiter Quests, Erfolge, Rangliste und Shop. */
public class ProgressScreen extends LoothubScreen {
	private enum Tab {
		QUESTS, ACH, BOARD, GAMES, SHOP, ADMIN
	}

	private record Hit(int x, int y, int w, int h, Runnable action, Component tip) {
		boolean over(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static Tab tab = Tab.QUESTS;
	private static String board = "points";
	/** Zeitraum der Rangliste: d = heute, w = Woche, m = Monat, a = gesamt. */
	private static String period = "w";
	private static final String[] PERIODS = {"d", "w", "m", "a"};
	private final List<Hit> hits = new ArrayList<>();
	private final List<Button> tabButtons = new ArrayList<>();
	private Button boardButton;
	private Button periodButton;
	/** Offene Auswahlliste: 0 keine, 1 Rangliste, 2 Zeitraum. */
	private int open;
	private int scroll;
	/** Unterreiter der Erfolge: feats = Heldentaten, b = Blöcke, m = Mobs. */
	private static String achSub = "feats";
	private static String searchText = "";
	private net.minecraft.client.gui.components.EditBox search;
	/** Aufgeklappte Erfolge (zeigen alle Stufen). */
	private final Set<String> expanded = new HashSet<>();
	private int listTop;
	private int contentH;
	private String message = "";

	public ProgressScreen() {
		super(Page.QUESTS);
	}

	@Override
	protected void initPage() {
		tabButtons.clear();
		WidgetConfig c = WidgetConfig.get();
		if (!c.progress) {
			addRenderableWidget(Button.builder(Component.translatable("loothub.lh.enable"), b -> {
				c.progress = true;
				c.save();
				Progress.syncSoon();
				rebuildWidgets();
			}).bounds(cl + (cw - 160) / 2, ct + 50, 160, 20).build());
			listTop = ct;
			return;
		}
		Progress.syncSoon();
		int x = cl;
		if (tab == Tab.ADMIN && !Progress.isAdmin()) {
			tab = Tab.QUESTS;
		}
		for (Tab t : Tab.values()) {
			if (t == Tab.ADMIN && !Progress.isAdmin()) {
				continue;
			}
			Component label = Component.translatable("loothub.lh.tab." + t.name().toLowerCase(java.util.Locale.ROOT));
			int w = font.width(label) + 16;
			Button b = addRenderableWidget(Button.builder(label, btn -> {
				tab = t;
				scroll = 0;
				open = 0;
				rebuildWidgets();
			}).bounds(x, ct, w, 16).build());
			b.active = tab != t;
			tabButtons.add(b);
			x += w + 4;
		}
		if (tab == Tab.ADMIN) {
			initAdmin();
			listTop = ct + 24;
			return;
		}
		search = null;
		if (tab == Tab.ACH) {
			int bx = cr;
			String[] subs = {"m", "b", "feats"};
			for (String sub : subs) {
				Component label = Component.translatable("loothub.lh.ach.sub." + switch (sub) {
					case "b" -> "blocks";
					case "m" -> "mobs";
					default -> "feats";
				});
				int w = font.width(label) + 14;
				bx -= w;
				Button b = addRenderableWidget(Button.builder(label, btn -> {
					achSub = sub;
					scroll = 0;
					rebuildWidgets();
				}).bounds(bx, ct, w, 16).build());
				b.active = !achSub.equals(sub);
				bx -= 4;
			}
			if (!achSub.equals("feats")) {
				search = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, cr - 160, ct + 20, 160, 14,
						Component.translatable("loothub.lh.ach.search")));
				search.setHint(Component.translatable("loothub.lh.ach.search").withStyle(ChatFormatting.DARK_GRAY));
				search.setMaxLength(40);
				search.setValue(searchText);
				search.setResponder(t -> {
					searchText = t;
					scroll = 0;
				});
				boardButton = null;
				periodButton = null;
				listTop = statsWarning(ct + 40);
				return;
			}
		}
		if (tab == Tab.BOARD) {
			periodButton = addRenderableWidget(Button.builder(periodLabel(), b -> open = open == 2 ? 0 : 2)
					.bounds(cr - 100, ct, 100, 16).build());
			boardButton = addRenderableWidget(Button.builder(boardLabel(), b -> open = open == 1 ? 0 : 1)
					.bounds(cr - 100 - 4 - 190, ct, 190, 16).build());
		} else {
			boardButton = null;
			periodButton = null;
		}
		listTop = statsWarning(ct + 24);
	}

	/** Statistik aus: Hinweis mit Knopf über der Liste (Aufgaben und Erfolge zählen dann nicht). */
	private int statsWarning(int top) {
		statsWarnY = -1;
		if (WidgetConfig.get().statsEnabled) {
			return top;
		}
		statsWarnY = top;
		Component label = Component.translatable("loothub.lh.stats_off.button");
		int w = font.width(label) + 16;
		addRenderableWidget(Button.builder(label, b -> {
			WidgetConfig c = WidgetConfig.get();
			c.statsEnabled = true;
			c.save();
			Progress.syncSoon();
			rebuildWidgets();
		}).bounds(cr - w - 4, top + 2, w, 16).build());
		return top + 24;
	}

	private int statsWarnY = -1;

	// ------------------------------------------------------------------ Admin

	private net.minecraft.client.gui.components.EditBox talerName, talerAmount, resetName;
	private int adminY;

	private void initAdmin() {
		adminY = ct + 24;
		int y = adminY + 14;
		talerName = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, cl, y, 130, 16,
				Component.translatable("loothub.lh.admin.player")));
		talerName.setHint(Component.translatable("loothub.lh.admin.player").withStyle(ChatFormatting.DARK_GRAY));
		talerName.setMaxLength(16);
		talerAmount = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, cl + 136, y, 80, 16,
				Component.translatable("loothub.lh.admin.amount")));
		talerAmount.setHint(Component.translatable("loothub.lh.admin.amount").withStyle(ChatFormatting.DARK_GRAY));
		talerAmount.setFilter(t -> t.matches("\\d{0,9}"));
		int bx = cl + 222;
		for (String mode : new String[]{"add", "remove", "set"}) {
			Component label = Component.translatable("loothub.lh.admin." + mode);
			int w = font.width(label) + 14;
			addRenderableWidget(Button.builder(label, b -> adminTaler(mode)).bounds(bx, y, w, 16).build());
			bx += w + 4;
		}
		int ry = adminY + 70;
		boardButton = addRenderableWidget(Button.builder(boardLabel(), b -> open = open == 1 ? 0 : 1).bounds(cl, ry, 190, 16).build());
		periodButton = addRenderableWidget(Button.builder(periodLabel(), b -> open = open == 2 ? 0 : 2).bounds(cl + 194, ry, 100, 16).build());
		resetName = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, cl + 298, ry, 120, 16,
				Component.translatable("loothub.lh.admin.reset.player")));
		resetName.setHint(Component.translatable("loothub.lh.admin.reset.player").withStyle(ChatFormatting.DARK_GRAY));
		resetName.setMaxLength(16);
		Component rl = Component.translatable("loothub.lh.admin.reset");
		addRenderableWidget(Button.builder(rl, b -> adminReset()).bounds(cl + 422, ry, font.width(rl) + 14, 16).build());
	}

	private void adminTaler(String mode) {
		String name = talerName.getValue().strip();
		String amount = talerAmount.getValue().strip();
		if (name.isEmpty() || amount.isEmpty()) {
			message = Component.translatable("loothub.lh.admin.missing").getString();
			return;
		}
		long n = Long.parseLong(amount);
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_taler");
		p.addProperty("target", name);
		p.addProperty("mode", mode.equals("set") ? "set" : "add");
		p.addProperty("amount", mode.equals("remove") ? -n : n);
		message = "";
		Progress.admin(p, res -> message = res == null ? Component.translatable("loothub.lh.error.offline").getString()
				: res.has("ok") ? Component.translatable("loothub.lh.admin.taler.done", res.get("name").getAsString(),
						Fmt.whole(res.get("balance").getAsLong())).getString()
				: Component.translatable("loothub.lh.admin.error." + res.get("error").getAsString()).getString());
	}

	private void adminReset() {
		String name = resetName.getValue().strip();
		Component what = Component.translatable("loothub.lh.board." + board).append(" · ").append(Component.translatable("loothub.lh.period." + period));
		Component who = name.isEmpty() ? Component.translatable("loothub.lh.admin.reset.all") : Component.literal(name);
		minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.lh.admin.reset"),
				Component.translatable("loothub.lh.admin.reset.confirm", what, who), () -> {
					JsonObject p = new JsonObject();
					p.addProperty("op", "admin_reset");
					p.addProperty("board", board);
					p.addProperty("period", period);
					if (!name.isEmpty()) {
						p.addProperty("target", name);
					}
					message = "";
					Progress.admin(p, res -> message = res == null ? Component.translatable("loothub.lh.error.offline").getString()
							: res.has("ok") ? Component.translatable("loothub.lh.admin.reset.done").getString()
							: Component.translatable("loothub.lh.admin.error." + res.get("error").getAsString()).getString());
					minecraft.setScreen(this);
				}));
	}

	private int drawAdmin(GuiGraphics g, int y) {
		y = section(g, Component.translatable("loothub.lh.admin.taler"), null, y);
		y += 22;
		g.drawString(font, Component.translatable("loothub.lh.admin.taler.hint"), cl, y, FAINT, false);
		y += 14;
		y = section(g, Component.translatable("loothub.lh.admin.reset.title"), null, y);
		y += 22;
		g.drawString(font, Component.translatable("loothub.lh.admin.reset.hint"), cl, y, FAINT, false);
		return y + 14;
	}

	private Component boardLabel() {
		return Component.translatable("loothub.lh.board." + board).append(" ▾");
	}

	private Component periodLabel() {
		return Component.translatable("loothub.lh.period." + period).append(" ▾");
	}

	/** Einträge der offenen Auswahlliste. */
	private List<String> options(JsonObject s) {
		List<String> out = new ArrayList<>();
		if (open == 1) {
			for (JsonElement e : s.getAsJsonObject("cat").getAsJsonArray("boards")) {
				out.add(e.getAsString());
			}
		} else if (open == 2) {
			out.addAll(List.of(PERIODS));
		}
		return out;
	}

	private Button openButton() {
		return open == 1 ? boardButton : open == 2 ? periodButton : null;
	}

	@Override
	protected Component headerRight() {
		JsonObject s = Progress.state();
		if (s == null || !WidgetConfig.get().progress) {
			return null;
		}
		return Component.translatable("loothub.lh.balance", Fmt.whole(s.get("balance").getAsLong()))
				.withStyle(st -> st.withColor(0xF2C14E));
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		hoverX = mouseX;
		hoverY = mouseY;
		hits.clear();
		if (!WidgetConfig.get().progress) {
			centered(g, Component.translatable("loothub.lh.off"), ct + 20, MUTED);
			centered(g, Component.translatable("loothub.lh.off.hint"), ct + 32, FAINT);
			return;
		}
		JsonObject s = Progress.state();
		if (s == null) {
			String st = Progress.status();
			centered(g, Component.translatable(st.isEmpty() ? "loothub.lh.loading" : "loothub.lh.status." + st), listTop + 20,
					st.isEmpty() ? MUTED : RED);
			return;
		}
		if (statsWarnY >= 0) {
			g.fill(cl, statsWarnY, cr, statsWarnY + 20, 0x40FF5555);
			g.fill(cl, statsWarnY, cl + 2, statsWarnY + 20, 0xFFFF5555);
			g.drawString(font, font.plainSubstrByWidth(Component.translatable("loothub.lh.stats_off").getString(), cw - 140),
					cl + 8, statsWarnY + 6, 0xFFFFB0B0, false);
		}
		int y = listTop - scroll;
		g.enableScissor(cl, listTop, cr, cb);
		int start = y;
		y = switch (tab) {
			case QUESTS -> drawQuests(g, s, y);
			case ACH -> drawAch(g, s, y);
			case BOARD -> drawBoard(g, s, y);
			case GAMES -> drawGames(g, s, y);
			case SHOP -> drawShop(g, s, y);
			case ADMIN -> drawAdmin(g, listTop);
		};
		g.disableScissor();
		contentH = y - start;
		if (!message.isEmpty()) {
			Component m = Component.literal(message);
			g.drawString(font, m, cr - font.width(m), ct + 4, RED, false);
		}
		if (open != 0 && openButton() != null) {
			drawDropdown(g, s, mouseX, mouseY);
			return;
		}
		for (Hit h : hits) {
			if (h.tip() != null && h.over(mouseX, mouseY) && mouseY >= listTop && mouseY < cb) {
				g.setTooltipForNextFrame(font, font.split(h.tip(), 220), mouseX, mouseY);
				break;
			}
		}
	}

	private void centered(GuiGraphics g, Component c, int y, int color) {
		g.drawString(font, c, cl + (cw - font.width(c)) / 2, y, color, false);
	}

	private int section(GuiGraphics g, Component title, Component right, int y) {
		g.drawString(font, title, cl, y + 2, RED, false);
		if (right != null) {
			g.drawString(font, right, cr - font.width(right), y + 2, FAINT, false);
		}
		g.fill(cl, y + 12, cr, y + 13, FRAME_EDGE);
		return y + 17;
	}

	private void bar(GuiGraphics g, int x, int y, int w, double f, int color) {
		f = Math.max(0, Math.min(1, f));
		g.fill(x, y, x + w, y + 3, 0xFF2A1B1E);
		g.fill(x, y, x + (int) Math.round(w * f), y + 3, color);
	}

	private static final int[] DIFF = {0xFF55E06A, 0xFFFFD166, 0xFFFF6B6B};

	private int quests(GuiGraphics g, JsonArray list, int y, String kind) {
		for (JsonElement e : list) {
			JsonObject q = e.getAsJsonObject();
			String pinKey = Progress.pinKey(kind, q);
			String stat = q.get("stat").getAsString();
			long goal = q.get("goal").getAsLong();
			long have = q.get("done").getAsBoolean() ? goal : Math.min(goal, Progress.liveHave(stat, q.get("have").getAsLong()));
			boolean done = q.get("done").getAsBoolean();
			int diff = q.get("diff").getAsInt();
			g.fill(cl, y + 1, cl + 3, y + 17, DIFF[Math.max(0, Math.min(2, diff))]);
			Component text = Texts.goal(stat, goal);
			g.drawString(font, text, cl + 8, y + 2, done ? FAINT : TEXT, false);
			// Wo zählt das? – Hinweis beim Darüberfahren (kleines ⓘ hinter dem Text)
			int tw = font.width(text);
			g.drawString(font, "ⓘ", cl + 12 + tw, y + 2, FAINT, false);
			hits.add(new Hit(cl + 8, y, tw + 14, 12, null, Texts.where(stat)));
			String right = done ? "✔ " + Component.translatable("loothub.lh.toast.points", q.get("points").getAsInt()).getString()
					: Component.translatable("loothub.lh.toast.points", q.get("points").getAsInt()).getString();
			g.drawString(font, right, cr - font.width(right), y + 2, done ? 0xFF55E06A : 0xFFF2C14E, false);
			// Anpinnen (Fähnchen links neben den Talern)
			boolean pin = Progress.pinned(pinKey);
			int px = cr - font.width(right) - 14;
			g.drawString(font, "⚑", px, y + 2, pin ? 0xFFF2C14E : 0xFF6A5A5C, false);
			hits.add(new Hit(px - 2, y, 12, 12, () -> {
				if (!Progress.togglePin(pinKey)) {
					message = Component.translatable("loothub.lh.pin.max", Progress.MAX_PINS).getString();
				} else {
					message = "";
				}
			}, Component.translatable(pin ? "loothub.lh.pin.off" : "loothub.lh.pin.on")));
			String prog = Texts.value(stat, have) + " / " + Texts.value(stat, goal);
			int barW = Math.max(40, cw - 8 - font.width(prog) - 12);
			bar(g, cl + 8, y + 13, barW, goal == 0 ? 1 : have / (double) goal, done ? 0xFF55E06A : RED);
			g.drawString(font, prog, cl + 8 + barW + 8, y + 10, MUTED, false);
			y += 24;
		}
		return y;
	}

	private int drawQuests(GuiGraphics g, JsonObject s, int y) {
		long now = System.currentTimeMillis();
		JsonObject slot = s.getAsJsonObject("slot");
		y = section(g, Component.translatable("loothub.lh.q.slot"),
				Component.translatable("loothub.lh.q.new_in", Texts.left(slot.get("ends").getAsLong() - now)), y);
		y = quests(g, slot.getAsJsonArray("list"), y, "s") + 6;

		JsonObject week = s.getAsJsonObject("week");
		y = section(g, Component.translatable("loothub.lh.q.week"),
				Component.translatable("loothub.lh.q.ends_in", Texts.left(week.get("ends").getAsLong() - now)), y);
		y = quests(g, week.getAsJsonArray("list"), y, "w") + 6;

		// Duo-Quest der Woche: zwei Spieler tragen sich gegenseitig ein und schaffen ein Ziel zusammen
		if (s.has("duo") && s.get("duo").isJsonObject()) {
			JsonObject duo = s.getAsJsonObject("duo");
			String dst = duo.get("stat").getAsString();
			long goal = duo.get("goal").getAsLong();
			String partner = duo.get("partner").getAsString();
			boolean paired = duo.get("paired").getAsBoolean(), paid = duo.get("paid").getAsBoolean();
			y = section(g, Component.translatable("loothub.lh.duo.title"),
					Component.translatable("loothub.lh.toast.points", duo.get("reward").getAsInt()), y);
			g.fill(cl, y + 1, cl + 3, y + 17, 0xFF6BD5FF);
			g.drawString(font, Component.translatable("loothub.lh.duo.goal", Texts.goal(dst, goal)), cl + 8, y + 2, paid ? FAINT : TEXT, false);
			Component btn = Component.translatable(partner.isEmpty() ? "loothub.lh.duo.pick" : "loothub.lh.duo.change");
			if (!paid) {
				int bw = font.width(btn) + 14;
				gameButton(g, cr - bw, y, bw, btn, () -> minecraft.setScreen(new DuoScreen(this, partner)));
			}
			// Einladungen: andere haben mich eingetragen – mit einem Klick annehmen
			if (!paired && !paid && duo.has("invites")) {
				for (var inv : duo.getAsJsonArray("invites")) {
					String who = inv.getAsString();
					if (who.equalsIgnoreCase(partner)) {
						continue;
					}
					y += 14;
					g.drawString(font, Component.translatable("loothub.lh.duo.invite", who), cl + 8, y + 2, 0xFF6BD5FF, false);
					Component acc = Component.translatable("loothub.lh.duo.accept");
					int aw = font.width(acc) + 14;
					gameButton(g, cr - aw, y - 2, aw, acc, () -> Progress.duo(who, err -> minecraft.execute(this::rebuildWidgets)));
				}
			}
			if (partner.isEmpty()) {
				g.drawString(font, Component.translatable("loothub.lh.duo.none"), cl + 8, y + 12, MUTED, false);
				y += 26;
			} else if (!paired) {
				g.drawString(font, Component.translatable("loothub.lh.duo.waiting", partner), cl + 8, y + 12, MUTED, false);
				y += 26;
			} else {
				long mine = duo.get("mine").getAsLong(), theirs = duo.get("theirs").getAsLong();
				long have = paid ? goal : Math.min(goal, mine + theirs);
				String prog = Texts.value(dst, have) + " / " + Texts.value(dst, goal);
				int barW = Math.max(40, cw - 8 - font.width(prog) - 12);
				bar(g, cl + 8, y + 15, barW, goal == 0 ? 1 : have / (double) goal, paid ? 0xFF55E06A : 0xFF6BD5FF);
				g.drawString(font, prog, cl + 8 + barW + 8, y + 12, MUTED, false);
				g.drawString(font, Component.translatable("loothub.lh.duo.split", Texts.value(dst, mine), partner, Texts.value(dst, theirs)),
						cl + 8, y + 21, paid ? 0xFF55E06A : FAINT, false);
				y += 34;
			}
			y += 6;
		}

		// Questkette der Woche: kleine Geschichte in drei Schritten
		if (s.has("chain") && s.get("chain").isJsonObject()) {
			JsonObject ch = s.getAsJsonObject("chain");
			String cid = ch.get("id").getAsString();
			int step = ch.get("step").getAsInt();
			JsonArray steps = ch.getAsJsonArray("steps");
			y = section(g, Component.translatable("loothub.lh.chain.title", Component.translatable("loothub.lh.chain." + cid)),
					Component.translatable("loothub.lh.chain.step", Math.min(step + 1, steps.size()), steps.size()), y);
			if (step >= steps.size()) {
				g.drawString(font, Component.translatable("loothub.lh.chain." + cid + ".end"), cl + 8, y + 2, 0xFF55E06A, false);
				y += 16;
			} else {
				JsonArray cur = steps.get(step).getAsJsonArray();
				String st = cur.get(0).getAsString();
				long goal = cur.get(1).getAsLong(), have = ch.get("have").getAsLong();
				// Erzähltext des Schritts, darunter Ziel und Fortschritt
				g.drawString(font, Component.translatable("loothub.lh.chain." + cid + "." + (step + 1)).withStyle(ChatFormatting.ITALIC),
						cl + 8, y + 2, 0xFFE8D8DA, false);
				y += 13;
				String reward = Component.translatable("loothub.lh.toast.points", cur.get(2).getAsInt()).getString();
				g.drawString(font, Texts.goal(st, goal), cl + 8, y + 2, TEXT, false);
				g.drawString(font, reward, cr - font.width(reward), y + 2, 0xFFF2C14E, false);
				pinFlag(g, "k|" + cid, cr - font.width(reward) - 14, y + 2);
				String prog = Texts.value(st, Math.min(have, goal)) + " / " + Texts.value(st, goal);
				int barW = Math.max(40, cw - 8 - font.width(prog) - 12);
				bar(g, cl + 8, y + 13, barW, goal == 0 ? 1 : have / (double) goal, 0xFFB07CFF);
				g.drawString(font, prog, cl + 8 + barW + 8, y + 10, MUTED, false);
				y += 24;
			}
			y += 4;
		}

		if (s.has("comm") && s.get("comm").isJsonObject()) {
			JsonObject c = s.getAsJsonObject("comm");
			String stat = c.get("stat").getAsString();
			long goal = c.get("goal").getAsLong(), sum = c.get("sum").getAsLong();
			long mine = c.get("mine").getAsLong(), need = c.get("need").getAsLong();
			y = section(g, Component.translatable("loothub.lh.q.comm"),
					Component.translatable("loothub.lh.toast.points", c.get("reward").getAsInt()), y);
			g.drawString(font, Component.translatable("loothub.lh.q.comm.goal", Texts.goal(stat, goal)), cl + 8, y + 2, TEXT, false);
			if (sum < goal) {
				pinFlag(g, "c|" + stat + "|" + goal, cr - 12, y + 2);
			}
			String prog = Texts.value(stat, Math.min(sum, goal)) + " / " + Texts.value(stat, goal);
			int barW = Math.max(40, cw - 8 - font.width(prog) - 12);
			bar(g, cl + 8, y + 13, barW, goal == 0 ? 1 : sum / (double) goal, 0xFF6BD5FF);
			g.drawString(font, prog, cl + 8 + barW + 8, y + 10, MUTED, false);
			y += 22;
			Component mineText = c.get("paid").getAsBoolean() ? Component.translatable("loothub.lh.q.comm.paid")
					: Component.translatable(mine >= need ? "loothub.lh.q.comm.mine_ok" : "loothub.lh.q.comm.mine",
							Texts.value(stat, mine), Texts.value(stat, need));
			g.drawString(font, mineText, cl + 8, y, mine >= need ? 0xFF55E06A : MUTED, false);
			y += 18;
		}

		// Login-Serie (mit Login-Schutz)
		y = section(g, Component.translatable("loothub.lh.q.streaks"), null, y);
		JsonObject login = s.getAsJsonObject("login");
		int freeze = login.has("freeze") ? login.get("freeze").getAsInt() : 0;
		y = streakRow(g, y, "login", login.get("cur").getAsInt(), login.get("best").getAsInt(), true,
				Component.translatable("loothub.lh.q.streak.login.today").append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.translatable("loothub.lh.freeze.count", freeze).withStyle(st2 -> st2.withColor(0x7FB3FF))),
				Component.translatable("loothub.lh.q.streak.login.hint"));
		return y + 4;
	}

	private int streakRow(GuiGraphics g, int y, String kind, int cur, int best, boolean kept, Component state, Component hint) {
		g.drawString(font, Component.translatable("loothub.lh.q.streak." + kind, cur), cl + 8, y + 2, 0xFFFFD166, false);
		Component right = Component.empty().append(state).append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.translatable("loothub.lh.q.streak.best_days", best));
		g.drawString(font, right, cr - font.width(right), y + 2, kept ? 0xFF55E06A : MUTED, false);
		g.drawString(font, hint, cl + 8, y + 13, FAINT, false);
		return y + 26;
	}

	private int drawAch(GuiGraphics g, JsonObject s, int y) {
		return switch (achSub) {
			case "b", "m" -> drawCollection(g, s, achSub, y);
			default -> drawFeats(g, s, y);
		};
	}

	/** Heldentaten: die großen Erfolge mit 30 Stufen, epischem Namen und Rang. */
	/** Skilltree der Heldentaten: Knoten (Spalte, Zeile) und ihr Vorgänger. */
	private static final String[][] TREE = {
			{"root", "0", "2", ""},
			{"blocks", "1", "0", "root"}, {"placed", "2", "0", "blocks"}, {"picked", "3", "0", "placed"},
			{"mobs", "1", "1", "root"}, {"treasures", "2", "1", "mobs"},
			{"fish", "1", "2", "root"}, {"jumps", "2", "2", "fish"},
			{"login", "1", "3", "root"}, {"play", "2", "3", "login"}, {"dquests", "3", "3", "play"}, {"wquests", "4", "3", "dquests"},
			{"jobmoney", "1", "4", "root"}, {"secret", "2", "4", "jobmoney"},
	};
	private static String selected = "blocks";
	private static final net.minecraft.resources.Identifier ACH_BG = net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "ach/bg");

	private static net.minecraft.resources.Identifier achSprite(String name) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "ach/" + name);
	}

	/** Heldentaten als Skilltree wie die Fortschritte in Minecraft; Klick auf einen Knoten zeigt darunter die Stufen. */
	private int drawFeats(GuiGraphics g, JsonObject s, int y) {
		JsonObject cat = s.getAsJsonObject("cat");
		JsonObject ach = cat.getAsJsonObject("ach");
		JsonArray tierPoints = cat.getAsJsonArray("tierPoints");
		JsonObject mine = s.getAsJsonObject("ach");
		JsonObject tot = s.getAsJsonObject("tot");
		int total = 0, reached = 0;
		for (String stat : ach.keySet()) {
			total += ach.getAsJsonArray(stat).size();
			reached += mine.has(stat) ? mine.get(stat).getAsInt() : 0;
		}
		y = section(g, Component.translatable("loothub.lh.ach.sub.feats"),
				Component.translatable("loothub.lh.ach.count", reached, total), y);
		// Fläche des Baums
		int sx = 74, sy = 38, nodes = 5;
		int treeW = Math.min(cw, 40 + 4 * sx + 40), treeH = 5 * sy + 16;
		int tx0 = cl + (cw - treeW) / 2, ty0 = y;
		g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, ACH_BG, tx0, ty0, treeW, treeH);
		g.fill(tx0, ty0, tx0 + treeW, ty0 + 1, 0xFF4A3A2A);
		g.fill(tx0, ty0 + treeH - 1, tx0 + treeW, ty0 + treeH, 0xFF4A3A2A);
		java.util.Map<String, int[]> pos = new java.util.HashMap<>();
		for (String[] n : TREE) {
			pos.put(n[0], new int[]{tx0 + 30 + Integer.parseInt(n[1]) * sx, ty0 + 20 + Integer.parseInt(n[2]) * sy});
		}
		// Verbindungslinien (rechtwinklig wie in Minecraft): dunkler Rand, helle Linie
		for (int pass = 0; pass < 2; pass++) {
			for (String[] n : TREE) {
				if (n[3].isEmpty()) {
					continue;
				}
				int[] a = pos.get(n[3]), b = pos.get(n[0]);
				int mx = (a[0] + b[0]) / 2;
				int c = pass == 0 ? 0xFF000000 : (tierOf(mine, n[0]) > 0 ? 0xFFF2C14E : 0xFFB9A9AB);
				int w = pass == 0 ? 2 : 1;
				g.fill(a[0], a[1] - w + 1, mx + w, a[1] + w, c);
				g.fill(mx - w + 1, Math.min(a[1], b[1]) - w + 1, mx + w, Math.max(a[1], b[1]) + w, c);
				g.fill(mx - w + 1, b[1] - w + 1, b[0], b[1] + w, c);
			}
		}
		// Knoten: Rahmen nach Rang (rund = Anfang, golden = ab Stufe 10, Stern = legendär), Icon darin
		JsonArray secrets = s.has("secrets") ? s.getAsJsonArray("secrets") : new JsonArray();
		int secretMax = cat.has("secrets") ? cat.getAsJsonArray("secrets").size() : 10;
		for (String[] n : TREE) {
			String id = n[0];
			int[] p = pos.get(id);
			int tier, max;
			Component name, sub;
			if (id.equals("root")) {
				tier = reached > 0 ? 1 : 0;
				max = 1;
				name = Component.translatable("loothub.lh.tree.root");
				sub = Component.translatable("loothub.lh.ach.count", reached, total);
			} else if (id.equals("secret")) {
				tier = secrets.size();
				max = secretMax;
				name = Component.translatable("loothub.lh.tree.secret");
				sub = Component.translatable("loothub.lh.tree.secret.count", tier, max);
			} else {
				tier = mine.has(id) ? mine.get(id).getAsInt() : 0;
				max = ach.has(id) ? ach.getAsJsonArray(id).size() : 30;
				name = Texts.achName(id);
				sub = Texts.rank(tier, max).copy().append(" · ").append(Component.translatable("loothub.lh.ach.tier", tier, max));
			}
			String frame = id.equals("root") || id.equals("secret") ? "frame_goal" : tier >= max ? "frame_challenge"
					: tier >= 10 ? "frame_goal" : "frame_task";
			int fs = 26;
			int fx = p[0] - fs / 2, fy = p[1] - fs / 2;
			boolean sel = id.equals(selected);
			if (sel) {
				g.fill(fx - 3, fy - 3, fx + fs + 3, fy + fs + 3, 0x60F2C14E);
			}
			g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, achSprite(frame), fx, fy, fs, fs);
			String icon = id.equals("root") ? "wquests" : id;
			g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, achSprite(icon), p[0] - 8, p[1] - 8, 16, 16);
			if (tier == 0) {
				g.fill(fx + 3, fy + 3, fx + fs - 3, fy + fs - 3, 0x90000000); // noch nicht begonnen: abgedunkelt
			}
			if (tier > 0 && !id.equals("root") && !id.equals("secret")) {
				int rc = Texts.rankColor(tier, max);
				g.fill(fx + fs - 6, fy + fs - 6, fx + fs, fy + fs, 0xFF000000);
				g.fill(fx + fs - 5, fy + fs - 5, fx + fs - 1, fy + fs - 1, rc);
			}
			Component tip = Component.empty().append(name.copy().withStyle(st -> st.withColor(0xF2C14E))).append("\n").append(sub);
			hits.add(new Hit(fx, fy, fs, fs, () -> selected = id, tip));
		}
		y += treeH + 8;
		// Details zum gewählten Knoten
		if (selected.equals("secret")) {
			y = drawSecrets(g, s, y);
		} else if (!selected.equals("root") && ach.has(selected)) {
			String stat = selected;
			JsonArray tiers = ach.getAsJsonArray(stat);
			int tier = mine.has(stat) ? mine.get(stat).getAsInt() : 0;
			long v = switch (stat) {
				case "dquests" -> s.get("dquests").getAsLong();
				case "wquests" -> s.get("wquests").getAsLong();
				case "login" -> s.getAsJsonObject("login").get("best").getAsLong();
				default -> tot.has(stat) ? tot.get(stat).getAsLong() : 0;
			};
			expanded.add(stat); // Stufen immer zeigen
			y = achRow(g, stat, Texts.achName(stat), Texts.stat(stat), ItemStack.EMPTY, stat, v, tiers, tierPoints, tier, y);
		} else {
			g.drawString(font, Component.translatable("loothub.lh.tree.hint"), cl + 8, y + 2, MUTED, false);
			y += 16;
		}
		return y;
	}

	/** Siegertreppchen für die ersten drei: Skins wippen leicht, Klick öffnet die Spielerkarte. */
	private int podium(GuiGraphics g, JsonArray list, int y) {
		if (list.isEmpty()) {
			return y;
		}
		int h = 96, cx = cl + cw / 2;
		int[] order = {1, 0, 2}; // links Platz 2, Mitte Platz 1, rechts Platz 3
		int[] heights = {26, 18, 12};
		int[] colors = {0xFFF2C14E, 0xFFC8CDD2, 0xFFCD8B4E};
		double t = System.currentTimeMillis() / 400.0;
		for (int k = 0; k < 3; k++) {
			int place = order[k];
			if (place >= list.size()) {
				continue;
			}
			JsonArray r = list.get(place).getAsJsonArray();
			int px = cx + (k - 1) * 70 - 26;
			int ph = heights[place];
			int baseY = y + h - ph;
			g.fill(px, baseY, px + 52, y + h, colors[place]);
			g.fill(px, baseY, px + 52, baseY + 2, 0x60FFFFFF);
			String num = String.valueOf(place + 1);
			g.drawString(font, num, px + 26 - font.width(num) / 2, baseY + (ph - 8) / 2 + 1, 0xFF2A1A10, false);
			String name = r.get(1).getAsString();
			net.minecraft.world.entity.player.PlayerSkin skin = de.loothub.client.cosmetics.SkinLookup.get(name);
			int bob = (int) Math.round(Math.sin(t + place * 1.3) * (place == 0 ? 2.5 : 1.5));
			int dollY = baseY - 34 - bob;
			if (skin != null) {
				PlayerCardScreen.doll(g, skin, px + 18, dollY, 1);
			}
			if (place == 0) {
				g.drawString(font, "♛", px + 22, dollY - 10 - bob, 0xFFF2C14E, false); // Krönchen für Platz 1
			}
			String shown = font.width(name) > 64 ? font.plainSubstrByWidth(name, 60) + "…" : name;
			g.drawString(font, shown, px + 26 - font.width(shown) / 2, baseY - 44 - bob, place == 0 ? 0xFFF2C14E : TEXT, false);
			String uuid = r.get(0).getAsString();
			hits.add(new Hit(px, baseY - 46, 52, 46 + ph, () -> minecraft.setScreen(new PlayerCardScreen(this, uuid, name)),
					Component.translatable("loothub.lh.card.open")));
		}
		return y + h + 8;
	}

	/** Minispiele: tägliches Quiz und Block-Raten. */
	private int drawGames(GuiGraphics g, JsonObject s, int y) {
		// Spielhalle: kleine Spiele mit Rekord, Ranglisten je Woche (Snake mit Taler für Platz 1–3)
		y = section(g, Component.translatable("loothub.lh.mg.hall"), Component.translatable("loothub.lh.mg.hall.sub"), y);
		// Spiel, Symbol, Symbolgröße (Ludo-Grafiken)
		String[][] games = {{"snake", "choco", "64"}, {"tower", "mg_penguin", "64"}, {"sheep", "mg_sheep", "64"}, {"elf", "mg_elf", "64"},
				{"memory", "mg_icon_memory", "32"}, {"craft", "mg_icon_craft", "32"}, {"word", "mg_icon_word", "32"}, {"dulli", "mg_dulli", "96"}};
		for (String[] gm : games) {
			String id = gm[0];
			int ts = Integer.parseInt(gm[2]);
			g.fill(cl + 4, y, cr - 4, y + 20, 0x18FFFFFF);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, ArcadeScreen.tex(gm[1]), cl + 7, y + 1, 0f, 0f, 18, 18, ts, ts,
					ts, ts);
			g.drawString(font, Component.translatable("loothub.lh.mg." + id), cl + 30, y + 2, TEXT, false);
			int rec = ArcadeScreen.best(id);
			String recText = rec == 0 ? "–" : Fmt.whole(rec);
			g.drawString(font, Component.translatable("loothub.lh.mg.record." + id, recText),
					cl + 30, y + 11, MUTED, false);
			if (id.equals("snake")) {
				Component rw = Component.translatable("loothub.lh.mg.weekly");
				g.drawString(font, rw, cl + 150, y + 6, 0xFF55E06A, false);
			}
			Component play = Component.translatable("loothub.lh.snake.play");
			int pw = font.width(play) + 14, px = cr - 8 - pw;
			gameButton(g, px, y + 2, pw, play, () -> minecraft.setScreen(switch (id) {
				case "tower" -> new TowerScreen(this);
				case "sheep" -> new SheepScreen(this);
				case "elf" -> new ElfScreen(this);
				case "memory" -> new MemoryScreen(this);
				case "craft" -> new CraftScreen(this);
				case "word" -> new WordScreen(this);
				case "dulli" -> new DulliScreen(this);
				default -> new SnakeScreen(this);
			}));
			gameButton(g, px - 24, y + 2, 20, Component.empty(), () -> minecraft.setScreen(new MgBoardScreen(this, id)));
			y += 22;
		}
		y += 8;

		// Quiz
		if (s.has("quiz") && s.get("quiz").isJsonObject()) {
			JsonObject q = s.getAsJsonObject("quiz");
			int qi = q.get("q").getAsInt();
			boolean done = q.get("done").getAsBoolean();
			y = section(g, Component.translatable("loothub.lh.games.quiz"),
					done ? Component.translatable("loothub.lh.games.tomorrow") : Component.translatable("loothub.lh.toast.points", q.get("reward").getAsInt()), y);
			for (var line : font.split(Component.translatable("loothub.lh.quiz." + qi), cw - 16)) {
				g.drawString(font, line, cl + 8, y + 2, TEXT, false);
				y += 11;
			}
			y += 4;
			int bw = (cw - 24) / 2;
			for (int i = 0; i < 4; i++) {
				int bx = cl + 8 + (i % 2) * (bw + 8), by2 = y + (i / 2) * 20;
				int a = q.get("a").getAsInt(), right = q.get("right").getAsInt();
				int col = !done ? 0xFF3A2A2C : i == right ? 0xFF2E7D3A : i == a ? 0xFF8A2A2A : 0xFF2A2022;
				g.fill(bx, by2, bx + bw, by2 + 16, col);
				Component opt = Component.translatable("loothub.lh.quiz." + qi + "." + i);
				g.drawString(font, opt, bx + 6, by2 + 4, TEXT, false);
				if (!done) {
					int answer = i;
					hits.add(new Hit(bx, by2, bw, 16, () -> Progress.quiz(answer, err -> message = err == null ? ""
							: Component.translatable("loothub.lh.error." + err).getString()), null));
				}
			}
			y += 44;
			if (done) {
				g.drawString(font, Component.translatable(q.get("ok").getAsBoolean() ? "loothub.lh.games.right" : "loothub.lh.games.wrong"),
						cl + 8, y, q.get("ok").getAsBoolean() ? 0xFF55E06A : 0xFFE23B3B, false);
				y += 14;
			}
			y = streakLine(g, q, "quiz", y) + 6;
		}
		// Block-Raten
		if (s.has("guess") && s.get("guess").isJsonObject()) {
			JsonObject gs = s.getAsJsonObject("guess");
			JsonArray opts = gs.getAsJsonArray("opts"), picked = gs.getAsJsonArray("picked");
			JsonArray rewards = gs.getAsJsonArray("rewards");
			boolean done = gs.get("done").getAsBoolean();
			int wrong = picked.size() - (done ? 1 : 0);
			y = section(g, Component.translatable("loothub.lh.games.guess"),
					done ? Component.translatable("loothub.lh.games.tomorrow")
							: Component.translatable("loothub.lh.games.guess.now", rewards.get(Math.min(wrong, rewards.size() - 1)).getAsInt()), y);
			// Ausschnitt der Block-Textur: wird mit jedem Fehlversuch größer
			String answer = gs.get("answer").getAsString();
			int[] crop = {3, 5, 9, 16};
			int size = done ? 16 : crop[Math.min(wrong, crop.length - 1)];
			int u = (16 - size) / 2;
			int box = 72;
			int bx = cl + 8, by2 = y + 2;
			g.fill(bx - 2, by2 - 2, bx + box + 2, by2 + box + 2, 0xFFF2C14E);
			g.fill(bx - 1, by2 - 1, bx + box + 1, by2 + box + 1, 0xFF000000);
			// Ausschnitt der Textur des heutigen Blocks
			String texBlock = done ? answer : gs.has("hint") ? gs.get("hint").getAsString() : "";
			if (!texBlock.isEmpty()) {
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
						net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/" + texBlock + ".png"), bx, by2, (float) u, (float) u,
						box, box, size, size, 16, 16);
			}
			int ox = bx + box + 14, oy = by2;
			int ow = Math.min(220, cr - ox - 4);
			for (int i = 0; i < opts.size(); i++) {
				String o = opts.get(i).getAsString();
				boolean tried = picked.contains(opts.get(i));
				boolean isAnswer = done && o.equals(answer);
				int col = isAnswer ? 0xFF2E7D3A : tried ? 0xFF4A2A2A : 0xFF3A2A2C;
				g.fill(ox, oy + i * 18, ox + ow, oy + i * 18 + 15, col);
				g.drawString(font, Component.translatable("block.minecraft." + o), ox + 6, oy + i * 18 + 4, tried && !isAnswer ? FAINT : TEXT, false);
				if (!done && !tried) {
					hits.add(new Hit(ox, oy + i * 18, ow, 15, () -> Progress.guess(o, err -> message = err == null ? ""
							: Component.translatable("loothub.lh.error." + err).getString()), null));
				}
			}
			y += Math.max(box, opts.size() * 18) + 6;
			y = streakLine(g, gs, "guess", y) + 6;
		}
		return y;
	}

	/** Knopf im Minispiel-Reiter (gezeichnet, Klick über die Trefferliste). */
	private void gameButton(GuiGraphics g, int x, int y, int w, Component label, Runnable action) {
		g.fill(x, y, x + w, y + 16, 0xFF3A2A2C);
		g.fill(x, y + 15, x + w, y + 16, RED);
		if (label.getString().isEmpty()) {
			// leerer Text = Ranglisten-Knopf mit Pokal
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "textures/gui/trophy.png"), x + (w - 12) / 2, y + 2,
					0f, 0f, 12, 12, 16, 16, 16, 16);
			hits.add(new Hit(x, y, w, 16, action, Component.translatable("loothub.lh.mg.board.title")));
			return;
		}
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + 4, TEXT, false);
		hits.add(new Hit(x, y, w, 16, action, null));
	}

	/** Serie (richtige Antworten an aufeinanderfolgenden Tagen) mit Ranglisten-Knopf. */
	private int streakLine(GuiGraphics g, JsonObject o, String game, int y) {
		if (!o.has("streak")) {
			return y;
		}
		JsonObject st = o.getAsJsonObject("streak");
		g.drawString(font, Component.translatable("loothub.lh.mg.streak", st.get("cur").getAsInt(), st.get("best").getAsInt()),
				cl + 8, y + 4, 0xFFF2C14E, false);
		gameButton(g, cr - 8 - 20, y, 20, Component.empty(), () -> minecraft.setScreen(new MgBoardScreen(this, game)));
		return y + 20;
	}

	/** Fähnchen zum An- und Abpinnen. */
	private void pinFlag(GuiGraphics g, String key, int px, int py) {
		boolean pin = Progress.pinned(key);
		g.drawString(font, "⚑", px, py, pin ? 0xFFF2C14E : 0xFF6A5A5C, false);
		hits.add(new Hit(px - 2, py - 2, 12, 12, () -> {
			if (!Progress.togglePin(key)) {
				message = Component.translatable("loothub.lh.pin.max", Progress.MAX_PINS).getString();
			} else {
				message = "";
			}
		}, Component.translatable(pin ? "loothub.lh.pin.off" : "loothub.lh.pin.on")));
	}

	private static int tierOf(JsonObject mine, String id) {
		return mine.has(id) ? mine.get(id).getAsInt() : id.equals("secret") || id.equals("root") ? 1 : 0;
	}

	/** Geheimnisse: Hinweis immer sichtbar, Name erst nach dem Finden. */
	private int drawSecrets(GuiGraphics g, JsonObject s, int y) {
		JsonObject cat = s.getAsJsonObject("cat");
		if (!cat.has("secrets")) {
			return y;
		}
		JsonArray all = cat.getAsJsonArray("secrets");
		JsonArray got = s.has("secrets") ? s.getAsJsonArray("secrets") : new JsonArray();
		int reward = cat.has("secretReward") ? cat.get("secretReward").getAsInt() : 0;
		y = section(g, Component.translatable("loothub.lh.tree.secret"),
				Component.translatable("loothub.lh.tree.secret.reward", reward), y);
		for (JsonElement e : all) {
			String id = e.getAsString();
			boolean found = got.contains(e);
			g.drawString(font, found ? "✔" : "?", cl + 8, y + 2, found ? 0xFF55E06A : 0xFFB07CFF, false);
			g.drawString(font, found ? Component.translatable("loothub.lh.secret." + id) : Component.literal("???"), cl + 22, y + 2,
					found ? TEXT : MUTED, false);
			Component hint = Component.translatable("loothub.lh.secret." + id + ".hint").withStyle(ChatFormatting.ITALIC);
			g.drawString(font, hint, cl + 130, y + 2, found ? FAINT : 0xFFD8C8CA, false);
			y += 13;
		}
		return y + 6;
	}

	/** Sammlung: eigene Erfolge je Block bzw. Mob (15 Stufen); Begonnene zuerst, dann alphabetisch. */
	private int drawCollection(GuiGraphics g, JsonObject s, String kind, int y) {
		JsonObject cat = s.getAsJsonObject("cat");
		if (!cat.has("colTiers")) {
			g.drawString(font, Component.translatable("loothub.lh.ach.col.old"), cl + 8, y + 4, MUTED, false);
			return y + 20;
		}
		JsonArray tiers = cat.getAsJsonObject("colTiers").getAsJsonArray(kind);
		JsonArray points = cat.getAsJsonArray("colPoints");
		JsonObject counts = s.has("col") ? s.getAsJsonObject("col").getAsJsonObject(kind) : new JsonObject();
		JsonObject got = s.has("cach") ? s.getAsJsonObject("cach").getAsJsonObject(kind) : new JsonObject();
		List<Collection.Entry> all = kind.equals("m") ? Collection.mobs() : Collection.blocks();
		int reached = 0;
		for (Collection.Entry e : all) {
			reached += got.has(e.id()) ? got.get(e.id()).getAsInt() : 0;
		}
		y = section(g, Collection.title(kind), Component.translatable("loothub.lh.ach.count", Fmt.whole(reached),
				Fmt.whole((long) all.size() * tiers.size())), y);
		String q = search == null ? "" : search.getValue().strip().toLowerCase(java.util.Locale.ROOT);
		List<Collection.Entry> list = new ArrayList<>();
		for (Collection.Entry e : all) {
			if (q.isEmpty() || e.name().toLowerCase(java.util.Locale.ROOT).contains(q) || e.id().contains(q)) {
				list.add(e);
			}
		}
		java.util.function.ToLongFunction<Collection.Entry> count = e -> counts.has(e.id()) ? counts.get(e.id()).getAsLong() : 0;
		// Begonnene nach oben (meiste zuerst), danach die noch unberührten alphabetisch
		list.sort((a, b) -> Long.compare(count.applyAsLong(b), count.applyAsLong(a)));
		if (list.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.lh.ach.col.none"), cl + 8, y + 4, MUTED, false);
			return y + 20;
		}
		for (Collection.Entry e : list) {
			int tier = got.has(e.id()) ? got.get(e.id()).getAsInt() : 0;
			y = achRow(g, kind + ":" + e.id(), Component.literal(e.name()), null, e.icon(), kind, count.applyAsLong(e), tiers, points,
					tier, y);
		}
		return y;
	}

	/**
	 * Eine Erfolgszeile: Name im Glanz des Rangs, darunter die Beschreibung, rechts Rang, Fortschritt und Belohnung der nächsten
	 * Stufe. Klick klappt alle Stufen auf.
	 */
	private int achRow(GuiGraphics g, String key, Component name, Component desc, ItemStack icon, String unit, long v,
			JsonArray tiers, JsonArray points, int tier, int y) {
		int max = tiers.size();
		int h = desc != null ? 30 : 24;
		boolean isOpen = expanded.contains(key);
		boolean visible = y + h > listTop && y < cb; // Zeilen außerhalb nur mitzählen, nicht zeichnen
		if (visible) {
			int rc = Texts.rankColor(tier, max);
			if (tier >= max) {
				g.fill(cl, y, cr, y + h - 2, 0x22F2C14E); // legendär: goldener Schimmer
			}
			int x = cl + 2;
			g.drawString(font, isOpen ? "▾" : "▸", x, y + 2, MUTED, false);
			x += 10;
			if (!icon.isEmpty()) {
				g.renderItem(icon, x, y + (h - 2 - 16) / 2 - 1);
				x += 20;
			}
			// Medaille in der Farbe des Rangs
			g.drawString(font, tier > 0 ? "✦" : "✧", x, y + 2, rc, false);
			g.drawString(font, name, x + 10, y + 2, tier > 0 ? TEXT : MUTED, false);
			if (desc != null) {
				g.drawString(font, desc, x + 10, y + 12, FAINT, false);
			}
			Component rank = Texts.rank(tier, max);
			int rx = cl + Math.min(240, cw / 3);
			g.drawString(font, rank, rx, y + 2, rc, false);
			g.drawString(font, Component.translatable("loothub.lh.ach.tier", tier, max), rx, desc != null ? y + 12 : y + 11, FAINT,
					false);
			String right;
			double f;
			if (tier >= max) {
				right = Component.translatable("loothub.lh.ach.max", Texts.value(unit, v)).getString();
				f = 1;
			} else {
				long next = tiers.get(tier).getAsLong();
				long prev = tier == 0 ? 0 : tiers.get(tier - 1).getAsLong();
				int p = points.get(Math.min(tier, points.size() - 1)).getAsInt();
				right = Texts.value(unit, v) + " / " + Texts.value(unit, next) + "  "
						+ Component.translatable("loothub.lh.toast.points", p).getString();
				f = (v - prev) / (double) Math.max(1, next - prev);
			}
			g.drawString(font, right, cr - font.width(right), y + 2, MUTED, false);
			if (tier < max) {
				Component nextRank = Component.translatable("loothub.lh.ach.next", Texts.rank(tier + 1, max));
				g.drawString(font, nextRank, cr - font.width(nextRank), desc != null ? y + 12 : y + 11, FAINT, false);
			}
			bar(g, cl + 2, y + h - 6, cw - 4, f, tier >= max ? rc : 0xFFF2C14E);
			hits.add(new Hit(cl, y, cw, h - 2, () -> {
				if (!expanded.remove(key)) {
					expanded.add(key);
				}
			}, Component.translatable(isOpen ? "loothub.lh.ach.collapse" : "loothub.lh.ach.expand")));
		}
		y += h;
		if (isOpen) {
			y = drawTiers(g, unit, tiers, points, tier, y);
		}
		return y;
	}

	/** Alle Stufen eines Erfolgs als Raster: geschafft (✔ in Rangfarbe), als Nächstes (★), noch offen (blass). */
	private int drawTiers(GuiGraphics g, String unit, JsonArray tiers, JsonArray tierPoints, int tier, int y) {
		int max = tiers.size();
		// Spaltenbreiten aus den längsten Texten, damit sich nichts überlappt
		int rankW = 0, valW = 0, ptsW = 0;
		for (int k = 0; k < max; k++) {
			rankW = Math.max(rankW, font.width(Texts.rank(k + 1, max)));
			valW = Math.max(valW, font.width(Texts.value(unit, tiers.get(k).getAsLong())));
			int p = tierPoints.get(Math.min(k, tierPoints.size() - 1)).getAsInt();
			ptsW = Math.max(ptsW, font.width(Component.translatable("loothub.lh.toast.points", p).getString()));
		}
		int cellW = 10 + rankW + 8 + valW + 8 + ptsW + 16, cellH = 12;
		int cols = Math.max(1, (cw - 12) / cellW);
		int rows = (max + cols - 1) / cols;
		g.fill(cl + 2, y - 4, cr - 2, y + rows * cellH + 2, 0x40000000);
		for (int k = 0; k < max; k++) {
			int col = k / rows, row = k % rows; // spaltenweise, damit die Reihenfolge von oben nach unten läuft
			int x = cl + 8 + col * cellW, cy = y + row * cellH;
			boolean done = k < tier, next = k == tier;
			int p = tierPoints.get(Math.min(k, tierPoints.size() - 1)).getAsInt();
			String mark = done ? "✔" : next ? "★" : "·";
			int rc = Texts.rankColor(k + 1, max);
			g.drawString(font, mark, x, cy, done ? 0xFF55E06A : next ? 0xFFF2C14E : FAINT, false);
			Component rank = Texts.rank(k + 1, max);
			g.drawString(font, rank, x + 10, cy, done || next ? rc : FAINT, false);
			g.drawString(font, Texts.value(unit, tiers.get(k).getAsLong()), x + 10 + rankW + 8, cy, done ? TEXT : next ? MUTED : FAINT, false);
			String pts = Component.translatable("loothub.lh.toast.points", p).getString();
			g.drawString(font, pts, x + cellW - 16 - font.width(pts), cy, done ? 0xFF55E06A : next ? MUTED : FAINT, false);
		}
		return y + rows * cellH + 8;
	}

	private int drawBoard(GuiGraphics g, JsonObject s, int y) {
		JsonObject b = Progress.board(board, period);
		Component hint = board.equals("dropped") && b != null && b.has("minPlay")
				? Component.translatable("loothub.lh.board.dropped.hint", Texts.value("play", b.get("minPlay").getAsLong()))
				: Component.translatable("loothub.lh.board.hint");
		y = section(g, Component.translatable("loothub.lh.board." + board).append(" · ")
				.append(Component.translatable("loothub.lh.period." + period)), hint, y);
		if (b == null) {
			g.drawString(font, Component.translatable("loothub.lh.loading"), cl + 8, y + 4, MUTED, false);
			return y + 20;
		}
		JsonArray list = b.getAsJsonArray("list");
		if (list.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.lh.board.empty"), cl + 8, y + 4, MUTED, false);
			return y + 20;
		}
		String self = net.minecraft.client.Minecraft.getInstance().getUser().getProfileId().toString();
		y = podium(g, list, y);
		int rank = 0;
		boolean meShown = false;
		for (JsonElement e : list) {
			JsonArray r = e.getAsJsonArray();
			rank++;
			boolean me = r.get(0).getAsString().equalsIgnoreCase(self);
			meShown |= me;
			if (me) {
				g.fill(cl, y - 1, cr, y + 11, 0x30E23B3B);
			}
			int rc = rank == 1 ? 0xFFF2C14E : rank == 2 ? 0xFFD0D0D0 : rank == 3 ? 0xFFCD8B4E : MUTED;
			g.drawString(font, "#" + rank, cl + 4, y + 1, rc, false);
			Component name = Component.literal(r.get(1).getAsString());
			Component badge = Shop.badge(r.get(2).getAsString());
			Component line = badge == null ? name : Component.empty().append(badge).append(" ").append(name);
			g.drawString(font, line, cl + 34, y + 1, me ? TEXT : 0xFFE8D8DA, false);
			String cardUuid = r.get(0).getAsString(), cardName = r.get(1).getAsString();
			hits.add(new Hit(cl + 30, y - 1, font.width(line) + 8, 12,
					() -> minecraft.setScreen(new PlayerCardScreen(this, cardUuid, cardName)),
					Component.translatable("loothub.lh.card.open")));
			String v = boardValue(board, r.get(3).getAsLong());
			g.drawString(font, v, cr - 4 - font.width(v), y + 1, me ? TEXT : MUTED, false);
			y += 13;
		}
		// eigene Position, wenn man nicht unter den gezeigten Plätzen ist
		if (!meShown && b.has("me") && b.get("me").isJsonObject()) {
			JsonObject me = b.getAsJsonObject("me");
			y += 4;
			g.fill(cl + 4, y - 3, cr - 4, y - 2, 0x40FFFFFF);
			g.fill(cl, y - 1, cr, y + 11, 0x30E23B3B);
			g.drawString(font, "#" + Fmt.whole(me.get("rank").getAsLong()), cl + 4, y + 1, MUTED, false);
			g.drawString(font, Component.translatable("loothub.lh.board.you"), cl + 34, y + 1, TEXT, false);
			String v = boardValue(board, me.get("v").getAsLong());
			g.drawString(font, v, cr - 4 - font.width(v), y + 1, TEXT, false);
			y += 13;
		} else if (!meShown) {
			y += 4;
			g.drawString(font, Component.translatable("loothub.lh.board.notyet"), cl + 8, y + 1, FAINT, false);
			y += 13;
		}
		return y;
	}

	private static String boardValue(String b, long v) {
		return switch (b) {
			case "points", "ach", "dquests", "wquests" -> Fmt.whole(v);
			case "login" -> Component.translatable(v == 1 ? "loothub.lh.day" : "loothub.lh.days", v).getString();
			case "jobday" -> Texts.value("jobmoney", v);
			default -> Texts.value(b, v);
		};
	}

	private void drawDropdown(GuiGraphics g, JsonObject s, int mx, int my) {
		Button btn = openButton();
		List<String> opts = options(s);
		int x = btn.getX(), y = btn.getY() + 17, w = btn.getWidth();
		g.nextStratum();
		g.fill(x - 1, y - 1, x + w + 1, y + opts.size() * 12 + 1, RED_DARK);
		g.fill(x, y, x + w, y + opts.size() * 12, 0xFF150D0F);
		for (int i = 0; i < opts.size(); i++) {
			String o = opts.get(i);
			int ry = y + i * 12;
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + 12;
			if (hover) {
				g.fill(x, ry, x + w, ry + 12, 0x40E23B3B);
			}
			boolean sel = open == 1 ? o.equals(board) : o.equals(period);
			g.drawString(font, Component.translatable((open == 1 ? "loothub.lh.board." : "loothub.lh.period.") + o), x + 4, ry + 2,
					sel ? RED : TEXT, false);
		}
	}

	private static JsonObject extra(JsonArray a) {
		return a.size() > 3 && a.get(3).isJsonObject() ? a.get(3).getAsJsonObject() : null;
	}

	private int drawShop(GuiGraphics g, JsonObject s, int y) {
		JsonObject cat = s.getAsJsonObject("cat");
		JsonArray shop = cat.getAsJsonArray("shop");
		JsonObject offer = cat.has("offer") ? cat.getAsJsonObject("offer") : null;
		JsonObject seasons = cat.has("seasons") ? cat.getAsJsonObject("seasons") : new JsonObject();

		// Angebot des Tages
		if (offer != null) {
			String oid = offer.get("id").getAsString();
			for (JsonElement e : shop) {
				JsonArray a = e.getAsJsonArray();
				if (a.get(0).getAsString().equals(oid)) {
					int off = cat.has("offerOff") ? (int) Math.round(cat.get("offerOff").getAsDouble() * 100) : 25;
					y = section(g, Component.translatable("loothub.lh.shop.section.offer"),
							Component.translatable("loothub.lh.shop.offer.hint", off), y);
					y = shopRow(g, s, a, y, offer.get("cost").getAsInt());
					y += 6;
				}
			}
		}
		// Saison-Artikel (nur während der Saison kaufbar; Gekauftes bleibt sichtbar)
		for (String season : seasons.keySet()) {
			JsonObject se = seasons.getAsJsonObject(season);
			boolean on = se.get("on").getAsBoolean();
			List<JsonArray> items = new ArrayList<>();
			for (JsonElement e : shop) {
				JsonArray a = e.getAsJsonArray();
				JsonObject x = extra(a);
				if (x != null && x.has("season") && x.get("season").getAsString().equals(season) && (on || Shop.owned(a.get(0).getAsString()))) {
					items.add(a);
				}
			}
			if (items.isEmpty()) {
				continue;
			}
			JsonArray until = se.getAsJsonArray("until");
			y = section(g, Component.translatable("loothub.lh.shop.section.season." + season),
					on ? Component.translatable("loothub.lh.shop.season.until", until.get(1).getAsInt(), until.get(0).getAsInt())
							: Component.translatable("loothub.lh.shop.season.over"), y);
			for (JsonArray a : items) {
				y = shopRow(g, s, a, y, -1);
			}
			y += 6;
		}
		for (String kind : new String[]{"freeze", "title", "tcolor", "badge", "fame", "steps", "crown", "eyes", "pet"}) {
			List<JsonArray> items = new ArrayList<>();
			for (JsonElement e : shop) {
				JsonArray a = e.getAsJsonArray();
				JsonObject x = extra(a);
				boolean reward = x != null && (x.has("ach") || x.has("col") || x.has("secrets"));
				boolean seasonal = x != null && x.has("season");
				if (seasonal) {
					continue;
				}
				if (kind.equals("fame") ? reward : !reward && a.get(1).getAsString().equals(kind)) {
					items.add(a);
				}
			}
			if (items.isEmpty()) {
				continue; // ältere Server-Version ohne diese Artikel
			}
			boolean cosmetic = kind.equals("steps") || kind.equals("crown") || kind.equals("eyes") || kind.equals("pet");
			y = section(g, Component.translatable("loothub.lh.shop.section." + kind),
					cosmetic ? Component.translatable("loothub.lh.shop.steps.hint")
							: kind.equals("fame") ? Component.translatable("loothub.lh.shop.fame.hint") : null, y);
			for (JsonArray a : items) {
				y = shopRow(g, s, a, y, -1);
			}
			y += 6;
		}
		return y;
	}

	/** Eine Shop-Zeile: Vorschau, Preis (oder Bedingung) und Knopf zum Kaufen, Anlegen bzw. Ablegen. */
	/** Mausposition beim Zeichnen (zum Hervorheben der Zeile unter der Maus). */
	private int hoverX, hoverY;

	private int shopRow(GuiGraphics g, JsonObject s, JsonArray a, int y, int priceOverride) {
		// Zeile unter der Maus hervorheben, damit man sieht, welcher Knopf zu welchem Artikel gehört
		if (hoverX >= cl && hoverX < cr && hoverY >= y - 2 && hoverY < y + 16 && hoverY >= listTop && hoverY < cb) {
			g.fill(cl, y - 2, cr, y + 16, 0x22FFFFFF);
			g.fill(cl, y - 2, cl + 2, y + 16, RED);
		}
		long balance = s.get("balance").getAsLong();
		String title = s.get("title").getAsString(), badge = s.get("badge").getAsString();
		JsonObject cat = s.getAsJsonObject("cat");
		String id = a.get(0).getAsString();
		String kind = a.get(1).getAsString();
		int base = a.get(2).getAsInt();
		JsonObject x = extra(a);
		boolean reward = x != null && (x.has("ach") || x.has("col") || x.has("secrets"));
		// Angebot des Tages gilt überall, wo der Artikel steht
		int cost = priceOverride >= 0 ? priceOverride : base;
		if (priceOverride < 0 && cat.has("offer") && cat.getAsJsonObject("offer").get("id").getAsString().equals(id)) {
			cost = cat.getAsJsonObject("offer").get("cost").getAsInt();
		}
		final int price = cost;
		boolean owned = Shop.owned(id);
		boolean cosmetic = kind.equals("steps") || kind.equals("crown") || kind.equals("eyes") || kind.equals("pet");
		if (kind.equals("freeze")) {
			// verbrauchbar: immer kaufbar, Anzahl daneben
			int have = s.getAsJsonObject("login").has("freeze") ? s.getAsJsonObject("login").get("freeze").getAsInt() : 0;
			g.drawString(font, Component.literal("❄ ").withStyle(st2 -> st2.withColor(0x7FB3FF)).append(Shop.name(id))
					.append(Component.translatable("loothub.lh.freeze.have", have).withStyle(ChatFormatting.GRAY)), cl + 8, y + 4, TEXT, false);
			String ps = Component.translatable("loothub.lh.shop.cost", Fmt.whole(price)).getString();
			int bw = 74, bx2 = cr - bw;
			g.drawString(font, ps, bx2 - 10 - font.width(ps), y + 4, 0xFFF2C14E, false);
			boolean can = balance >= price;
			Component label = Component.translatable("loothub.lh.shop.buy");
			g.fill(bx2, y + 1, cr, y + 15, can ? 0xFFE23B3B : 0xFF4A3A3C);
			g.drawString(font, label, bx2 + (bw - font.width(label)) / 2, y + 4, TEXT, false);
			hits.add(new Hit(bx2, y + 1, bw, 14, can ? () -> confirmBuy(id, price) : null,
					can ? Component.translatable("loothub.lh.freeze.tip") : Component.translatable("loothub.lh.shop.poor", Fmt.whole(price - balance))));
			return y + 18;
		}
		Component preview = switch (kind) {
			case "title" -> Shop.titleText(id, s.has("tcolor") ? s.get("tcolor").getAsString() : "");
			case "tcolor" -> Shop.titleText(title.isEmpty() ? "t_miner" : title, id).copy().append(
					Component.literal("  ").append(Shop.name(id).copy().withStyle(ChatFormatting.GRAY)));
			case "badge" -> Component.empty().append(Shop.badge(id) == null ? Component.literal("?") : Shop.badge(id)).append("  ")
					.append(Shop.name(id).copy().withStyle(ChatFormatting.GRAY));
			default -> Shop.name(id);
		};
		g.drawString(font, preview, cl + 8, y + 4, TEXT, false);
		int btnW = 74;
		int bx = cr - btnW;
		// Preis bzw. Bedingung
		Component need = null;
		if (reward) {
			if (x.has("ach")) {
				need = Component.translatable("loothub.lh.shop.need.ach", Texts.achName(x.get("ach").getAsString()));
			} else if (x.has("secrets")) {
				int found = s.has("secrets") ? s.getAsJsonArray("secrets").size() : 0;
				int all = cat.has("secrets") ? cat.getAsJsonArray("secrets").size() : 10;
				need = Component.translatable("loothub.lh.shop.need.secrets", found, all);
			} else {
				String c = x.get("col").getAsString();
				JsonArray pr = s.has("colBadges") && s.getAsJsonObject("colBadges").has(c) ? s.getAsJsonObject("colBadges").getAsJsonArray(c) : null;
				need = Component.translatable("loothub.lh.shop.need.col." + c, pr == null ? 0 : pr.get(0).getAsInt(), pr == null ? 0 : pr.get(1).getAsInt());
			}
			String ns = owned ? Component.translatable("loothub.lh.shop.unlocked").getString() : need.getString();
			g.drawString(font, ns, bx - 10 - font.width(ns), y + 4, owned ? FAINT : MUTED, false);
			// (Belohnungen kann man nicht verschenken)
		} else {
			String ps = Component.translatable("loothub.lh.shop.cost", Fmt.whole(price)).getString();
			int px = bx - 28 - font.width(ps); // Platz für den Geschenk-Knopf
			g.drawString(font, ps, px, y + 4, owned ? FAINT : 0xFFF2C14E, false);
			if (price != base && !owned) {
				// alter Preis durchgestrichen davor
				String old = Fmt.whole(base);
				int ox = px - 6 - font.width(old);
				g.drawString(font, old, ox, y + 4, FAINT, false);
				g.fill(ox - 1, y + 7, ox + font.width(old) + 1, y + 8, FAINT);
			}
		}
		boolean worn = kind.equals("title") ? id.equals(title) : kind.equals("tcolor")
				? id.equals(s.has("tcolor") ? s.get("tcolor").getAsString() : "") : kind.equals("badge") && id.equals(badge);
		Component label;
		int color;
		Runnable action;
		Component tip = null;
		if (!owned && reward) {
			label = Component.translatable("loothub.lh.shop.locked");
			color = 0xFF3A2E30;
			action = null;
			tip = need;
		} else if (!owned) {
			boolean can = balance >= price;
			label = Component.translatable("loothub.lh.shop.buy");
			color = can ? 0xFFE23B3B : 0xFF4A3A3C;
			action = can ? () -> confirmBuy(id, price) : null;
			tip = can ? null : Component.translatable("loothub.lh.shop.poor", Fmt.whole(price - balance));
		} else if (cosmetic) {
			label = Component.translatable("loothub.lh.shop.owned");
			color = 0xFF2E5E36;
			action = null;
			tip = Component.translatable("loothub.lh.shop.steps.tip");
		} else if (worn) {
			label = Component.translatable("loothub.lh.shop.worn");
			color = 0xFF2E7D3A;
			action = () -> equip(kind, "");
			tip = Component.translatable("loothub.lh.shop.unequip");
		} else {
			label = Component.translatable("loothub.lh.shop.equip");
			color = 0xFF6E2A30;
			action = () -> equip(kind, id);
		}
		g.fill(bx, y + 1, cr, y + 15, color);
		g.drawString(font, label, bx + (btnW - font.width(label)) / 2, y + 4, TEXT, false);
		if (action != null || tip != null) {
			hits.add(new Hit(bx, y + 1, btnW, 14, action, tip));
		}
		// Verschenken (nur kaufbare Artikel)
		if (!reward) {
			int gx = bx - 18;
			g.fill(gx, y + 1, gx + 14, y + 15, balance >= price ? 0xFF8A3A68 : 0xFF4A3A3C);
			g.drawString(font, "✉", gx + 3, y + 4, TEXT, false);
			hits.add(new Hit(gx, y + 1, 14, 14, balance >= price ? () -> minecraft.setScreen(new GiftScreen(this, id, price)) : null,
					Component.translatable("loothub.lh.gift.tip", Fmt.whole(price))));
		}
		// Klick auf die Zeile: Vorschau am eigenen Spieler
		hits.add(new Hit(cl, y, Math.max(40, bx - 10 - cl - 140), 16, () -> minecraft.setScreen(new ShopPreviewScreen(this, id, kind, price)),
				Component.translatable("loothub.lh.preview.click")));
		return y + 18;
	}

	private void confirmBuy(String id, int cost) {
		minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.lh.shop.confirm.title"),
				Component.translatable("loothub.lh.shop.confirm", Shop.name(id), Fmt.whole(cost)), () -> {
					message = "";
					Progress.buy(id, err -> {
						message = err == null ? "" : Component.translatable("loothub.lh.error." + err).getString();
						if (Shop.isCosmetic(id) && err == null) {
							de.loothub.client.cosmetics.Cosmetics.rebuild();
						}
					});
					minecraft.setScreen(this);
				}, Component.translatable("loothub.lh.shop.confirm.yes")));
	}

	private void equip(String kind, String id) {
		message = "";
		if (kind.equals("tcolor")) {
			Progress.equipColor(id, err -> message = err == null ? "" : Component.translatable("loothub.lh.error." + err).getString());
			return;
		}
		Progress.equip(kind.equals("title") ? id : null, kind.equals("badge") ? id : null,
				err -> message = err == null ? "" : Component.translatable("loothub.lh.error." + err).getString());
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		Button btn = openButton();
		if (open != 0 && btn != null) {
			JsonObject s = Progress.state();
			if (s != null) {
				List<String> opts = options(s);
				int x = btn.getX(), y = btn.getY() + 17, w = btn.getWidth();
				if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + opts.size() * 12) {
					String o = opts.get((int) ((event.y() - y) / 12));
					if (open == 1) {
						board = o;
						boardButton.setMessage(boardLabel());
					} else {
						period = o;
						periodButton.setMessage(periodLabel());
					}
					open = 0;
					scroll = 0;
					return true;
				}
			}
			if (!btn.isMouseOver(event.x(), event.y())) {
				open = 0;
				return true;
			}
		}
		if (event.button() == 0 && event.y() >= listTop && event.y() < cb) {
			for (Hit h : hits) {
				if (h.action() != null && h.over(event.x(), event.y())) {
					h.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			int max = Math.max(0, contentH - (cb - listTop));
			scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical) * 20));
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
