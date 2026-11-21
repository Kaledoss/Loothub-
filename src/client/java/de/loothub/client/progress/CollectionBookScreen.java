package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.loothub.client.Fmt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Sammelbuch: alle Blöcke bzw. Mobs der Sammlung als Buch (so groß wie das Loothub-Fenster). Entdecktes mit Rang-Ecke,
 * Unentdecktes nur als Schatten. Blättern mit den Pfeilen oder dem Mausrad.
 */
public final class CollectionBookScreen extends Screen {
	private static final Identifier BOOK = Identifier.fromNamespaceAndPath("loothub", "textures/gui/book.png");
	private static final int TEX_W = 684, TEX_H = 444;
	/** Seitenflächen im Buchbild (Anteile): links und rechts, oben und unten. */
	private static final float LX0 = 0.135F, LX1 = 0.455F, RX0 = 0.565F, RX1 = 0.885F, PY0 = 0.085F, PY1 = 0.86F;
	private static final int INK = 0xFF4A2E1A, INK_SOFT = 0xFF8A6A4A, CELL = 20;

	private final Screen parent;
	private String kind;
	private int spread; // Doppelseite (0 = erste)
	private int bx, by, bw, bh;
	private final List<Runnable> clicks = new ArrayList<>();
	private final List<int[]> clickAreas = new ArrayList<>();

	public CollectionBookScreen(Screen parent, String kind) {
		super(Component.translatable("loothub.lh.book.title"));
		this.parent = parent;
		this.kind = kind.equals("m") ? "m" : "b";
	}

	@Override
	protected void init() {
		int margin = Mth.clamp(Math.min(width, height) / 24, 4, 24);
		int aw = width - 2 * margin, ah = height - 2 * margin - 16; // oben Platz für die Lesezeichen
		float scale = Math.min(aw / (float) TEX_W, ah / (float) TEX_H);
		bw = (int) (TEX_W * scale);
		bh = (int) (TEX_H * scale);
		bx = (width - bw) / 2;
		by = margin + 16 + (ah - bh) / 2;
	}

	private int perPage() {
		int pw = (int) ((LX1 - LX0) * bw), ph = (int) ((PY1 - PY0) * bh) - 26;
		return Math.max(1, (pw / CELL) * Math.max(1, ph / CELL));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		clicks.clear();
		clickAreas.clear();
		// Lesezeichen oben: Blöcke / Mobs
		int tx = bx + (int) (bw * 0.16F);
		for (String k : new String[]{"b", "m"}) {
			Component label = Collection.title(k);
			int w = font.width(label) + 16;
			boolean on = k.equals(kind);
			g.fill(tx, by - 16, tx + w, by + (on ? 6 : 2), on ? 0xFFB0302C : 0xFF6E2A30);
			g.fill(tx + 1, by - 15, tx + w - 1, by - 14, 0x40FFFFFF);
			g.drawString(font, label, tx + 8, by - 11, on ? 0xFFFFF4D8 : 0xFFD8C0A8, false);
			String key = k;
			area(tx, by - 16, w, 18, () -> {
				kind = key;
				spread = 0;
			});
			tx += w + 4;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, BOOK, bx, by, 0f, 0f, bw, bh, TEX_W, TEX_H, TEX_W, TEX_H);

		JsonObject s = Progress.state();
		if (s == null || !s.getAsJsonObject("cat").has("colTiers")) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.loading"), bx + bw / 2, by + bh / 2, INK);
			return;
		}
		JsonArray tiers = s.getAsJsonObject("cat").getAsJsonObject("colTiers").getAsJsonArray(kind);
		JsonObject counts = s.has("col") ? s.getAsJsonObject("col").getAsJsonObject(kind) : new JsonObject();
		JsonObject got = s.has("cach") ? s.getAsJsonObject("cach").getAsJsonObject(kind) : new JsonObject();
		List<Collection.Entry> all = kind.equals("m") ? Collection.mobs() : Collection.blocks();
		int found = 0;
		for (Collection.Entry e : all) {
			found += counts.has(e.id()) && counts.get(e.id()).getAsLong() > 0 ? 1 : 0;
		}
		int per = perPage();
		int pages = Math.max(1, (all.size() + per - 1) / per);
		int spreads = (pages + 1) / 2;
		spread = Mth.clamp(spread, 0, spreads - 1);

		Collection.Entry hover = null;
		for (int side = 0; side < 2; side++) {
			int page = spread * 2 + side;
			int px0 = bx + (int) ((side == 0 ? LX0 : RX0) * bw), px1 = bx + (int) ((side == 0 ? LX1 : RX1) * bw);
			int py0 = by + (int) (PY0 * bh), py1 = by + (int) (PY1 * bh);
			// Kopfzeile: links Titel und Fortschritt, rechts Seitenzahl
			if (side == 0) {
				g.drawString(font, Collection.title(kind), px0, py0, INK, false);
				String prog = Component.translatable("loothub.lh.book.found", found, all.size()).getString();
				g.drawString(font, prog, px1 - font.width(prog), py0, INK_SOFT, false);
			} else {
				String pg = Component.translatable("loothub.lh.book.page", spread + 1, spreads).getString();
				g.drawString(font, pg, px1 - font.width(pg), py0, INK_SOFT, false);
			}
			g.fill(px0, py0 + 11, px1, py0 + 12, 0x60704A2A);
			int cols = Math.max(1, (px1 - px0) / CELL);
			int gx0 = px0 + ((px1 - px0) - cols * CELL) / 2;
			int start = page * per;
			for (int i = 0; i < per && start + i < all.size(); i++) {
				Collection.Entry e = all.get(start + i);
				int cx = gx0 + (i % cols) * CELL, cy = py0 + 16 + (i / cols) * CELL;
				long n = counts.has(e.id()) ? counts.get(e.id()).getAsLong() : 0;
				int tier = got.has(e.id()) ? got.get(e.id()).getAsInt() : 0;
				g.fill(cx, cy, cx + 18, cy + 18, 0x30704A2A);
				if (!e.icon().isEmpty()) {
					g.renderItem(e.icon(), cx + 1, cy + 1);
				} else {
					g.drawCenteredString(font, "?", cx + 9, cy + 5, INK);
				}
				if (n <= 0) {
					// noch nicht entdeckt: nur ein Schatten
					g.nextStratum();
					g.fill(cx, cy, cx + 18, cy + 18, 0xD8402818);
				} else if (tier > 0) {
					// Rang-Ecke unten rechts
					g.nextStratum();
					int rc = Texts.rankColor(tier, tiers.size());
					g.fill(cx + 13, cy + 13, cx + 18, cy + 18, 0xFF2A1A10);
					g.fill(cx + 14, cy + 14, cx + 17, cy + 17, rc);
				}
				if (mouseX >= cx && mouseX < cx + 18 && mouseY >= cy && mouseY < cy + 18) {
					hover = e;
					g.fill(cx - 1, cy - 1, cx + 19, cy, 0xFFF2C14E);
					g.fill(cx - 1, cy + 18, cx + 19, cy + 19, 0xFFF2C14E);
				}
			}
			// Blätterpfeile unten
			if (side == 0 && spread > 0) {
				arrow(g, px0, py1 - 10, "◀", () -> spread--, mouseX, mouseY);
			}
			if (side == 1 && spread < spreads - 1) {
				arrow(g, px1 - 10, py1 - 10, "▶", () -> spread++, mouseX, mouseY);
			}
		}
		if (hover != null) {
			long n = counts.has(hover.id()) ? counts.get(hover.id()).getAsLong() : 0;
			int tier = got.has(hover.id()) ? got.get(hover.id()).getAsInt() : 0;
			List<Component> tip = new ArrayList<>();
			if (n <= 0) {
				tip.add(Component.translatable("loothub.lh.book.unknown"));
			} else {
				tip.add(Component.literal(hover.name()).withStyle(st -> st.withColor(0xFFF4D8)));
				tip.add(Component.translatable("loothub.lh.book.count", Fmt.whole(n)).withStyle(st -> st.withColor(0xB9A9AB)));
				tip.add(Texts.rank(tier, tiers.size()).copy().withStyle(st -> st.withColor(Texts.rankColor(tier, tiers.size()) & 0xFFFFFF)));
				if (tier < tiers.size()) {
					tip.add(Component.translatable("loothub.lh.book.next", Fmt.whole(tiers.get(tier).getAsLong()))
							.withStyle(st -> st.withColor(0x9A8A8C)));
				}
			}
			g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	private void arrow(GuiGraphics g, int x, int y, String s, Runnable r, int mx, int my) {
		boolean h = mx >= x - 2 && mx < x + 12 && my >= y - 2 && my < y + 12;
		g.drawString(font, s, x, y, h ? 0xFFB0302C : INK, false);
		area(x - 2, y - 2, 14, 14, r);
	}

	private void area(int x, int y, int w, int h, Runnable r) {
		clickAreas.add(new int[]{x, y, w, h});
		clicks.add(r);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < clickAreas.size(); i++) {
			int[] a = clickAreas.get(i);
			if (event.x() >= a[0] && event.x() < a[0] + a[2] && event.y() >= a[1] && event.y() < a[1] + a[3]) {
				clicks.get(i).run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		spread += sy < 0 ? 1 : sy > 0 ? -1 : 0;
		if (spread < 0) {
			spread = 0;
		}
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
