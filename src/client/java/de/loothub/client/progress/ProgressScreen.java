package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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

	private int quests(GuiGraphics g, JsonArray list, int y) {
		for (JsonElement e : list) {
			JsonObject q = e.getAsJsonObject();
			String stat = q.get("stat").getAsString();
			long goal = q.get("goal").getAsLong();
			long have = Math.min(goal, q.get("have").getAsLong());
			boolean done = q.get("done").getAsBoolean();
			int diff = q.get("diff").getAsInt();
			g.fill(cl, y + 1, cl + 3, y + 17, DIFF[Math.max(0, Math.min(2, diff))]);
			Component text = Texts.goal(stat, goal);
			g.drawString(font, text, cl + 8, y + 2, done ? FAINT : TEXT, false);
			String right = done ? "✔ " + Component.translatable("loothub.lh.toast.points", q.get("points").getAsInt()).getString()
					: Component.translatable("loothub.lh.toast.points", q.get("points").getAsInt()).getString();
			g.drawString(font, right, cr - font.width(right), y + 2, done ? 0xFF55E06A : 0xFFF2C14E, false);
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
		y = quests(g, slot.getAsJsonArray("list"), y) + 6;

		JsonObject week = s.getAsJsonObject("week");
		y = section(g, Component.translatable("loothub.lh.q.week"),
				Component.translatable("loothub.lh.q.ends_in", Texts.left(week.get("ends").getAsLong() - now)), y);
		y = quests(g, week.getAsJsonArray("list"), y) + 6;

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
		y = section(g, Component.translatable("loothub.lh.ach.title"),
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
			g.drawString(font, Texts.stat(stat), cl + 2, y + 2, tier > 0 ? TEXT : MUTED, false);
			// Stufe als „★ 12/30“
			int starsX = cl + Math.min(170, cw / 3);
			g.drawString(font, Component.translatable("loothub.lh.ach.tier", tier, tiers.size()), starsX, y + 2,
					tier > 0 ? 0xFFF2C14E : FAINT, false);
			String unit = stat;
			String right;
			double f;
			if (tier >= tiers.size()) {
				right = Component.translatable("loothub.lh.ach.max", Texts.value(unit, v)).getString();
				f = 1;
			} else {
				long next = tiers.get(tier).getAsLong();
				long prev = tier == 0 ? 0 : tiers.get(tier - 1).getAsLong();
				int p = tierPoints.get(Math.min(tier, tierPoints.size() - 1)).getAsInt();
				right = Texts.value(unit, v) + " / " + Texts.value(unit, next) + "  "
						+ Component.translatable("loothub.lh.toast.points", p).getString();
				f = (v - prev) / (double) Math.max(1, next - prev);
			}
			g.drawString(font, right, cr - font.width(right), y + 2, MUTED, false);
			bar(g, cl + 2, y + 13, cw - 4, f, tier >= tiers.size() ? 0xFF55E06A : 0xFFF2C14E);
			y += 22;
		}
		return y;
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
		for (JsonElement e : list) {
			JsonArray r = e.getAsJsonArray();
			rank++;
			boolean me = r.get(0).getAsString().equalsIgnoreCase(self);
			if (me) {
				g.fill(cl, y - 1, cr, y + 11, 0x30E23B3B);
			}
			int rc = rank == 1 ? 0xFFF2C14E : rank == 2 ? 0xFFD0D0D0 : rank == 3 ? 0xFFCD8B4E : MUTED;
			g.drawString(font, "#" + rank, cl + 4, y + 1, rc, false);
			Component name = Component.literal(r.get(1).getAsString());
			Component badge = Shop.badge(r.get(2).getAsString());
			Component line = badge == null ? name : Component.empty().append(badge).append(" ").append(name);
			g.drawString(font, line, cl + 34, y + 1, me ? TEXT : 0xFFE8D8DA, false);
			String v = boardValue(board, r.get(3).getAsLong());
			g.drawString(font, v, cr - 4 - font.width(v), y + 1, me ? TEXT : MUTED, false);
			y += 13;
		}
		return y;
	}

	private static String boardValue(String b, long v) {
		return switch (b) {
			case "points", "ach", "dquests", "wquests" -> Fmt.whole(v);
			case "login" -> Component.translatable("loothub.lh.days", v).getString();
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

	private int drawShop(GuiGraphics g, JsonObject s, int y) {
		JsonArray shop = s.getAsJsonObject("cat").getAsJsonArray("shop");
		long balance = s.get("balance").getAsLong();
		String title = s.get("title").getAsString(), badge = s.get("badge").getAsString();
		for (String kind : new String[]{"freeze", "title", "badge", "steps"}) {
			y = section(g, Component.translatable("loothub.lh.shop.section." + kind),
					kind.equals("steps") ? Component.translatable("loothub.lh.shop.steps.hint") : null, y);
			for (JsonElement e : shop) {
				JsonArray a = e.getAsJsonArray();
				if (!a.get(1).getAsString().equals(kind)) {
					continue;
				}
				String id = a.get(0).getAsString();
				int cost = a.get(2).getAsInt();
				boolean owned = Shop.owned(id);
				if (kind.equals("freeze")) {
					// verbrauchbar: immer kaufbar, Anzahl daneben
					int have = s.getAsJsonObject("login").has("freeze") ? s.getAsJsonObject("login").get("freeze").getAsInt() : 0;
					g.drawString(font, Component.literal("❄ ").withStyle(st2 -> st2.withColor(0x7FB3FF)).append(Shop.name(id))
							.append(Component.translatable("loothub.lh.freeze.have", have).withStyle(ChatFormatting.GRAY)), cl + 8, y + 4, TEXT, false);
					String price = Component.translatable("loothub.lh.shop.cost", Fmt.whole(cost)).getString();
					int bw = 74, bx2 = cr - bw;
					g.drawString(font, price, bx2 - 10 - font.width(price), y + 4, 0xFFF2C14E, false);
					boolean can = balance >= cost;
					Component label = Component.translatable("loothub.lh.shop.buy");
					g.fill(bx2, y + 1, cr, y + 15, can ? 0xFFE23B3B : 0xFF4A3A3C);
					g.drawString(font, label, bx2 + (bw - font.width(label)) / 2, y + 4, TEXT, false);
					hits.add(new Hit(bx2, y + 1, bw, 14, can ? () -> confirmBuy(id, cost) : null,
							can ? Component.translatable("loothub.lh.freeze.tip") : Component.translatable("loothub.lh.shop.poor", Fmt.whole(cost - balance))));
					y += 18;
					continue;
				}
				Component preview = switch (kind) {
					case "title" -> Shop.titleText(id);
					case "badge" -> Component.empty().append(Shop.badge(id) == null ? Component.literal("?") : Shop.badge(id)).append("  ")
							.append(Shop.name(id).copy().withStyle(ChatFormatting.GRAY));
					default -> Shop.name(id);
				};
				g.drawString(font, preview, cl + 8, y + 4, TEXT, false);
				String price = Component.translatable("loothub.lh.shop.cost", Fmt.whole(cost)).getString();
				int btnW = 74;
				int bx = cr - btnW;
				g.drawString(font, price, bx - 10 - font.width(price), y + 4, owned ? FAINT : 0xFFF2C14E, false);
				boolean worn = kind.equals("title") ? id.equals(title) : kind.equals("badge") && id.equals(badge);
				Component label;
				int color;
				Runnable action;
				Component tip = null;
				if (!owned) {
					boolean can = balance >= cost;
					label = Component.translatable("loothub.lh.shop.buy");
					color = can ? 0xFFE23B3B : 0xFF4A3A3C;
					action = can ? () -> confirmBuy(id, cost) : null;
					tip = can ? null : Component.translatable("loothub.lh.shop.poor", Fmt.whole(cost - balance));
				} else if (kind.equals("steps")) {
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
				// Klick auf die Zeile: Vorschau am eigenen Spieler
				String pk = kind;
				hits.add(new Hit(cl, y, bx - 10 - cl, 16, () -> minecraft.setScreen(new ShopPreviewScreen(this, id, pk, cost)),
						Component.translatable("loothub.lh.preview.click")));
				y += 18;
			}
			y += 6;
		}
		return y;
	}

	private void confirmBuy(String id, int cost) {
		minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.lh.shop.confirm.title"),
				Component.translatable("loothub.lh.shop.confirm", Shop.name(id), Fmt.whole(cost)), () -> {
					message = "";
					Progress.buy(id, err -> {
						message = err == null ? "" : Component.translatable("loothub.lh.error." + err).getString();
						if (Shop.STEPS.containsKey(id) && err == null) {
							de.loothub.client.cosmetics.Cosmetics.rebuild();
						}
					});
					minecraft.setScreen(this);
				}));
	}

	private void equip(String kind, String id) {
		message = "";
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
