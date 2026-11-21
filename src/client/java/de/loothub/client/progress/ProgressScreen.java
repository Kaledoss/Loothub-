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
		QUESTS, ACH, BOARD, SHOP, ADMIN
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
				// Sammelbuch öffnen
				Component bookLabel = Component.translatable("loothub.lh.book.open");
				int bw2 = font.width(bookLabel) + 14;
				addRenderableWidget(Button.builder(bookLabel, btn -> minecraft.setScreen(new CollectionBookScreen(this, achSub)))
						.bounds(cr - 160 - 6 - bw2, ct + 19, bw2, 16).build());
				boardButton = null;
				periodButton = null;
				listTop = ct + 40;
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
		listTop = ct + 24;
	}

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
		int y = listTop - scroll;
		g.enableScissor(cl, listTop, cr, cb);
		int start = y;
		y = switch (tab) {
			case QUESTS -> drawQuests(g, s, y);
			case ACH -> drawAch(g, s, y);
			case BOARD -> drawBoard(g, s, y);
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
		for (String stat : ach.keySet()) {
			JsonArray tiers = ach.getAsJsonArray(stat);
			int tier = mine.has(stat) ? mine.get(stat).getAsInt() : 0;
			long v = switch (stat) {
				case "dquests" -> s.get("dquests").getAsLong();
				case "wquests" -> s.get("wquests").getAsLong();
				case "login" -> s.getAsJsonObject("login").get("best").getAsLong();
				default -> tot.has(stat) ? tot.get(stat).getAsLong() : 0;
			};
			y = achRow(g, stat, Texts.achName(stat), Texts.stat(stat), ItemStack.EMPTY, stat, v, tiers, tierPoints, tier, y);
		}
		return y;
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
		int cellW = 190, cellH = 12;
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
			g.drawString(font, Texts.value(unit, tiers.get(k).getAsLong()), x + 90, cy, done ? TEXT : next ? MUTED : FAINT, false);
			String pts = Component.translatable("loothub.lh.toast.points", p).getString();
			g.drawString(font, pts, x + cellW - 12 - font.width(pts), cy, done ? 0xFF55E06A : next ? MUTED : FAINT, false);
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
				boolean reward = x != null && (x.has("ach") || x.has("col"));
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
	private int shopRow(GuiGraphics g, JsonObject s, JsonArray a, int y, int priceOverride) {
		long balance = s.get("balance").getAsLong();
		String title = s.get("title").getAsString(), badge = s.get("badge").getAsString();
		JsonObject cat = s.getAsJsonObject("cat");
		String id = a.get(0).getAsString();
		String kind = a.get(1).getAsString();
		int base = a.get(2).getAsInt();
		JsonObject x = extra(a);
		boolean reward = x != null && (x.has("ach") || x.has("col"));
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
				}));
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
