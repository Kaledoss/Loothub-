package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.cosmetics.AuraTextures;
import de.loothub.client.cosmetics.Cosmetics;
import de.loothub.client.cosmetics.SkinLookup;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Seite „Cosmetics“ mit Reitern: Begleiter (Mini-Mes, Flügel der Mini-Mes, Haustiere, Gesten), Körper (Aura, Flügel,
 * Augen, Krone), Effekte (Fußspuren, Flug-Spur, Aura-Stufen) und Teilen (Eintrag für {@code cosmetics.json}).
 * Jede Auswahl ist eine Auswahlliste.
 */
public class CosmeticsScreen extends LoothubScreen {
	private static final int CARD = 0xE01C1315;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int GREEN = 0xFF5BD68A;
	private static final int GAP = 6;
	private static final int TAB_H = 22;
	private static final int DD_ROW = 14;

	private enum Tab {
		COMPANIONS("loothub.cosmetics.tab.companions"),
		PETS("loothub.cosmetics.tab.pets"),
		BODY("loothub.cosmetics.tab.body"),
		EFFECTS("loothub.cosmetics.tab.effects");

		final String key;

		Tab(String key) {
			this.key = key;
		}
	}

	private static Tab tab = Tab.COMPANIONS;

	/** {@code divH}: so weit reicht die Trennlinie zwischen Titel und Beschreibung (breite Abschnitte nutzen darunter die volle Breite). */
	private record Section(int y, int h, String title, String desc, int divH) {
		Section(int y, int h, String title, String desc) {
			this(y, h, title, desc, h);
		}
	}

	/** Farbfeld neben einem „Farbe wählen“-Knopf. */
	private record Swatch(int x, int y, Supplier<String> value, Supplier<String> fallback, boolean label) {
		Swatch(int x, int y, Supplier<String> value, Supplier<String> fallback) {
			this(x, y, value, fallback, true);
		}
	}

	private final List<Section> sections = new ArrayList<>();
	private final List<Swatch> swatches = new ArrayList<>();
	private final int[][] skinStatus = new int[Cosmetics.MAX_MINI_MES][];
	private final List<Dropdown> dropdowns = new ArrayList<>();
	/** Kleine Flügel-Vorschau am Zeilenende (Stil + Farben, live). */
	private record RowPreview(int x, int y, int h, Supplier<String> style, Supplier<String> inner, Supplier<String> outer) {
	}

	private final List<RowPreview> rowPreviews = new ArrayList<>();
	private List<EditBox> nameBoxes = List.of();
	private long namesChangedAt;
	private Dropdown openDropdown;
	private int jsonY = -1;
	private int descX;

	public CosmeticsScreen() {
		super(Page.COSMETICS);
	}

	@Override
	protected void initPage() {
		sections.clear();
		swatches.clear();
		dropdowns.clear();
		rowPreviews.clear();
		nameBoxes = List.of();
		openDropdown = null;
		jsonY = -1;
		for (int i = 0; i < skinStatus.length; i++) {
			skinStatus[i] = null;
		}
		WidgetConfig c = WidgetConfig.get();
		int labelW = Math.min(130, cw / 3);
		descX = cl + labelW + 12;

		// Reiter
		int tabX = cl;
		int tabW = (cw - (Tab.values().length - 1) * 3) / Tab.values().length;
		for (Tab t : Tab.values()) {
			Button tb = Button.builder(Component.translatable(t.key), b -> {
				tab = t;
				rebuildWidgets();
			}).bounds(tabX, ct, tabW, 16).build();
			tb.active = t != tab;
			addRenderableWidget(tb);
			tabX += tabW + 3;
		}
		int y = ct + TAB_H;
		int lx = cl + 8, lw = labelW - 16;

		switch (tab) {
			case COMPANIONS -> {
				// Anzeige an/aus (gilt für alle Cosmetics)
				toggle(lx, y + 20, lw, () -> c.cosmetics, v -> c.cosmetics = v);
				sections.add(new Section(y, 42, "loothub.cosmetics.show", "loothub.cosmetics.show.desc"));
				y += 42 + GAP;

				// Mini-Mes: je Zeile ein Mini-Me mit Spielername, Flügeln und Flügelfarbe
				int x0 = cl + 8;
				int ddW = 110, colW = 46;
				int nameW = Math.max(70, Math.min(130, cw - 16 - 14 - ddW - 6 - 2 * (colW + 16) - 18));
				int x1 = x0 + nameW + 14, x2 = x1 + ddW + 6;
				List<EditBox> boxes = new ArrayList<>();
				for (int i = 0; i < Cosmetics.MAX_MINI_MES; i++) {
					final int slot = i;
					int by = y + 30 + i * 20;
					EditBox box = new EditBox(font, x0, by, nameW, 16, Component.translatable("loothub.settings.minime"));
					box.setMaxLength(16);
					box.setHint(Component.translatable("loothub.cosmetics.minime.slot" + (i + 1)).withStyle(ChatFormatting.DARK_GRAY));
					box.setValue(i < c.miniMes.size() && c.miniMes.get(i) != null ? c.miniMes.get(i) : "");
					box.setResponder(t -> namesChangedAt = System.currentTimeMillis()); // übernimmt kurz nach dem Tippen
					boxes.add(addRenderableWidget(box));
					skinStatus[i] = new int[]{x0 + nameW + 3, by + 4};
					dropdown(x1, by, ddW, Component.translatable("loothub.cosmetics.wings.short").getString(),
							WING_OPTIONS, "loothub.cosmetics.wings.style.", () -> slotValue(c.miniMeWings, slot),
							v -> c.miniMeWings = setSlot(c.miniMeWings, slot, v, Cosmetics.MAX_MINI_MES));
					// zwei Farben: innen (Flughaut/Federn) und außen (Knochen/Rand); ↺ = Originalfarben
					Supplier<String> style = () -> Cosmetics.wingAt(slotValue(c.miniMeWings, slot), 0);
					Supplier<String> inner = () -> slotValue(c.miniMeWingColor, slot);
					Supplier<String> outer = () -> slotValue(c.miniMeWingColorOuter, slot);
					int nx = colorPick(x2, by, colW, "loothub.cosmetics.color.inner", inner,
							v -> c.miniMeWingColor = setSlot(c.miniMeWingColor, slot, v, Cosmetics.MAX_MINI_MES), style, false, outer);
					nx = colorPick(nx, by, colW, "loothub.cosmetics.color.outer", outer,
							v -> c.miniMeWingColorOuter = setSlot(c.miniMeWingColorOuter, slot, v, Cosmetics.MAX_MINI_MES), style, true,
							inner);
					rowPreviews.add(new RowPreview(nx + 30, by - 1, 18, style, inner, outer));
					resetButton(nx, by, () -> {
						c.miniMeWingColor = setSlot(c.miniMeWingColor, slot, "", Cosmetics.MAX_MINI_MES);
						c.miniMeWingColorOuter = setSlot(c.miniMeWingColorOuter, slot, "", Cosmetics.MAX_MINI_MES);
					});
				}
				nameBoxes = boxes;
				int h = 30 + Cosmetics.MAX_MINI_MES * 20 + 2;
				sections.add(new Section(y, h, "loothub.settings.minime", "loothub.cosmetics.minime.rows.desc", 28));
				y += h + GAP;
			}
			case PETS -> {
				int dw = ddWidth();
				// Haustiere (2 Plätze)
				for (int k = 0; k < Cosmetics.MAX_PETS; k++) {
					final int slot = k;
					dropdown(descX + k * (dw + 6), y + 30, dw,
							Component.translatable(slot == 0 ? "loothub.cosmetics.pets.right" : "loothub.cosmetics.pets.left").getString(),
							PET_OPTIONS, "loothub.cosmetics.pets.type.", () -> slotValue(c.pets, slot),
							v -> c.pets = setSlot(c.pets, slot, v, Cosmetics.MAX_PETS));
				}
				sections.add(new Section(y, 54, "loothub.cosmetics.pets", "loothub.cosmetics.pets.desc"));
				y += 54 + GAP;

				// Gesten
				toggle(lx, y + 20, lw, () -> c.miniMeEmotes, v -> c.miniMeEmotes = v);
				sections.add(new Section(y, 42, "loothub.cosmetics.emotes", "loothub.cosmetics.emotes.desc"));
			}
			case BODY -> {
				// Flügel für mich
				dropdown(descX, y + 30, ddWidth(), Component.translatable("loothub.cosmetics.wings.me").getString(),
						WING_OPTIONS, "loothub.cosmetics.wings.style.", () -> Cosmetics.wingAt(c.wings, 0) == null ? "" : Cosmetics.wingAt(c.wings, 0),
						v -> c.wings = v);
				sections.add(new Section(y, 54, "loothub.cosmetics.wings", "loothub.cosmetics.wings.desc"));
				y += 54 + GAP;
				Supplier<String> myStyle = () -> Cosmetics.wingAt(c.wings, 0);
				int nx = colorPick(descX, y + 30, 70, "loothub.cosmetics.color.inner", () -> c.wingColor, v -> c.wingColor = v,
						myStyle, false, () -> c.wingColorOuter);
				nx = colorPick(nx + 6, y + 30, 70, "loothub.cosmetics.color.outer", () -> c.wingColorOuter, v -> c.wingColorOuter = v,
						myStyle, true, () -> c.wingColor);
				resetButton(nx + 6, y + 30, () -> {
					c.wingColor = "";
					c.wingColorOuter = "";
				});
				rowPreviews.add(new RowPreview(nx + 30, y + 22, 30, myStyle, () -> c.wingColor, () -> c.wingColorOuter));
				sections.add(new Section(y, 54, "loothub.cosmetics.wing_color", "loothub.cosmetics.wing_color.desc"));
				y += 54 + GAP;
				// Augen: an/aus + Farbe
				y = colorSection(y, lx, lw, "loothub.cosmetics.eyes", "loothub.cosmetics.eyes.desc",
						() -> c.eyeColor, v -> c.eyeColor = v, () -> c.eyeLast, v -> c.eyeLast = v, "#7FE8FF");
				// Krone
				toggle(lx, y + 20, lw, () -> c.crown, v -> c.crown = v);
				sections.add(new Section(y, 42, "loothub.cosmetics.crown", "loothub.cosmetics.crown.desc"));
			}
			case EFFECTS -> {
				dropdown(descX, y + 30, ddWidth(), Component.translatable("loothub.cosmetics.footsteps.label").getString(),
						STEP_OPTIONS, "loothub.cosmetics.footsteps.style.", () -> c.footsteps == null ? "" : c.footsteps,
						v -> c.footsteps = v);
				sections.add(new Section(y, 54, "loothub.cosmetics.footsteps", "loothub.cosmetics.footsteps.desc"));
				y += 54 + GAP;
			}
		}
	}

	@Override
	public void removed() {
		// noch nicht übernommene Namen beim Schließen speichern
		if (namesChangedAt != 0) {
			namesChangedAt = 0;
			WidgetConfig c = WidgetConfig.get();
			c.miniMes.clear();
			for (EditBox box : nameBoxes) {
				String v = box.getValue().strip();
				c.miniMes.add(v);
				if (!v.isEmpty()) {
					SkinLookup.forget(v);
				}
			}
			c.save();
		}
		super.removed();
	}

	private int ddWidth() {
		return Math.max(80, Math.min(120, (cr - 8 - descX - 2 * 6) / 3));
	}

	// ------------------------------------------------------------------ Bausteine

	private void toggle(int x, int y, int w, Supplier<Boolean> get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(onOff(get.get()), b -> {
			set.accept(!get.get());
			b.setMessage(onOff(get.get()));
			WidgetConfig.get().save();
		}).bounds(x, y, w, 16).build());
	}

	/** Abschnitt mit an/aus (merkt sich die letzte Farbe) und „Farbe wählen …“ samt Farbfeld. */
	private int colorSection(int y, int lx, int lw, String title, String desc, Supplier<String> get, Consumer<String> set,
			Supplier<String> getLast, Consumer<String> setLast, String fallback) {
		WidgetConfig c = WidgetConfig.get();
		Supplier<String> last = () -> getLast.get() == null || getLast.get().isBlank() ? fallback : getLast.get();
		boolean on = get.get() != null && !get.get().isBlank();
		addRenderableWidget(Button.builder(onOff(on), b -> {
			set.accept(get.get() == null || get.get().isBlank() ? last.get() : "");
			c.save();
			rebuildWidgets();
		}).bounds(lx, y + 20, lw, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.cosmetics.aura.pick"), b ->
				minecraft.setScreen(new ColorPickerScreen(this, Component.translatable(title), AuraTextures.parse(last.get()),
						rgb -> {
							String hex = String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
							set.accept(hex);
							setLast.accept(hex);
							c.save();
						})))
				.bounds(descX, y + 30, 120, 16).build());
		swatches.add(new Swatch(descX + 126, y + 33, get, last));
		sections.add(new Section(y, 54, title, desc));
		return y + 54 + GAP;
	}

	/** Knopf „Innen/Außen …“ öffnet den Farbwähler, daneben ein Farbfeld. Gibt die x-Position dahinter zurück. */
	private int colorPick(int x, int y, int w, String key, Supplier<String> get, Consumer<String> set, Supplier<String> style,
			boolean outerPart, Supplier<String> other) {
		addRenderableWidget(Button.builder(Component.translatable(key), b ->
				minecraft.setScreen(new ColorPickerScreen(this, Component.translatable("loothub.cosmetics.wing_color"),
						AuraTextures.parse(get.get() == null || get.get().isBlank() ? "#8A2BE2" : get.get()), rgb -> {
							set.accept(String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF));
							WidgetConfig.get().save();
						}).withPreview((g, px, py, pw, ph, rgb) -> {
							// Flügel live in der gerade gewählten Farbe (ohne Stil: Drache als Beispiel)
							String st = style.get() == null || style.get().isBlank() ? "dragon" : style.get();
							String hex = String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
							int hh = Math.min(ph - 10, (int) (pw / 2 * 128 / 66.0F) - 4);
							de.loothub.client.cosmetics.WingPreview.draw(g, px + pw / 2, py + (ph - hh) / 2, hh, st,
									outerPart ? other.get() : hex, outerPart ? hex : other.get());
						})))
				.bounds(x, y, w, 16).build());
		swatches.add(new Swatch(x + w + 3, y + 3, get, () -> "#8A2BE2", false));
		return x + w + 16;
	}

	private void resetButton(int x, int y, Runnable reset) {
		Button b = Button.builder(Component.literal("↺"), btn -> {
			reset.run();
			WidgetConfig.get().save();
		}).bounds(x, y, 16, 16).build();
		b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.cosmetics.color.reset")));
		addRenderableWidget(b);
	}

	private static Component onOff(boolean on) {
		return Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
	}

	/** Wert auf Platz {@code slot} aus „a,b,,c“ ("" wenn leer). */
	private static String slotValue(String raw, int slot) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		if (raw.indexOf(',') < 0) {
			return raw.strip(); // alte Form: ein Wert für alle
		}
		String[] parts = raw.split(",", -1);
		return slot < parts.length ? parts[slot].strip() : "";
	}

	private static String setSlot(String raw, int slot, String value, int slots) {
		String[] all = new String[slots];
		for (int i = 0; i < slots; i++) {
			all[i] = i == slot ? value : slotValue(raw, i);
		}
		return String.join(",", all); // immer mit Kommas → je Platz einzeln
	}

	// ------------------------------------------------------------------ Auswahllisten

	private static final String[] WING_OPTIONS = {"", "dragon", "devil", "angel"};
	private static final String[] PET_OPTIONS = {"", "slime", "allay", "bee", "vex"};
	private static final String[] STEP_OPTIONS = {"", "sparks", "flames"};

	/** Knopf „Präfix: Wahl ▾“, Klick klappt die Optionen darunter auf. */
	private static final class Dropdown {
		final String prefix;
		final String[] options;
		final String keyPrefix;
		final Supplier<String> get;
		final Consumer<String> set;
		Button button;

		Dropdown(String prefix, String[] options, String keyPrefix, Supplier<String> get, Consumer<String> set) {
			this.prefix = prefix;
			this.options = options;
			this.keyPrefix = keyPrefix;
			this.get = get;
			this.set = set;
		}

		Component name(String option) {
			return Component.translatable(keyPrefix + (option == null || option.isEmpty() ? "off" : option));
		}

		Component label() {
			return Component.literal(prefix + ": ").append(name(get.get())).append(" ▾");
		}
	}

	private void dropdown(int x, int y, int w, String prefix, String[] options, String keyPrefix, Supplier<String> get,
			Consumer<String> set) {
		Dropdown d = new Dropdown(prefix, options, keyPrefix, get, v -> {
			set.accept(v);
			WidgetConfig.get().save();
		});
		d.button = addRenderableWidget(Button.builder(d.label(), b -> openDropdown = openDropdown == d ? null : d)
				.bounds(x, y, w, 16).build());
		dropdowns.add(d);
	}

	private int optionAt(double mx, double my) {
		if (openDropdown == null) {
			return -1;
		}
		Button b = openDropdown.button;
		int y = b.getY() + b.getHeight() + 1;
		if (mx < b.getX() || mx >= b.getX() + b.getWidth() || my < y || my >= y + openDropdown.options.length * DD_ROW) {
			return -1;
		}
		return (int) ((my - y) / DD_ROW);
	}

	private void drawDropdown(GuiGraphics g, int mx, int my) {
		if (openDropdown == null) {
			return;
		}
		g.nextStratum();
		Dropdown d = openDropdown;
		Button b = d.button;
		int x = b.getX(), y = b.getY() + b.getHeight() + 1, w = b.getWidth();
		int n = d.options.length;
		g.fill(x - 1, y - 1, x + w + 1, y + n * DD_ROW + 1, RED);
		g.fill(x, y, x + w, y + n * DD_ROW, 0xF0140B0D);
		int hover = optionAt(mx, my);
		String current = d.get.get();
		for (int i = 0; i < n; i++) {
			int ry = y + i * DD_ROW;
			if (i == hover) {
				g.fill(x, ry, x + w, ry + DD_ROW, 0x40E23B3B);
			}
			boolean selected = d.options[i].equals(current);
			g.drawString(font, (selected ? "✔ " : "   ") + d.name(d.options[i]).getString(), x + 4, ry + 3,
					selected ? TEXT : MUTED, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (openDropdown != null) {
			int i = optionAt(event.x(), event.y());
			Dropdown d = openDropdown;
			if (i >= 0) {
				d.set.accept(d.options[i]);
				d.button.setMessage(d.label());
				openDropdown = null;
				return true;
			}
			if (!d.button.isMouseOver(event.x(), event.y())) {
				openDropdown = null;
				for (Dropdown other : dropdowns) {
					if (other.button.isMouseOver(event.x(), event.y())) {
						return super.mouseClicked(event, doubleClick); // gleich die andere Liste öffnen
					}
				}
				return true; // Klick daneben schließt nur
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	// ------------------------------------------------------------------ Teilen

	/** Eintrag für cosmetics.json mit den eigenen Einstellungen. */
	private String jsonLine() {
		WidgetConfig c = WidgetConfig.get();
		String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "DeinName";
		List<String> parts = new ArrayList<>();
		int last = -1;
		for (int i = 0; i < c.miniMes.size(); i++) {
			if (c.miniMes.get(i) != null && !c.miniMes.get(i).isBlank()) {
				last = i;
			}
		}
		if (last >= 0) {
			List<String> q = new ArrayList<>();
			for (int i = 0; i <= last; i++) {
				String s = c.miniMes.get(i) == null ? "" : c.miniMes.get(i).strip();
				q.add(s.isEmpty() ? "null" : "\"" + (s.equalsIgnoreCase(me) ? "" : s) + "\"");
			}
			parts.add("\"minime\": {\"" + me + "\": [" + String.join(", ", q) + "]}");
		}
		single(parts, Cosmetics.AURA, me, c.auraColor);
		single(parts, Cosmetics.WINGS, me, c.wings);
		perSlot(parts, Cosmetics.MINI_ME_WINGS, me, c.miniMeWings, Cosmetics.MAX_MINI_MES);
		single(parts, Cosmetics.EYES, me, c.eyeColor);
		single(parts, Cosmetics.FOOTSTEPS, me, c.footsteps);
		perSlot(parts, Cosmetics.PETS, me, c.pets, Cosmetics.MAX_PETS);
		flag(parts, Cosmetics.CROWN, me, c.crown);
		flag(parts, Cosmetics.FLY_TRAIL, me, c.flyTrail);
		flag(parts, Cosmetics.EMOTES, me, c.miniMeEmotes);
		return parts.isEmpty() ? "–" : String.join(", ", parts);
	}

	private static void single(List<String> parts, String key, String me, String value) {
		if (value != null && !value.isBlank()) {
			parts.add("\"" + key + "\": {\"" + me + "\": \"" + value + "\"}");
		}
	}

	private static void flag(List<String> parts, String key, String me, boolean on) {
		if (on) {
			parts.add("\"" + key + "\": [\"" + me + "\"]");
		}
	}

	private static void perSlot(List<String> parts, String key, String me, String raw, int slots) {
		List<String> list = new ArrayList<>();
		int lastSet = -1;
		for (int i = 0; i < slots; i++) {
			String v = slotValue(raw, i);
			list.add(v.isEmpty() ? "null" : "\"" + v + "\"");
			if (!v.isEmpty()) {
				lastSet = i;
			}
		}
		if (lastSet >= 0) {
			parts.add("\"" + key + "\": {\"" + me + "\": [" + String.join(", ", list.subList(0, lastSet + 1)) + "]}");
		}
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.renderBackground(g, mouseX, mouseY, delta);
		int labelW = descX - 12 - cl;
		for (Section s : sections) {
			g.fill(cl, s.y(), cl + cw, s.y() + s.h(), Theme.bg(CARD));
			g.fill(cl, s.y() + s.h() - 1, cl + cw, s.y() + s.h(), Theme.bg(CARD_EDGE));
			g.fill(cl + labelW + 6, s.y() + 5, cl + labelW + 7, s.y() + Math.min(s.h(), s.divH()) - 5, Theme.bg(CARD_EDGE));
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int labelW = descX - 12 - cl;
		for (Section s : sections) {
			Component title = Component.translatable(s.title());
			int ty0 = s.y() + 6;
			for (FormattedCharSequence line : font.split(title, labelW - 8)) {
				g.drawString(font, line, cl + 8 + (labelW - 16 - font.width(line)) / 2, ty0, TEXT, false);
				ty0 += 10;
			}
			int ty = s.y() + 6;
			for (FormattedCharSequence line : font.split(Component.translatable(s.desc()), cr - 8 - descX)) {
				g.drawString(font, line, descX, ty, MUTED, false);
				ty += 10;
				if (ty > s.y() + 24) {
					break; // Platz für die Bedienelemente darunter
				}
			}
		}
		WidgetConfig c = WidgetConfig.get();
		// Status je Mini-Me
		for (int i = 0; i < skinStatus.length && i < c.miniMes.size(); i++) {
			if (skinStatus[i] == null || c.miniMes.get(i) == null || c.miniMes.get(i).isBlank()) {
				continue;
			}
			SkinLookup.State st = SkinLookup.state(c.miniMes.get(i));
			String mark = st == SkinLookup.State.READY ? "✔" : st == SkinLookup.State.NOT_FOUND ? "✖" : "…";
			int color = st == SkinLookup.State.READY ? GREEN : st == SkinLookup.State.NOT_FOUND ? RED : FAINT;
			g.drawString(font, mark, skinStatus[i][0], skinStatus[i][1], color, false);
		}
		// Farbfelder
		for (Swatch sw : swatches) {
			String v = sw.value().get();
			boolean on = v != null && !v.isBlank();
			int rgb = AuraTextures.parse(on ? v : sw.fallback().get());
			g.fill(sw.x() - 1, sw.y() - 1, sw.x() + 11, sw.y() + 11, 0xFF000000);
			g.fill(sw.x(), sw.y(), sw.x() + 10, sw.y() + 10, 0xFF000000 | rgb);
			if (sw.label()) {
				g.drawString(font, String.format(Locale.ROOT, "#%06X", rgb)
						+ (on ? "" : " · " + Component.translatable("loothub.settings.off").getString()), sw.x() + 16, sw.y() + 1,
						on ? TEXT : FAINT, false);
			} else if (!on) {
				// Originalfarben: Feld durchgestrichen
				g.fill(sw.x(), sw.y() + 4, sw.x() + 10, sw.y() + 6, 0xFF000000);
			}
		}
		// fertiger Eintrag für cosmetics.json
		if (jsonY >= 0) {
			int y = jsonY;
			for (FormattedCharSequence line : font.split(Component.literal(jsonLine()), cr - 8 - descX)) {
				g.drawString(font, line, descX, y, 0xFFFFD66B, false);
				y += 10;
			}
		}
		// Flügel-Vorschau je Zeile
		for (RowPreview rp : rowPreviews) {
			if (rp.x() + rp.h() < cr) {
				de.loothub.client.cosmetics.WingPreview.draw(g, rp.x(), rp.y(), rp.h(), rp.style().get(), rp.inner().get(),
						rp.outer().get());
			}
		}
		// Mini-Me-Namen: kurz nach dem letzten Tastendruck übernehmen (kein „Übernehmen“ nötig)
		if (namesChangedAt != 0 && System.currentTimeMillis() - namesChangedAt > 700) {
			namesChangedAt = 0;
			c.miniMes.clear();
			for (EditBox box : nameBoxes) {
				String v = box.getValue().strip();
				c.miniMes.add(v); // Platz bleibt erhalten, leer = frei
				if (!v.isEmpty()) {
					SkinLookup.forget(v);
				}
			}
			c.save();
		}
		drawDropdown(g, mouseX, mouseY);
	}
}
