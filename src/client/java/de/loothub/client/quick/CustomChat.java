package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Eigener Chat: frei verschiebbare und skalierbare Fenster mit Tabs (eigene Regeln je Tab), Farben, Textgröße und
 * Ausblendzeit. Gezeichnet wird über Minecrafts eigene Chat-Schnittstelle – Links, Hover-Texte, Klicks und Shift-Klick
 * funktionieren dadurch wie im normalen Chat. Senden bleibt unverändert (normale Eingabezeile).
 */
public final class CustomChat {
	/** Ein Chat-Fenster (gespeichert). Position: Abstand links und unten in GUI-Pixeln. */
	public static final class Window {
		public int left = 2;
		public int bottom = 40;
		public int w = 320;
		public int h = 160;
		public int bg = 0x000000;
		public int opacity = 50;
		public int accent = 0xE23B3B;
		public int textScale = 100;
		public int fadeSec = 10;
		public List<Tab> tabs = new ArrayList<>();
		public int active = 0;

		Tab tab() {
			if (tabs.isEmpty()) {
				tabs.add(new Tab());
			}
			active = Mth.clamp(active, 0, tabs.size() - 1);
			return tabs.get(active);
		}
	}

	/** Ein Tab mit Regeln: enthält eines der Wörter (leer = alles), enthält keines der Ausschluss-Wörter, nur Privat. */
	public static final class Tab {
		public String name = "Alle";
		public String include = "";
		public String exclude = "";
		public boolean privateOnly = false;

		boolean matches(Msg m) {
			if (privateOnly && !m.pm) {
				return false;
			}
			if (any(exclude, m.low)) {
				return false;
			}
			return include == null || include.isBlank() || any(include, m.low);
		}

		private static boolean any(String words, String low) {
			if (words == null || words.isBlank()) {
				return false;
			}
			for (String w : words.split(",")) {
				String t = w.strip().toLowerCase(Locale.ROOT);
				if (!t.isEmpty() && low.contains(t)) {
					return true;
				}
			}
			return false;
		}
	}

	record Msg(long time, Component text, String low, boolean pm) {
	}

	private record Line(FormattedCharSequence seq, long time) {
	}

	private static final class Cache {
		int version = -1, width = -1;
		List<Line> lines = List.of();
	}

	private static final int MAX = 1000;
	private static final int HEADER = 12;
	private static final int BTN = 11;
	private static final List<Msg> MESSAGES = new ArrayList<>();
	private static int version;
	private static final Map<Tab, Cache> CACHE = new IdentityHashMap<>();
	private static final Map<Tab, Integer> UNREAD = new IdentityHashMap<>();
	private static final Map<Window, Integer> SCROLL = new IdentityHashMap<>();

	// Ziehen
	private static Window dragWin;
	private static boolean resizing;
	private static double grabX, grabY;
	private static int startW, startH, startLeft, startBottom;

	private CustomChat() {
	}

	public static boolean active() {
		return WidgetConfig.get().customChat;
	}

	public static List<Window> windows() {
		WidgetConfig c = WidgetConfig.get();
		if (c.chatWindows == null) {
			c.chatWindows = new ArrayList<>();
		}
		if (c.chatWindows.isEmpty()) {
			Window w = new Window();
			w.tabs.add(new Tab());
			c.chatWindows.add(w);
		}
		return c.chatWindows;
	}

	// ------------------------------------------------------------------ Nachrichten

	/** Fertige Chat-Nachricht (mit Uhrzeit usw.) übernehmen. */
	public static synchronized void add(Component message) {
		String plain = message.getString();
		Msg m = new Msg(System.currentTimeMillis(), message, plain.toLowerCase(Locale.ROOT), ChatLog.isPrivate(plain));
		MESSAGES.add(m);
		if (MESSAGES.size() > MAX) {
			MESSAGES.remove(0);
		}
		version++;
		if (!active()) {
			return;
		}
		for (Window w : windows()) {
			Tab current = w.tab();
			for (Tab t : w.tabs) {
				if (t != current && t.matches(m)) {
					UNREAD.merge(t, 1, Integer::sum);
				}
			}
			Integer s = SCROLL.get(w);
			if (s != null && s > 0 && current.matches(m)) {
				SCROLL.put(w, s + 1); // beim Zurückscrollen bleibt die Ansicht stehen
			}
		}
	}

	private static synchronized List<Line> lines(Tab tab, int width) {
		Cache c = CACHE.computeIfAbsent(tab, k -> new Cache());
		if (c.version != version || c.width != width) {
			Font font = Minecraft.getInstance().font;
			List<Line> out = new ArrayList<>();
			for (Msg m : MESSAGES) {
				if (tab.matches(m)) {
					for (FormattedCharSequence s : font.split(m.text(), Math.max(20, width))) {
						out.add(new Line(s, m.time()));
					}
				}
			}
			c.lines = out;
			c.version = version;
			c.width = width;
		}
		return c.lines;
	}

	/** Nach Regel-Änderungen neu aufbauen. */
	public static synchronized void invalidate() {
		CACHE.clear();
		UNREAD.clear();
	}

	// ------------------------------------------------------------------ Geometrie

	private static int sw() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth();
	}

	private static int sh() {
		return Minecraft.getInstance().getWindow().getGuiScaledHeight();
	}

	/** {links, oben, breite, höhe} des Nachrichtenbereichs (Kopfzeile liegt darüber). */
	static int[] box(Window w) {
		int sw = sw(), sh = sh();
		w.w = Mth.clamp(w.w, 80, Math.max(80, sw - 4));
		w.h = Mth.clamp(w.h, 30, Math.max(30, sh - HEADER - 20));
		int left = Mth.clamp(w.left, 0, Math.max(0, sw - w.w));
		int bottom = Mth.clamp(w.bottom, 0, Math.max(0, sh - w.h - HEADER));
		return new int[]{left, sh - bottom - w.h, w.w, w.h};
	}

	private static float scale(Window w) {
		return Mth.clamp(w.textScale, 50, 200) / 100F;
	}

	// ------------------------------------------------------------------ Zeichnen

	/** Ersetzt das Zeichnen des normalen Chats (auch für Hover und Klick-Erkennung). */
	public static void render(ChatComponent.ChatGraphicsAccess a, boolean focused) {
		if (Minecraft.getInstance().options.chatVisibility().get() == net.minecraft.world.entity.player.ChatVisiblity.HIDDEN) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Window w : windows()) {
			int[] b = box(w);
			float s = scale(w);
			int lineH = Math.max(1, Math.round(9 * s));
			int bgA = Mth.clamp(w.opacity, 0, 100) * 255 / 100;
			Tab tab = w.tab();
			List<Line> lines = lines(tab, (int) ((b[2] - 6) / s));
			int visible = Math.max(1, (b[3] - 2) / lineH);
			int scroll = SCROLL.getOrDefault(w, 0);
			scroll = Mth.clamp(scroll, 0, Math.max(0, lines.size() - visible));
			SCROLL.put(w, scroll);

			if (focused) {
				a.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], (bgA << 24) | (w.bg & 0xFFFFFF));
				header(a, w, b);
				// Bildlaufleiste
				if (lines.size() > visible) {
					int barH = Math.max(6, b[3] * visible / lines.size());
					int barY = b[1] + b[3] - barH - (b[3] - barH) * scroll / Math.max(1, lines.size() - visible);
					a.fill(b[0] + b[2] - 2, barY, b[0] + b[2], barY + barH, 0xC0000000 | (w.accent & 0xFFFFFF));
				}
			}
			// Nachrichten von unten nach oben
			for (int i = 0; i < visible; i++) {
				int idx = lines.size() - 1 - scroll - i;
				if (idx < 0) {
					break;
				}
				Line l = lines.get(idx);
				float alpha = 1F;
				if (!focused) {
					long age = now - l.time();
					long life = Math.max(1, w.fadeSec) * 1000L;
					if (age >= life) {
						break;
					}
					alpha = Mth.clamp((life - age) / 1000F, 0F, 1F);
					int la = (int) (bgA * alpha);
					a.fill(b[0], b[1] + b[3] - (i + 1) * lineH, b[0] + b[2], b[1] + b[3] - i * lineH, (la << 24) | (w.bg & 0xFFFFFF));
				}
				int y = b[1] + b[3] - (i + 1) * lineH;
				text(a, b[0] + 3, y + Math.max(0, (lineH - Math.round(8 * s)) / 2), s, alpha, l.seq());
			}
		}
	}

	private static void text(ChatComponent.ChatGraphicsAccess a, float x, float y, float s, float alpha, FormattedCharSequence seq) {
		a.updatePose(m -> {
			m.translate(x, y);
			m.scale(s, s);
		});
		a.handleMessage(0, alpha, seq);
		a.updatePose(m -> {
			m.scale(1 / s, 1 / s);
			m.translate(-x, -y);
		});
	}

	private static FormattedCharSequence label(String s, int rgb) {
		return Component.literal(s).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb & 0xFFFFFF))).getVisualOrderText();
	}

	/** Kopfzeile mit Tabs, „+“, Einstellungen, neues Fenster, schließen und Größen-Griff. */
	private static void header(ChatComponent.ChatGraphicsAccess a, Window w, int[] b) {
		int top = b[1] - HEADER;
		int accent = 0xFF000000 | (w.accent & 0xFFFFFF);
		a.fill(b[0], top, b[0] + b[2], b[1], 0xE0100A0C);
		a.fill(b[0], b[1] - 1, b[0] + b[2], b[1], accent);
		for (Hit h : hits(w, b)) {
			switch (h.kind) {
				case TAB -> {
					Tab t = w.tabs.get(h.index);
					boolean sel = h.index == w.active;
					if (sel) {
						a.fill(h.x, top, h.x + h.w, b[1] - 1, 0x60000000 | (w.accent & 0xFFFFFF));
					}
					text(a, h.x + 4, top + 2, 1F, 1F, label(tabLabel(t), sel ? 0xFFFFFF : 0xB9A9AB));
				}
				case ADD_TAB -> text(a, h.x + 3, top + 2, 1F, 1F, label("+", 0x55E06A));
				case SETTINGS -> text(a, h.x + 2, top + 2, 1F, 1F, label("⚙", 0xD0D0D0));
				case NEW_WINDOW -> text(a, h.x + 2, top + 2, 1F, 1F, label("❐", 0xD0D0D0));
				case CLOSE -> text(a, h.x + 2, top + 2, 1F, 1F, label("✕", 0xFF6666));
				case RESIZE -> {
					a.fill(h.x + h.w - 6, top, h.x + h.w, top + 2, accent);
					a.fill(h.x + h.w - 2, top, h.x + h.w, top + 6, accent);
				}
				default -> {
				}
			}
		}
	}

	private static String tabLabel(Tab t) {
		int n = UNREAD.getOrDefault(t, 0);
		return n > 0 ? t.name + " (" + (n > 99 ? "99+" : n) + ")" : t.name;
	}

	// ------------------------------------------------------------------ Klickflächen

	private enum Kind {
		TAB, ADD_TAB, SETTINGS, NEW_WINDOW, CLOSE, RESIZE, MOVE
	}

	private record Hit(Kind kind, int index, int x, int w) {
	}

	private static List<Hit> hits(Window w, int[] b) {
		Font font = Minecraft.getInstance().font;
		List<Hit> out = new ArrayList<>();
		int right = b[0] + b[2];
		out.add(new Hit(Kind.RESIZE, 0, right - 8, 8));
		right -= 9;
		if (windows().size() > 1) {
			out.add(new Hit(Kind.CLOSE, 0, right - BTN, BTN));
			right -= BTN;
		}
		out.add(new Hit(Kind.NEW_WINDOW, 0, right - BTN, BTN));
		right -= BTN;
		out.add(new Hit(Kind.SETTINGS, 0, right - BTN, BTN));
		right -= BTN + 2;
		int x = b[0];
		for (int i = 0; i < w.tabs.size(); i++) {
			int tw = font.width(tabLabel(w.tabs.get(i))) + 8;
			if (x + tw > right - BTN) {
				break; // passt nicht mehr – Fenster breiter ziehen
			}
			out.add(new Hit(Kind.TAB, i, x, tw));
			x += tw;
		}
		out.add(new Hit(Kind.ADD_TAB, 0, x, BTN));
		return out;
	}

	private static Window windowAt(double mx, double my, boolean headerOnly) {
		List<Window> ws = windows();
		for (int i = ws.size() - 1; i >= 0; i--) {
			int[] b = box(ws.get(i));
			int top = b[1] - HEADER;
			int bottom = headerOnly ? b[1] : b[1] + b[3];
			if (mx >= b[0] && mx < b[0] + b[2] && my >= top && my < bottom) {
				return ws.get(i);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Maus im Chat

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (!LookInfo.isChat(screen)) { // auch der Chat-Bildschirm von LabyMod
				return;
			}
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !active() || !click(s, e));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !active() || !drag(e));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> {
				if (dragWin != null) {
					dragWin = null;
					WidgetConfig.get().save();
					return false;
				}
				return true;
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, h, v) -> !active() || !scroll(mx, my, v));
		});
	}

	private static boolean click(Screen screen, MouseButtonEvent e) {
		Window w = windowAt(e.x(), e.y(), true);
		if (w == null) {
			return false;
		}
		int[] b = box(w);
		Hit hit = null;
		for (Hit h : hits(w, b)) {
			if (e.x() >= h.x && e.x() < h.x + h.w) {
				hit = h;
			}
		}
		Kind kind = hit == null ? Kind.MOVE : hit.kind;
		Minecraft mc = Minecraft.getInstance();
		List<Window> ws = windows();
		switch (kind) {
			case TAB -> {
				if (e.button() == 1) {
					w.active = hit.index;
					mc.setScreen(new ChatWindowScreen(w));
				} else {
					w.active = hit.index;
					UNREAD.remove(w.tab());
					SCROLL.put(w, 0);
					WidgetConfig.get().save();
				}
			}
			case ADD_TAB -> {
				Tab t = new Tab();
				t.name = "Tab " + (w.tabs.size() + 1);
				w.tabs.add(t);
				w.active = w.tabs.size() - 1;
				WidgetConfig.get().save();
				mc.setScreen(new ChatWindowScreen(w));
			}
			case SETTINGS -> mc.setScreen(new ChatWindowScreen(w));
			case NEW_WINDOW -> {
				Window n = new Window();
				n.left = w.left + 20;
				n.bottom = w.bottom + 20;
				n.w = Math.max(160, w.w * 2 / 3);
				n.h = Math.max(60, w.h * 2 / 3);
				n.bg = w.bg;
				n.opacity = w.opacity;
				n.accent = w.accent;
				n.textScale = w.textScale;
				n.fadeSec = w.fadeSec;
				Tab t = new Tab();
				t.name = "Privat";
				t.privateOnly = true;
				n.tabs.add(t);
				ws.add(n);
				WidgetConfig.get().save();
			}
			case CLOSE -> {
				ws.remove(w);
				WidgetConfig.get().save();
			}
			case RESIZE, MOVE -> {
				dragWin = w;
				resizing = kind == Kind.RESIZE;
				grabX = e.x();
				grabY = e.y();
				startW = w.w;
				startH = w.h;
				startLeft = b[0];
				startBottom = sh() - (b[1] + b[3]);
				// nach vorne holen
				ws.remove(w);
				ws.add(w);
			}
			default -> {
			}
		}
		return true;
	}

	private static boolean drag(MouseButtonEvent e) {
		if (dragWin == null) {
			return false;
		}
		int dx = (int) Math.round(e.x() - grabX), dy = (int) Math.round(e.y() - grabY);
		if (resizing) {
			dragWin.w = startW + dx;
			dragWin.h = startH - dy;
			dragWin.left = startLeft;
			dragWin.bottom = startBottom;
		} else {
			dragWin.left = startLeft + dx;
			dragWin.bottom = startBottom - dy;
		}
		box(dragWin); // begrenzen
		return true;
	}

	private static boolean scroll(double mx, double my, double v) {
		Window w = windowAt(mx, my, false);
		if (w == null) {
			return false;
		}
		int step = Minecraft.getInstance().hasShiftDown() ? 1 : 3;
		SCROLL.merge(w, (int) Math.signum(v) * step, Integer::sum);
		if (SCROLL.get(w) < 0) {
			SCROLL.put(w, 0);
		}
		return true;
	}

	/** Beim Schließen des Chats wieder ganz nach unten. */
	public static void resetScroll() {
		SCROLL.clear();
	}
}
