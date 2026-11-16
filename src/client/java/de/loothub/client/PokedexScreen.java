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

import de.loothub.client.jobs.WidgetConfig;
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
	/** Kopfleiste mit den Twitch-Einstellungen (Kanal, Name, Token, Verbinden, Anzeige). */
	private int headTop, headBottom, fieldW;
	private List<Row> rows = List.of();
	private int seenDataSize = -1;
	private int seenCaught = -1;

	public PokedexScreen() {
		super(Page.POKEDEX);
	}

	@Override
	protected void initPage() {
		PokedexData.ensureLoaded();
		// ---- Kopfleiste: Pokémon Community Game (der An/Aus-Schalter bleibt in den Einstellungen unter Extras)
		WidgetConfig c = WidgetConfig.get();
		headTop = ct;
		fieldW = (cw - 16) / 2;
		int fx = cl + 6, fy = headTop + 16;
		EditBox channel = new EditBox(font, fx, fy, fieldW, 16, Component.translatable("loothub.settings.pcg.channel"));
		channel.setMaxLength(40);
		channel.setValue(c.pcgChannel == null ? "" : c.pcgChannel);
		channel.setResponder(t -> c.pcgChannel = t.strip());
		addRenderableWidget(channel);
		EditBox user = new EditBox(font, fx + fieldW + 4, fy, fieldW, 16, Component.translatable("loothub.settings.pcg.user"));
		user.setMaxLength(40);
		user.setValue(c.pcgUser == null ? "" : c.pcgUser);
		user.setResponder(t -> c.pcgUser = t.strip());
		addRenderableWidget(user);
		int bw = (2 * fieldW + 4 - 8) / 3, by = fy + 20;
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
				Component.translatable("loothub.settings.pcg.token"), b -> {
					java.nio.file.Path file = de.loothub.client.pcg.TwitchChat.tokenFile();
					try {
						if (!java.nio.file.Files.exists(file)) {
							java.nio.file.Files.writeString(file, "");
						}
					} catch (java.io.IOException ignored) {
						// Ordner wird trotzdem geöffnet
					}
					SettingsScreen.openFolder(file.getParent());
				}).bounds(fx, by, bw, 16).build());
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
				Component.translatable("loothub.settings.pcg.connect"), b -> {
					WidgetConfig.get().save();
					de.loothub.client.pcg.TwitchChat.stop();
					de.loothub.client.pcg.TwitchChat.ensure(c.pcgEnabled, c.pcgChannel, c.pcgUser);
				}).bounds(fx + bw + 4, by, bw, 16).build());
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
				Component.translatable("loothub.settings.pcg.move"),
				b -> minecraft.setScreen(new de.loothub.client.pcg.PcgOverlayEditScreen(this))).bounds(fx + 2 * (bw + 4), by, bw, 16).build());
		headBottom = by + 22;

		EditBox box = new EditBox(font, cl, headBottom + 6, Math.min(260, cw), 16, Component.translatable("loothub.screen.search"));
		box.setHint(Component.translatable("loothub.pokedex.search").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(t -> {
			search = t;
			scroll = 0;
			refresh();
		});
		addRenderableWidget(box);
		listTop = headBottom + 28;
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
		// Kopfleiste: Rahmen, Beschriftungen, Verbindungsstatus
		g.fill(cl, headTop, cr, headBottom, 0x18FFFFFF);
		g.fill(cl, headBottom, cr, headBottom + 1, 0x40E23B3B);
		g.drawString(font, Component.translatable("loothub.settings.pcg.channel"), cl + 6, headTop + 5, MUTED, false);
		g.drawString(font, Component.translatable("loothub.settings.pcg.user"), cl + 6 + fieldW + 4, headTop + 5, MUTED, false);
		WidgetConfig cfg = WidgetConfig.get();
		Component status = cfg.pcgEnabled ? Component.translatable("loothub.settings.pcg.status."
				+ de.loothub.client.pcg.TwitchChat.status().name().toLowerCase(Locale.ROOT))
				: Component.translatable("loothub.pokedex.pcg_off");
		g.drawString(font, status, cr - 6 - font.width(status), headTop + 5, cfg.pcgEnabled ? GREEN : FAINT, false);
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
	public void removed() {
		super.removed();
		// Eingaben übernehmen und (falls an) mit dem neuen Kanal verbinden
		WidgetConfig.get().save();
		WidgetConfig c = WidgetConfig.get();
		de.loothub.client.pcg.TwitchChat.ensure(c.pcgEnabled, c.pcgChannel, c.pcgUser);
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
