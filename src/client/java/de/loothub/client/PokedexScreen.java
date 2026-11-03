package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import de.loothub.client.pcg.Pokedex;
import de.loothub.client.pcg.PokedexData;

/**
 * Seite „Pokédex“: alle Pokémon nach Nummer im Nationalen Pokédex mit deutschem Namen und Fang-Status
 * aus dem eigenen Pokédex (gelernt aus Fang-Meldungen und !pokecheck). Nur Text, keine Bilder.
 */
public class PokedexScreen extends LoothubScreen {
	private enum Filter { ALL, CAUGHT, MISSING }

	private static final int TILE_H = 14;
	private static final int TILE_MIN_W = 150;
	private static final int GREEN = 0xFF55E06A;
	private static final int BLUE = 0xFF6FA8FF;

	private static String search = "";
	private static Filter filter = Filter.ALL;
	private int scroll;
	private int cols;
	private int tileW;
	private int gridTop;
	private Button filterButton;
	private List<PokedexData.Entry> shown = List.of();
	private int seenSize = -1;

	public PokedexScreen() {
		super(Page.POKEDEX);
	}

	@Override
	protected void initPage() {
		PokedexData.ensureLoaded();
		int filterW = 110;
		EditBox box = new EditBox(font, cl, ct, cw - filterW - 4, 16, Component.translatable("loothub.screen.search"));
		box.setHint(Component.translatable("loothub.pokedex.search").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(t -> {
			search = t;
			scroll = 0;
			refresh();
		});
		addRenderableWidget(box);
		filterButton = addRenderableWidget(Button.builder(filterLabel(), b -> {
					filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
					b.setMessage(filterLabel());
					scroll = 0;
					refresh();
				})
				.bounds(cr - filterW, ct, filterW, 16).build());
		gridTop = ct + 32;
		cols = Math.max(1, cw / TILE_MIN_W);
		tileW = cw / cols;
		refresh();
	}

	private Component filterLabel() {
		return Component.translatable("loothub.pokedex.filter." + filter.name().toLowerCase(Locale.ROOT));
	}

	private void refresh() {
		List<PokedexData.Entry> all = PokedexData.entries();
		seenSize = all.size();
		String q = search.strip().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
		List<PokedexData.Entry> out = new ArrayList<>();
		for (PokedexData.Entry e : all) {
			Pokedex.State st = Pokedex.state(e.english());
			if (filter == Filter.CAUGHT && st != Pokedex.State.CAUGHT) {
				continue;
			}
			if (filter == Filter.MISSING && st == Pokedex.State.CAUGHT) {
				continue;
			}
			if (!q.isEmpty() && !e.german().toLowerCase(Locale.ROOT).contains(q)
					&& !e.english().toLowerCase(Locale.ROOT).contains(q) && !String.valueOf(e.number()).equals(q)
					&& !String.format(Locale.ROOT, "%03d", e.number()).equals(q)) {
				continue;
			}
			out.add(e);
		}
		shown = out;
	}

	@Override
	protected Component headerRight() {
		List<PokedexData.Entry> all = PokedexData.entries();
		if (all.isEmpty()) {
			return null;
		}
		int caught = 0;
		for (PokedexData.Entry e : all) {
			if (Pokedex.state(e.english()) == Pokedex.State.CAUGHT) {
				caught++;
			}
		}
		return Component.translatable("loothub.pokedex.count", caught, all.size());
	}

	@Override
	public void tick() {
		super.tick();
		if (PokedexData.entries().size() != seenSize) {
			refresh();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(font, Component.translatable("loothub.pokedex.legend"), cl, ct + 20, FAINT, false);
		if (shown.isEmpty()) {
			String key = PokedexData.isLoading() || (PokedexData.entries().isEmpty() && !PokedexData.failed())
					? "loothub.pokedex.loading" : PokedexData.failed() ? "loothub.pokedex.error" : "loothub.screen.no_results";
			Component msg = Component.translatable(key);
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, gridTop + 20, FAINT, false);
			return;
		}
		int visibleRows = Math.max(1, (cb - gridTop) / TILE_H);
		int totalRows = (shown.size() + cols - 1) / cols;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - visibleRows)));
		g.enableScissor(cl, gridTop, cr, cb);
		for (int row = 0; row <= visibleRows; row++) {
			for (int col = 0; col < cols; col++) {
				int idx = (row + scroll) * cols + col;
				if (idx >= shown.size()) {
					break;
				}
				PokedexData.Entry e = shown.get(idx);
				int x = cl + col * tileW;
				int y = gridTop + row * TILE_H;
				boolean hover = mouseX >= x && mouseX < x + tileW - 2 && mouseY >= y && mouseY < y + TILE_H - 1 && mouseY < cb;
				g.fill(x, y, x + tileW - 2, y + TILE_H - 1, hover ? 0x30E23B3B : ((row + col) % 2 == 0 ? 0x14FFFFFF : 0x08FFFFFF));
				Pokedex.State st = Pokedex.state(e.english());
				int color = switch (st) {
					case CAUGHT -> GREEN;
					case NOT_CAUGHT -> BLUE;
					case UNKNOWN -> FAINT;
				};
				String mark = st == Pokedex.State.CAUGHT ? "✔" : st == Pokedex.State.NOT_CAUGHT ? "✘" : "·";
				g.drawString(font, mark, x + 3, y + 3, color, false);
				g.drawString(font, String.format(Locale.ROOT, "#%04d", e.number()), x + 12, y + 3, MUTED, false);
				g.drawString(font, e.german(), x + 46, y + 3, st == Pokedex.State.CAUGHT ? TEXT : MUTED, false);
				if (hover && !e.german().equals(e.english())) {
					g.setTooltipForNextFrame(font, Component.literal(e.english()), mouseX, mouseY);
				}
			}
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= gridTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
