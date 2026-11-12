package de.loothub.client.quick;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;
import de.loothub.client.Sounds;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Alarm für gemerkte Auktionen: Loothub liest die Restzeit („Endet in: 5 Stunden 30 Minuten“) aus der Merkliste
 * des Auktionshauses und aus der Auktion, die man gerade gemerkt hat. Kurz vor dem Ende kommt eine Einblendung mit
 * Ton und eine Chat-Zeile. Nur Mitlesen – geboten oder gekauft wird nichts.
 * <p>
 * Der Server rundet die Restzeit ab („2 Tage“ = 2 bis 3 Tage). Loothub nimmt daher den frühestmöglichen Zeitpunkt
 * und wird mit jedem neuen Blick in die Merkliste genauer.
 */
public final class AuctionWatch {
	/** Eine gemerkte Auktion. */
	public static final class Watched {
		public String key = "";
		public String name = "";
		public String seller = "";
		public String price = "";
		/** Frühestmögliches Ende (ms). */
		public long endAt;
		/** Wie genau die letzte Restzeit war (ms, z. B. 1 Tag bei „2 Tage“). */
		public long precision;
		public boolean warned;
		public boolean ended;
	}

	private static final Gson GSON = new Gson();
	private static final Pattern PART = Pattern.compile("(\\d+)\\s*(tage?|stunden?|minuten?|sekunden?|d|h|m|s)\\b",
			Pattern.CASE_INSENSITIVE);
	private static List<Watched> list;
	private static ItemStack lastClicked = ItemStack.EMPTY;
	private static int ticks;

	private AuctionWatch() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> acs)) {
				return;
			}
			String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
			boolean merk = title.contains("merkliste");
			if (!merk && !title.contains("auktion")) {
				return;
			}
			ScreenMouseEvents.beforeMouseClick(screen).register((s, e) -> {
				Slot slot = ((de.loothub.client.mixin.AbstractContainerScreenAccessor) acs).loothub$hoveredSlot();
				if (slot != null && slot.hasItem()) {
					lastClicked = slot.getItem().copy();
				}
			});
			if (merk) {
				ScreenEvents.afterTick(screen).register(s -> {
					if (++ticks % 20 == 0) {
						scanMerkliste(acs);
					}
				});
			}
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			String low = message.getString().toLowerCase(Locale.ROOT);
			if (!low.contains("merkliste") || !low.contains("auktion")) {
				return;
			}
			if (low.contains("hinzugefügt")) {
				observe(lastClicked);
			} else if (low.contains("entfernt")) {
				Watched w = find(key(lastClicked));
				if (w != null) {
					all().remove(w);
					save();
				}
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.level != null && mc.level.getGameTime() % 20 == 0) {
				check(mc);
			}
		});
	}

	// ------------------------------------------------------------------ Daten

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-auction-watch.json");
	}

	public static List<Watched> all() {
		if (list == null) {
			list = new ArrayList<>();
			Path p = file();
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					List<Watched> l = GSON.fromJson(r, new TypeToken<List<Watched>>() { }.getType());
					if (l != null) {
						l.removeIf(x -> x == null || x.key == null);
						list.addAll(l);
					}
				} catch (Exception e) {
					Loothub.LOGGER.warn("Merkliste konnte nicht gelesen werden", e);
				}
			}
		}
		return list;
	}

	private static void save() {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(all()));
	}

	private static Watched find(String key) {
		for (Watched w : all()) {
			if (w.key.equals(key)) {
				return w;
			}
		}
		return null;
	}

	private static List<String> lore(ItemStack stack) {
		List<String> out = new ArrayList<>();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component c : lore.lines()) {
				out.add(ChatFormatting.stripFormatting(c.getString()).strip());
			}
		}
		return out;
	}

	private static String value(List<String> lore, String label) {
		for (String l : lore) {
			if (l.toLowerCase(Locale.ROOT).startsWith(label)) {
				int c = l.indexOf(':');
				return c >= 0 ? l.substring(c + 1).strip() : "";
			}
		}
		return "";
	}

	/** Auktion erkennen: Name + Verkäufer + Startgebot. */
	private static String key(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		List<String> lore = lore(stack);
		return stack.getHoverName().getString() + "|" + value(lore, "spieler") + "|" + value(lore, "startgebot");
	}

	/** Restzeit „2 Tage“, „5 Stunden 30 Minuten“ → {ms, Genauigkeit in ms}; null = keine Restzeit. */
	static long[] parse(String text) {
		Matcher m = PART.matcher(text);
		long total = 0;
		long smallest = 0;
		boolean any = false;
		while (m.find()) {
			any = true;
			long n = Long.parseLong(m.group(1));
			String u = m.group(2).toLowerCase(Locale.ROOT);
			long unit = u.startsWith("t") || u.equals("d") ? 86_400_000L : u.startsWith("st") || u.equals("h") ? 3_600_000L
					: u.startsWith("m") ? 60_000L : 1_000L;
			total += n * unit;
			smallest = smallest == 0 ? unit : Math.min(smallest, unit);
		}
		return any ? new long[]{total, smallest} : null;
	}

	/** Auktion (gerade gemerkt oder in der Merkliste gesehen) mit ihrer Restzeit übernehmen. */
	private static void observe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		List<String> lore = lore(stack);
		String ends = value(lore, "endet in");
		long[] left = ends.isEmpty() ? null : parse(ends);
		if (left == null) {
			return;
		}
		String key = key(stack);
		Watched w = find(key);
		long now = System.currentTimeMillis();
		long earliest = now + left[0];
		if (w == null) {
			w = new Watched();
			w.key = key;
			w.name = stack.getHoverName().getString();
			w.seller = value(lore, "spieler");
			String buy = value(lore, "sofortkauf");
			w.price = buy.isEmpty() ? value(lore, "startgebot") : buy;
			w.endAt = earliest;
			w.precision = left[1];
			all().add(w);
		} else {
			// genauere Angabe übernimmt; die Untergrenze kann nur später werden
			w.endAt = Math.max(w.endAt, earliest);
			w.precision = Math.min(w.precision, left[1]);
			if (w.endAt - now > alarmMs()) {
				w.warned = false;
			}
		}
		save();
	}

	private static void scanMerkliste(AbstractContainerScreen<?> screen) {
		Minecraft mc = Minecraft.getInstance();
		List<String> seen = new ArrayList<>();
		for (Slot slot : screen.getMenu().slots) {
			if (mc.player != null && slot.container == mc.player.getInventory()) {
				continue;
			}
			ItemStack s = slot.getItem();
			if (!s.isEmpty() && !value(lore(s), "endet in").isEmpty()) {
				observe(s);
				seen.add(key(s));
			}
		}
		// in der Merkliste nicht mehr vorhanden (abgelaufen, entfernt) → vergessen
		if (!seen.isEmpty() && all().removeIf(w -> !seen.contains(w.key) && w.endAt < System.currentTimeMillis())) {
			save();
		}
	}

	private static long alarmMs() {
		return Math.max(1, WidgetConfig.get().auctionAlarmMinutes) * 60_000L;
	}

	private static void check(Minecraft mc) {
		if (!WidgetConfig.get().auctionAlarm || all().isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (Watched w : new ArrayList<>(all())) {
			long left = w.endAt - now;
			if (!w.warned && left <= alarmMs() && left > -60_000L) {
				w.warned = true;
				changed = true;
				String when = left <= 60_000L ? "jetzt" : "in ca. " + Math.max(1, left / 60_000L) + " Min.";
				de.loothub.client.jobs.FishAlert.notice("⏰ Auktion endet " + when, w.name + (w.seller.isEmpty() ? "" : " · " + w.seller),
						0xF2C14E);
				Sounds.play(Sounds.Group.TIMER, List.of(
						Sounds.n(0, SoundEvents.NOTE_BLOCK_BELL, 1.0F, 1.0F),
						Sounds.n(4, SoundEvents.NOTE_BLOCK_BELL, 1.26F, 1.0F),
						Sounds.n(8, SoundEvents.NOTE_BLOCK_BELL, 1.5F, 1.0F)));
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal("[Loothub+] ").withStyle(ChatFormatting.GOLD)
							.append(Component.literal("Gemerkte Auktion endet " + when + ": ").withStyle(ChatFormatting.YELLOW))
							.append(Component.literal(w.name).withStyle(ChatFormatting.WHITE))
							.append(Component.literal(w.price.isEmpty() ? "" : " (" + w.price + ")").withStyle(ChatFormatting.GRAY))
							.append(Component.literal(w.precision >= 86_400_000L
									? " – Restzeit war nur in Tagen bekannt, Merkliste öffnen für die genaue Zeit" : "")
									.withStyle(ChatFormatting.DARK_GRAY)), false);
				}
			}
			if (left < -6 * 3_600_000L) {
				all().remove(w); // längst vorbei
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}
}
