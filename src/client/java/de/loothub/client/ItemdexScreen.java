package de.loothub.client;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.itemdex.Itemdex;
import de.loothub.client.itemdex.Itemdex.Category;
import de.loothub.client.itemdex.Itemdex.Entry;

/**
 * Seite „Itemdex“: nummerierte Liste (A–Z) aller Server-eigenen Items, die bisher gesehen wurden.
 * Suche (Name, Beschreibung, Nummer), Filter nach Art, Rechtsklick blendet einen Eintrag aus.
 */
public class ItemdexScreen extends LoothubScreen {
	private static final int ROW_H = 20;

	private static String search = "";
	private static Category category; // null = alle
	private static boolean showHidden;

	private Button categoryButton;
	private Button hiddenButton;
	private List<Entry> rows = List.of();
	private final Map<Entry, Integer> numbers = new IdentityHashMap<>();
	private int seenVersion = -1;
	private int scroll;
	private int listTop;

	public ItemdexScreen() {
		super(Page.ITEMDEX);
	}

	@Override
	protected void initPage() {
		int hiddenW = 110;
		int catW = 120;
		int boxW = Math.max(80, Math.min(260, cw - catW - hiddenW - 12));
		EditBox box = new EditBox(font, cl, ct, boxW, 16, Component.translatable("loothub.screen.search"));
		box.setHint(Component.translatable("loothub.itemdex.search").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(t -> {
			search = t;
			scroll = 0;
			refresh();
		});
		addRenderableWidget(box);
		categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> {
			Category[] all = Category.values();
			category = category == null ? all[0] : (category.ordinal() + 1 < all.length ? all[category.ordinal() + 1] : null);
			b.setMessage(categoryLabel());
			scroll = 0;
			refresh();
		}).bounds(cr - catW - hiddenW - 6, ct, catW, 16).build());
		hiddenButton = addRenderableWidget(Button.builder(hiddenLabel(), b -> {
			showHidden = !showHidden;
			b.setMessage(hiddenLabel());
			scroll = 0;
			refresh();
		}).bounds(cr - hiddenW, ct, hiddenW, 16).build());
		listTop = ct + 22;
		refresh();
	}

	private static Component categoryLabel() {
		return Component.translatable("loothub.itemdex.category",
				category == null ? Component.translatable("loothub.itemdex.cat.all") : Component.translatable(category.key));
	}

	private static Component hiddenLabel() {
		return Component.translatable(showHidden ? "loothub.itemdex.hidden.on" : "loothub.itemdex.hidden.off");
	}

	private void refresh() {
		seenVersion = Itemdex.version();
		String q = search.strip().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
		// A–Z sortiert; Nummern nur für eingeblendete Items, lückenlos und unabhängig von Suche/Filter
		List<Entry> all = new ArrayList<>(Itemdex.entries());
		Collator collator = Collator.getInstance(Locale.GERMANY);
		collator.setStrength(Collator.PRIMARY);
		all.sort(Comparator.comparing((Entry e) -> e.name, collator).thenComparing(e -> e.itemId));
		numbers.clear();
		int n = 0;
		for (Entry e : all) {
			if (!e.hidden) {
				numbers.put(e, ++n);
			}
		}
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) {
			if (e.hidden && !showHidden) {
				continue; // ausgeblendete nur zeigen, wenn eingeschaltet
			}
			if (category != null && e.category != category) {
				continue;
			}
			if (!q.isEmpty() && !e.text.contains(q) && !e.itemId.contains(q)
					&& !String.valueOf(numbers.getOrDefault(e, -1)).equals(q.replaceFirst("^0+(?=\\d)", ""))) {
				continue;
			}
			out.add(e);
		}
		rows = out;
	}

	@Override
	protected Component headerRight() {
		return Component.translatable("loothub.itemdex.count", numbers.size());
	}

	@Override
	public void tick() {
		super.tick();
		if (Itemdex.version() != seenVersion) {
			refresh();
		}
	}

	private Entry rowAt(double mx, double my) {
		if (mx < cl || mx >= cr || my < listTop || my >= cb) {
			return null;
		}
		int i = (int) ((my - listTop) / ROW_H) + scroll;
		return i >= 0 && i < rows.size() ? rows.get(i) : null;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (rows.isEmpty()) {
			boolean filtered = !search.isBlank() || category != null || showHidden;
			Component msg = Component.translatable(filtered ? "loothub.screen.no_results" : "loothub.itemdex.empty");
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, listTop + 20, FAINT, false);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
		Entry hovered = rowAt(mouseX, mouseY);
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i <= visible && i + scroll < rows.size(); i++) {
			Entry e = rows.get(i + scroll);
			int y = listTop + i * ROW_H;
			g.fill(cl, y, cr, y + ROW_H - 1, e == hovered ? 0x30E23B3B : (i % 2 == 0 ? 0x14FFFFFF : 0x08FFFFFF));
			Integer number = numbers.get(e);
			g.drawString(font, number == null ? "  –" : String.format(Locale.ROOT, "#%04d", number), cl + 4, y + 6, MUTED, false);
			ItemStack stack = e.stack();
			g.renderItem(stack, cl + 40, y + 2);
			Component name = stack.isEmpty() ? Component.literal(e.name) : stack.getHoverName();
			int nameX = cl + 60;
			String right = Fmt.date(e.firstSeen, false) + " · " + e.where;
			String cat = Component.translatable(e.category.key).getString();
			int rightW = font.width(right);
			int catX = cr - 6 - rightW - 12 - font.width(cat);
			int maxName = Math.max(30, catX - nameX - 8);
			FormattedCharSequence clipped = font.split(name, maxName).isEmpty() ? FormattedCharSequence.EMPTY
					: font.split(name, maxName).get(0);
			g.drawString(font, clipped, nameX, y + 6, e.hidden ? FAINT : TEXT, false);
			g.drawString(font, cat, catX, y + 6, FAINT, false);
			g.drawString(font, right, cr - 6 - rightW, y + 6, FAINT, false);
		}
		g.disableScissor();

		if (hovered != null) {
			List<FormattedCharSequence> tip = new ArrayList<>();
			ItemStack stack = hovered.stack();
			if (!stack.isEmpty()) {
				for (Component line : Screen.getTooltipFromItem(minecraft, stack)) {
					tip.add(line.getVisualOrderText());
				}
			} else {
				tip.add(Component.literal(hovered.name).getVisualOrderText());
			}
			tip.add(FormattedCharSequence.EMPTY);
			tip.add(Component.translatable("loothub.itemdex.first_seen", Fmt.date(hovered.firstSeen, true), hovered.where)
					.withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			tip.add(Component.translatable(hovered.hidden ? "loothub.itemdex.unhide_hint" : "loothub.itemdex.hide_hint")
					.withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 1) {
			Entry e = rowAt(event.x(), event.y());
			if (e != null) {
				Itemdex.setHidden(e, !e.hidden);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
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
