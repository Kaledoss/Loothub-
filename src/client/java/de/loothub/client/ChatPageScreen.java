package de.loothub.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.quick.ChatLog;

/** Seite „Chat“: Privatnachrichten, Suche im Chat dieser Sitzung und der eigene Nachrichten-Filter. */
public class ChatPageScreen extends LoothubScreen {
	private enum Tab {
		PMS, SEARCH, FILTER
	}

	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final int LINE_H = 10;
	private static Tab tab = Tab.PMS;
	private static String query = "";

	private EditBox searchBox, wordBox;
	private List<FormattedCharSequence> lines = List.of();
	private int matches;
	private int listTop;
	private int scroll;
	private int seen = -1;

	public ChatPageScreen() {
		super(Page.CHAT);
	}

	@Override
	protected void initPage() {
		int tw = 100;
		int x = cl;
		for (Tab t : Tab.values()) {
			Component label = Component.translatable("loothub.chatpage.tab." + t.name().toLowerCase(Locale.ROOT));
			if (t == Tab.PMS && ChatLog.unreadPms() > 0 && tab != Tab.PMS) {
				label = label.copy().append(Component.literal(" (" + ChatLog.unreadPms() + ")").withStyle(ChatFormatting.GOLD));
			}
			if (t == tab) {
				label = Component.literal("▶ ").append(label);
			}
			addRenderableWidget(Button.builder(label, b -> {
				tab = t;
				scroll = 0;
				rebuildWidgets();
			}).bounds(x, ct, tw, 16).build());
			x += tw + 4;
		}
		int y = ct + 24;
		switch (tab) {
			case PMS -> listTop = y;
			case SEARCH -> {
				searchBox = addRenderableWidget(new EditBox(font, cl, y, Math.min(240, cw), 16, Component.translatable("loothub.chatpage.search")));
				searchBox.setMaxLength(64);
				searchBox.setHint(Component.translatable("loothub.chatpage.search").withStyle(ChatFormatting.DARK_GRAY));
				searchBox.setValue(query);
				searchBox.setResponder(v -> {
					query = v;
					scroll = 0;
					rebuildLines();
				});
				setInitialFocus(searchBox);
				listTop = y + 22;
			}
			case FILTER -> {
				WidgetConfig c = WidgetConfig.get();
				addRenderableWidget(Button.builder(onOff(c.chatFilter), b -> {
					c.chatFilter = !c.chatFilter;
					c.save();
					b.setMessage(onOff(c.chatFilter));
				}).bounds(cl, y, 120, 16).build());
				wordBox = addRenderableWidget(new EditBox(font, cl + 124, y, 140, 16, Component.translatable("loothub.chatpage.word")));
				wordBox.setMaxLength(40);
				wordBox.setHint(Component.translatable("loothub.chatpage.word").withStyle(ChatFormatting.DARK_GRAY));
				addRenderableWidget(Button.builder(Component.translatable("loothub.alltag.add"), b -> addWord())
						.bounds(cl + 268, y, 80, 16).build());
				listTop = y + 34;
				List<String> words = words();
				int visible = Math.max(1, (cb - listTop) / 18);
				scroll = Math.max(0, Math.min(scroll, Math.max(0, words.size() - visible)));
				for (int i = 0; i < visible && i + scroll < words.size(); i++) {
					String w = words.get(i + scroll);
					addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
						words().remove(w);
						WidgetConfig.get().save();
						rebuildWidgets();
					}).bounds(cl + 200, listTop + i * 18, 16, 14).build());
				}
			}
		}
		rebuildLines();
	}

	private static List<String> words() {
		WidgetConfig c = WidgetConfig.get();
		if (c.chatFilterWords == null) {
			c.chatFilterWords = new ArrayList<>();
		}
		return c.chatFilterWords;
	}

	private void addWord() {
		String w = wordBox.getValue().strip();
		if (w.isEmpty() || words().stream().anyMatch(x -> x.equalsIgnoreCase(w))) {
			return;
		}
		words().add(w);
		WidgetConfig c = WidgetConfig.get();
		c.chatFilter = true;
		c.save();
		rebuildWidgets();
	}

	private static Component onOff(boolean on) {
		return Component.translatable("loothub.chatpage.filter").append(": ")
				.append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private void rebuildLines() {
		if (tab == Tab.FILTER) {
			lines = List.of();
			return;
		}
		List<ChatLog.Entry> src = tab == Tab.PMS ? ChatLog.pms() : ChatLog.all();
		String q = query.strip().toLowerCase(Locale.ROOT);
		List<FormattedCharSequence> out = new ArrayList<>();
		int width = cw - 8;
		matches = 0;
		// Neueste oben
		for (int i = src.size() - 1; i >= 0; i--) {
			ChatLog.Entry e = src.get(i);
			if (tab == Tab.SEARCH && (q.isEmpty() || !e.plain().toLowerCase(Locale.ROOT).contains(q))) {
				continue;
			}
			matches++;
			MutableComponent line = Component.literal("[" + HM.format(Instant.ofEpochMilli(e.time()).atZone(ZoneId.systemDefault())) + "] ")
					.withStyle(ChatFormatting.DARK_GRAY).append(e.text());
			out.addAll(font.split(line, width));
			if (out.size() > 4000) {
				break;
			}
		}
		lines = out;
		seen = tab == Tab.PMS ? src.size() : ChatLog.all().size();
	}

	@Override
	public void tick() {
		super.tick();
		int now = tab == Tab.PMS ? ChatLog.pms().size() : ChatLog.all().size();
		if (tab != Tab.FILTER && now != seen) {
			rebuildLines();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (tab == Tab.FILTER) {
			g.drawString(font, Component.translatable("loothub.chatpage.filter.hint"), cl, listTop - 12, FAINT, false);
			List<String> words = words();
			if (words.isEmpty()) {
				g.drawString(font, Component.translatable("loothub.chatpage.filter.empty"), cl + 4, listTop + 4, FAINT, false);
			}
			int visible = Math.max(1, (cb - listTop) / 18);
			for (int i = 0; i < visible && i + scroll < words.size(); i++) {
				int ry = listTop + i * 18;
				g.fill(cl, ry, cl + 196, ry + 14, 0x18FFFFFF);
				g.drawString(font, words.get(i + scroll), cl + 6, ry + 3, TEXT, false);
			}
			return;
		}
		if (tab == Tab.SEARCH && !query.isBlank()) {
			Component c = Component.translatable("loothub.chatpage.matches", matches);
			g.drawString(font, c, cr - font.width(c), ct + 28, FAINT, false);
		}
		if (lines.isEmpty()) {
			Component m = Component.translatable(tab == Tab.PMS ? "loothub.chatpage.pms.empty"
					: query.isBlank() ? "loothub.chatpage.search.hint" : "loothub.chatpage.search.none");
			g.drawString(font, m, cl + (cw - font.width(m)) / 2, listTop + 16, FAINT, false);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / LINE_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i < visible && i + scroll < lines.size(); i++) {
			g.drawString(font, lines.get(i + scroll), cl + 4, listTop + i * LINE_H + 1, TEXT, false);
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			if (tab == Tab.FILTER) {
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
