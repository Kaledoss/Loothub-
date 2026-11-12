package de.loothub.client.quick;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Kompressor (OPSUCHT): zeigt am Kompressor-Item im Inventar und in der Schnellleiste klein an, was er komprimiert.
 * Beim Öffnen seines Menüs („OPSUCHT » Kompressor“) merkt sich Loothub die eingestellten Ziel-Items (die mit „Du
 * benötigst … der folgenden Materialien“) – je Kompressor, falls man mehrere hat. Mehrere Ziele wechseln sich im
 * Symbol ab. Nur Anzeige.
 */
public final class Compressor {
	private static final String TITLE = "Kompressor";
	private static final long CYCLE_MS = 1_200L;
	private static final Map<String, List<ItemStack>> TARGETS = new HashMap<>();
	private static String openKey;
	private static int scanTick;
	private static boolean loaded;

	private Compressor() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof AbstractContainerScreen<?> && screen.getTitle().getString().contains(TITLE)
					&& client.player != null) {
				// welcher Kompressor wurde geöffnet? Der in der Hand (sonst der erste im Inventar)
				ItemStack hand = client.player.getMainHandItem();
				ItemStack off = client.player.getOffhandItem();
				openKey = is(hand) ? key(hand) : is(off) ? key(off) : firstKey(client.player.getInventory());
			} else if (!(screen instanceof AbstractContainerScreen<?>)) {
				openKey = null;
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(Compressor::tick);
	}

	private static void tick(Minecraft mc) {
		if (!loaded && mc.level != null) {
			load();
		}
		if (openKey == null || !(mc.screen instanceof AbstractContainerScreen<?> cs)
				|| !cs.getTitle().getString().contains(TITLE) || ++scanTick % 10 != 0) {
			return;
		}
		List<ItemStack> found = new ArrayList<>();
		for (Slot s : cs.getMenu().slots) {
			if (s.container instanceof Inventory) {
				continue;
			}
			ItemStack st = s.getItem();
			if (!st.isEmpty() && isTarget(st)) {
				found.add(st.copyWithCount(1));
			}
		}
		List<ItemStack> old = TARGETS.get(openKey);
		if (old == null || !sameList(old, found)) {
			TARGETS.put(openKey, found);
			save();
		}
	}

	/** Ziel-Item im Kompressor-Menü: hat die Zeile „Du benötigst … der folgenden Materialien“. */
	private static boolean isTarget(ItemStack st) {
		ItemLore lore = st.get(DataComponents.LORE);
		if (lore == null) {
			return false;
		}
		for (Component line : lore.lines()) {
			String t = line.getString();
			if (t.contains("benötigst") && t.contains("Materialien")) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameList(List<ItemStack> a, List<ItemStack> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/** Ist das ein Kompressor-Item? */
	public static boolean is(ItemStack st) {
		return !st.isEmpty() && st.getHoverName().getString().contains(TITLE);
	}

	/** Kennung eines Kompressors: seine Server-Daten (damit mehrere Kompressoren unterschieden werden). */
	private static String key(ItemStack st) {
		CustomData data = st.get(DataComponents.CUSTOM_DATA);
		return data == null ? st.getHoverName().getString() : Integer.toHexString(data.toString().hashCode());
	}

	private static String firstKey(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (is(inv.getItem(i))) {
				return key(inv.getItem(i));
			}
		}
		return "";
	}

	/** Am Slot zeichnen (aus der Item-Deko): kleines Symbol des Ziel-Items oben links, mehrere im Wechsel. */
	public static void decorate(GuiGraphics g, ItemStack st, int x, int y) {
		if (!WidgetConfig.get().compressorIcon || !is(st)) {
			return;
		}
		List<ItemStack> list = TARGETS.get(key(st));
		if (list == null || list.isEmpty()) {
			return;
		}
		ItemStack show = list.get((int) ((System.currentTimeMillis() / CYCLE_MS) % list.size()));
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.5F, 0.5F);
		g.renderItem(show, 0, 0);
		g.pose().popMatrix();
	}

	// ------------------------------------------------------------------ Speichern

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-compressor.json");
	}

	private static HolderLookup.Provider registries() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.registryAccess() : null;
	}

	private static void load() {
		loaded = true;
		HolderLookup.Provider regs = registries();
		Path f = file();
		if (regs == null || !Files.exists(f)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				List<ItemStack> list = new ArrayList<>();
				for (JsonElement j : e.getValue().getAsJsonArray()) {
					ItemStack.CODEC.parse(regs.createSerializationContext(JsonOps.INSTANCE), j).result().ifPresent(list::add);
				}
				TARGETS.put(e.getKey(), list);
			}
		} catch (IOException | RuntimeException ex) {
			Loothub.LOGGER.warn("Kompressor-Liste nicht lesbar", ex);
		}
	}

	private static void save() {
		HolderLookup.Provider regs = registries();
		if (regs == null) {
			return;
		}
		JsonObject root = new JsonObject();
		for (Map.Entry<String, List<ItemStack>> e : TARGETS.entrySet()) {
			JsonArray arr = new JsonArray();
			for (ItemStack st : e.getValue()) {
				ItemStack.CODEC.encodeStart(regs.createSerializationContext(JsonOps.INSTANCE), st).result().ifPresent(arr::add);
			}
			root.add(e.getKey(), arr);
		}
		try {
			Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			Loothub.LOGGER.warn("Kompressor-Liste nicht gespeichert", ex);
		}
	}
}
