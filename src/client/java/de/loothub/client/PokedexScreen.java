package de.loothub.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import de.loothub.client.pcg.Pokedex;
import de.loothub.client.pcg.PokedexData;

/**
 * Seite „Pokédex“: beginnt leer; jedes selbst gefangene Pokémon bekommt automatisch einen Eintrag mit
 * Nummer im Nationalen Pokédex, deutschem Namen und Fangdatum. Nur Text, keine Bilder.
 */
public class PokedexScreen extends LoothubScreen {
	private record Row(int number, String german, String english, long caughtAt) {
	}

	private static final int ROW_H = 16;
	private static final int GREEN = 0xFF55E06A;

	private static String search = "";
	private int scroll;
	private int listTop;
	private List<Row> rows = List.of();
	private int seenDataSize = -1;
	private int seenCaught = -1;

	public PokedexScreen() {
		super(Page.POKEDEX);
	}

	@Override
	protected void initPage() {
		PokedexData.ensureLoaded();
		EditBox box = new EditBox(font, cl, ct, Math.min(260, cw), 16, Component.translatable("loothub.screen.search"));
		box.setHint(Component.translatable("loothub.pokedex.search").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(t -> {
			search = t;
			scroll = 0;
			refresh();
		});
		addRenderableWidget(box);
		listTop = ct + 22;
		refresh();
	}

	private void refresh() {
		List<PokedexData.Entry> data = PokedexData.entries();
		Map<String, Long> caught = Pokedex.all();
		seenDataSize = data.size();
		seenCaught = caught.size();
		Map<String, PokedexData.Entry> byEnglish = new HashMap<>();
		for (PokedexData.Entry e : data) {
			byEnglish.put(e.english().toLowerCase(Locale.ROOT), e);
		}
		String q = search.strip().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
		List<Row> out = new ArrayList<>();
		for (Map.Entry<String, Long> c : caught.entrySet()) {
			PokedexData.Entry e = byEnglish.get(c.getKey());
			int number = e == null ? Integer.MAX_VALUE : e.number();
			String english = e == null ? c.getKey() : e.english();
			String german = e == null ? english : e.german();
			if (!q.isEmpty() && !german.toLowerCase(Locale.ROOT).contains(q) && !english.toLowerCase(Locale.ROOT).contains(q)
					&& !(e != null && (String.valueOf(number).equals(q) || String.format(Locale.ROOT, "%03d", number).equals(q)))) {
				continue;
			}
			out.add(new Row(number, german, english, c.getValue()));
		}
		out.sort(Comparator.comparingInt(Row::number).thenComparing(Row::english));
		rows = out;
	}

	@Override
	protected Component headerRight() {
		return Component.translatable("loothub.pokedex.count", Pokedex.all().size());
	}

	@Override
	public void tick() {
		super.tick();
		if (PokedexData.entries().size() != seenDataSize || Pokedex.all().size() != seenCaught) {
			refresh();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (rows.isEmpty()) {
			Component msg = Component.translatable(search.isBlank() ? "loothub.pokedex.empty" : "loothub.screen.no_results");
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, listTop + 20, FAINT, false);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i <= visible && i + scroll < rows.size(); i++) {
			Row r = rows.get(i + scroll);
			int y = listTop + i * ROW_H;
			boolean hover = mouseX >= cl && mouseX < cr && mouseY >= y && mouseY < y + ROW_H - 1 && mouseY < cb;
			g.fill(cl, y, cr, y + ROW_H - 1, hover ? 0x30E23B3B : (i % 2 == 0 ? 0x14FFFFFF : 0x08FFFFFF));
			g.drawString(font, "✔", cl + 4, y + 4, GREEN, false);
			String num = r.number() == Integer.MAX_VALUE ? "#????" : String.format(Locale.ROOT, "#%04d", r.number());
			g.drawString(font, num, cl + 16, y + 4, MUTED, false);
			g.drawString(font, r.german(), cl + 54, y + 4, TEXT, false);
			if (!r.german().equalsIgnoreCase(r.english())) {
				g.drawString(font, r.english(), cl + 54 + font.width(r.german()) + 8, y + 4, FAINT, false);
			}
			String when = r.caughtAt() > 0 ? Component.translatable("loothub.pokedex.caught_at",
					Fmt.date(r.caughtAt(), true)).getString() : "";
			g.drawString(font, when, cr - 6 - font.width(when), y + 4, FAINT, false);
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
