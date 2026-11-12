package de.loothub.client.quick;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Inventar-Werkzeuge: Sortieren (Inventar und Kisten), Verschieben (alles / Passendes einlagern, alles herausnehmen)
 * und Auto-Refill (leerer Stapel in der Hand wird nachgefüllt, fast kaputtes Werkzeug ersetzt). Alles läuft über ganz
 * normale Klicks, verteilt auf mehrere Ticks. Kisten-Funktionen nur bei echten Kisten, Fässern, Shulkerkisten und der
 * Endertruhe (Titel aus Minecraft) – nie in Server-Menüs, wo ein Klick eine Aktion auslösen würde.
 * Sortieren und Verschieben haben einstellbare Tasten (Optionen › Steuerung › Loothub+); Auto-Refill läuft von selbst.
 */
public final class InvTools {
	/** Klicks je Tick – zügig, aber nicht alles auf einmal. */
	private static final int CLICKS_PER_TICK = 4;
	/** Werkzeugschutz: ab so viel Resthaltbarkeit wird getauscht. */
	private static final int TOOL_SAFE = 5;

	private static KeyMapping sortKey, sortMouseKey, moveKey;
	private static final Deque<int[]> QUEUE = new ArrayDeque<>();
	private static int queueContainer = -1;

	// Auto-Refill: Zustand der Hand im letzten Tick
	private static int lastSelected = -1;
	private static ItemStack lastHand = ItemStack.EMPTY;
	private static int refillDelay;
	private static long toolWarnedAt;

	private InvTools() {
	}

	public static void register(KeyMapping.Category category) {
		// Sortieren: R oder mittlere Maustaste; Verschieben: seitliche Maustaste (mit Shift: alles). Unter
		// Optionen › Steuerung › Loothub+ frei änderbar. Achtung: Inventory Profiles Next nutzt R/Mausrad-Klick selbst.
		sortKey = key("key.loothub.inv_sort", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category);
		sortMouseKey = key("key.loothub.inv_sort_mouse", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, category);
		moveKey = key("key.loothub.inv_move", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4, category);
		ClientTickEvents.END_CLIENT_TICK.register(InvTools::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> cs)) {
				return;
			}
			addButtons(cs);
			ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
				// nicht beim Tippen (z. B. Suchfeld des Rezeptbuchs)
				if (sortKey.matches(event) && !(cs.getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
					sortHovered(cs);
				}
			});
			// Maustasten: Klick wird verbraucht, wenn er eine Inventar-Aktion auslöst
			net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen)
					.register((s, e) -> !onMouse(cs, e));
		});
	}

	private static KeyMapping key(String id, InputConstants.Type type, int code, KeyMapping.Category category) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(id, type, code, category));
	}

	// ---------------------------------------------------------------- Bereiche

	/** Ist das eine echte Kiste (kein Server-Menü)? Erkennbar am unveränderten Minecraft-Titel. */
	private static boolean realContainer(AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = screen.getMenu();
		if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
			return false;
		}
		return screen.getTitle().getContents() instanceof TranslatableContents t && t.getKey().startsWith("container.");
	}

	/** Slots der Kiste (ohne Spielerinventar). */
	private static List<Integer> containerSlots(AbstractContainerMenu menu) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			if (!(menu.slots.get(i).container instanceof Inventory)) {
				out.add(i);
			}
		}
		return out;
	}

	/** Hauptinventar des Spielers (ohne Schnellleiste, Rüstung, Zweithand) in diesem Menü. */
	private static List<Integer> playerMainSlots(AbstractContainerMenu menu) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (s.container instanceof Inventory && s.getContainerSlot() >= 9 && s.getContainerSlot() < 36) {
				out.add(i);
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- Knöpfe

	private static void addButtons(AbstractContainerScreen<?> screen) {
		WidgetConfig c = WidgetConfig.get();
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.loothub$leftPos(), top = acc.loothub$topPos(), w = acc.loothub$imageWidth();
		AbstractContainerMenu menu = screen.getMenu();
		boolean real = realContainer(screen);
		if (screen instanceof InventoryScreen) {
			if (c.invSort) {
				// rechts über dem Hauptinventar
				Screens.getButtons(screen).add(small(left + w - 14, top + 70, "⇅", "loothub.inv.sort_inventory",
						() -> sort(playerMainSlots(menu))));
			}
			return;
		}
		if (!real) {
			return;
		}
		int containerRows = (containerSlots(menu).size() + 8) / 9;
		int invY = top + 17 + containerRows * 18 + 1; // Höhe der Beschriftung „Inventar“
		int x = left + w - 14;
		if (c.invSort) {
			Screens.getButtons(screen).add(small(x, top + 4, "⇅", "loothub.inv.sort_container", () -> sort(containerSlots(menu))));
			Screens.getButtons(screen).add(small(x, invY, "⇅", "loothub.inv.sort_inventory", () -> sort(playerMainSlots(menu))));
			x -= 12;
		}
		if (c.invMove) {
			Screens.getButtons(screen).add(small(x, top + 4, "⇩", "loothub.inv.take_all", () -> take(menu, false)));
			Screens.getButtons(screen).add(small(x, invY, "⇧", "loothub.inv.store_all", () -> store(menu, false)));
			Screens.getButtons(screen).add(small(x - 12, invY, "≡", "loothub.inv.store_matching", () -> store(menu, true)));
		}
	}

	private static Button small(int x, int y, String label, String tooltipKey, Runnable action) {
		return Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, 11, 11)
				.tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
	}

	// ---------------------------------------------------------------- Tasten

	/** Sortiert den Bereich unter der Maus (über der Kiste die Kiste, sonst das Inventar). */
	private static void sortHovered(AbstractContainerScreen<?> screen) {
		if (!WidgetConfig.get().invSort) {
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		boolean real = realContainer(screen);
		if (overContainer(screen) && real) {
			sort(containerSlots(menu));
		} else if (screen instanceof InventoryScreen || real) {
			sort(playerMainSlots(menu));
		}
	}

	private static boolean overContainer(AbstractContainerScreen<?> screen) {
		Slot hovered = ((AbstractContainerScreenAccessor) screen).loothub$hoveredSlot();
		return hovered != null && !(hovered.container instanceof Inventory);
	}

	/** @return true, wenn der Mausklick eine Inventar-Aktion ausgelöst hat (dann wird er verbraucht) */
	private static boolean onMouse(AbstractContainerScreen<?> screen, net.minecraft.client.input.MouseButtonEvent e) {
		WidgetConfig c = WidgetConfig.get();
		if (sortMouseKey.matchesMouse(e) || sortKey.matchesMouse(e)) {
			if (!c.invSort) {
				return false;
			}
			sortHovered(screen);
			return true;
		}
		if (moveKey.matchesMouse(e) && c.invMove && realContainer(screen)) {
			move(screen, Minecraft.getInstance().hasShiftDown());
			return true;
		}
		return false;
	}

	/**
	 * Verschieben in Richtung „von unter der Maus weg“: über der Kiste heraus, über dem Inventar hinein. Ohne Shift nur
	 * Items, die auf der anderen Seite schon liegen; mit Shift alles.
	 */
	private static void move(AbstractContainerScreen<?> screen, boolean all) {
		AbstractContainerMenu menu = screen.getMenu();
		if (overContainer(screen)) {
			take(menu, !all);
		} else {
			store(menu, !all);
		}
	}

	// ---------------------------------------------------------------- Sortieren

	private static boolean same(ItemStack a, ItemStack b) {
		return ItemStack.isSameItemSameComponents(a, b);
	}

	/** Sortierschlüssel: nach Item-ID, dann Name (bei umbenannten / Server-Items), volle Stapel zuerst. */
	private static final Comparator<ItemStack> ORDER = Comparator
			.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString())
			.thenComparing(s -> s.getHoverName().getString())
			.thenComparing(s -> s.getComponents().hashCode())
			.thenComparing(ItemStack::getCount, Comparator.reverseOrder());

	/** Sortiert die Slots: erst gleiche Stapel zusammenlegen, dann ordnen. Plant alle Klicks vorab (Simulation). */
	private static void sort(List<Integer> slots) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || slots.isEmpty() || !QUEUE.isEmpty() || !mc.player.containerMenu.getCarried().isEmpty()) {
			return;
		}
		AbstractContainerMenu menu = mc.player.containerMenu;
		Sim sim = new Sim(menu, slots);
		// 1) Zusammenlegen
		for (int j = slots.size() - 1; j > 0; j--) {
			for (int i = 0; i < j; i++) {
				ItemStack a = sim.at(i), b = sim.at(j);
				if (!b.isEmpty() && same(a, b) && a.getCount() < a.getMaxStackSize()) {
					sim.click(j);
					sim.click(i);
					if (!sim.carried.isEmpty()) {
						sim.click(j);
					}
				}
			}
		}
		// 2) Zielreihenfolge
		List<ItemStack> target = new ArrayList<>();
		for (int i = 0; i < slots.size(); i++) {
			if (!sim.at(i).isEmpty()) {
				target.add(sim.at(i).copy());
			}
		}
		target.sort(ORDER);
		// 3) an den Platz tauschen (gleiche Item-Art gilt als passend – so wird nie zusammengelegt statt getauscht)
		for (int p = 0; p < target.size(); p++) {
			ItemStack want = target.get(p);
			if (same(sim.at(p), want)) {
				continue;
			}
			int q = -1;
			for (int k = p + 1; k < slots.size(); k++) {
				if (same(sim.at(k), want) && !same(sim.at(k), target.get(k < target.size() ? k : p))) {
					q = k;
					break;
				}
			}
			if (q < 0) {
				for (int k = p + 1; k < slots.size(); k++) {
					if (same(sim.at(k), want)) {
						q = k;
						break;
					}
				}
			}
			if (q < 0) {
				continue;
			}
			sim.click(q); // aufnehmen
			sim.click(p); // ablegen (tauscht mit dem, was dort lag)
			if (!sim.carried.isEmpty()) {
				sim.click(q); // Getauschtes in die frei gewordene Stelle
			}
		}
		if (!sim.carried.isEmpty()) {
			return; // sollte nie passieren – dann lieber gar nicht klicken
		}
		queue(menu, sim.clicks);
	}

	/** Nachbildung der Linksklicks (Aufnehmen/Ablegen/Tauschen/Zusammenlegen), um die Klickfolge vorab zu planen. */
	private static final class Sim {
		final List<Integer> slots;
		final List<ItemStack> items = new ArrayList<>();
		ItemStack carried = ItemStack.EMPTY;
		final List<int[]> clicks = new ArrayList<>();

		Sim(AbstractContainerMenu menu, List<Integer> slots) {
			this.slots = slots;
			for (int s : slots) {
				items.add(menu.slots.get(s).getItem().copy());
			}
		}

		ItemStack at(int i) {
			return items.get(i);
		}

		void click(int i) {
			clicks.add(new int[]{slots.get(i), 0, ClickType.PICKUP.ordinal()});
			ItemStack slot = items.get(i);
			if (carried.isEmpty()) {
				carried = slot;
				items.set(i, ItemStack.EMPTY);
			} else if (slot.isEmpty()) {
				items.set(i, carried);
				carried = ItemStack.EMPTY;
			} else if (same(slot, carried)) {
				int move = Math.min(carried.getCount(), slot.getMaxStackSize() - slot.getCount());
				slot.grow(move);
				carried.shrink(move);
				if (carried.isEmpty()) {
					carried = ItemStack.EMPTY;
				}
			} else {
				items.set(i, carried);
				carried = slot;
			}
		}
	}

	// ---------------------------------------------------------------- Verschieben

	/** Hauptinventar in die Kiste (Shift-Klick); {@code matching}: nur Items, die schon in der Kiste liegen. */
	private static void store(AbstractContainerMenu menu, boolean matching) {
		List<ItemStack> inside = new ArrayList<>();
		for (int s : containerSlots(menu)) {
			ItemStack st = menu.slots.get(s).getItem();
			if (!st.isEmpty()) {
				inside.add(st);
			}
		}
		List<int[]> clicks = new ArrayList<>();
		for (int s : playerMainSlots(menu)) {
			ItemStack st = menu.slots.get(s).getItem();
			if (st.isEmpty() || matching && inside.stream().noneMatch(x -> same(x, st))) {
				continue;
			}
			clicks.add(new int[]{s, 0, ClickType.QUICK_MOVE.ordinal()});
		}
		queue(menu, clicks);
	}

	/** Kiste ins Hauptinventar (Shift-Klick); {@code matching}: nur Items, die schon im Inventar liegen. */
	private static void take(AbstractContainerMenu menu, boolean matching) {
		List<ItemStack> mine = new ArrayList<>();
		for (int s : playerMainSlots(menu)) {
			ItemStack st = menu.slots.get(s).getItem();
			if (!st.isEmpty()) {
				mine.add(st);
			}
		}
		List<int[]> clicks = new ArrayList<>();
		for (int s : containerSlots(menu)) {
			ItemStack st = menu.slots.get(s).getItem();
			if (st.isEmpty() || matching && mine.stream().noneMatch(x -> same(x, st))) {
				continue;
			}
			clicks.add(new int[]{s, 0, ClickType.QUICK_MOVE.ordinal()});
		}
		queue(menu, clicks);
	}

	// ---------------------------------------------------------------- Ausführen

	private static void queue(AbstractContainerMenu menu, List<int[]> clicks) {
		if (clicks.isEmpty()) {
			return;
		}
		QUEUE.clear();
		QUEUE.addAll(clicks);
		queueContainer = menu.containerId;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.gameMode == null) {
			QUEUE.clear();
			return;
		}
		if (!QUEUE.isEmpty()) {
			if (mc.player.containerMenu.containerId != queueContainer) {
				QUEUE.clear(); // Menü gewechselt – abbrechen
			} else {
				for (int n = 0; n < CLICKS_PER_TICK && !QUEUE.isEmpty(); n++) {
					int[] c = QUEUE.poll();
					mc.gameMode.handleInventoryMouseClick(queueContainer, c[0], c[1], ClickType.values()[c[2]], mc.player);
				}
			}
		}
		refill(mc);
	}

	// ---------------------------------------------------------------- Auto-Refill

	private static void refill(Minecraft mc) {
		WidgetConfig c = WidgetConfig.get();
		Inventory inv = mc.player.getInventory();
		int sel = inv.getSelectedSlot();
		ItemStack hand = inv.getItem(sel);
		boolean free = mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty()
				&& QUEUE.isEmpty() && !mc.player.isCreative();
		if (c.autoRefill && free) {
			// 1) Stapel aufgebraucht (der letzte wurde benutzt) → gleichen Stapel nachlegen
			if (sel == lastSelected && hand.isEmpty() && !lastHand.isEmpty() && lastHand.getCount() == 1) {
				if (++refillDelay >= 2) {
					int from = find(inv, lastHand, sel, false);
					if (from >= 0) {
						swapIntoHand(mc, from, sel);
					}
					lastHand = ItemStack.EMPTY;
					refillDelay = 0;
				}
				return;
			}
			// 2) Werkzeug fast kaputt → gleiches Werkzeug mit mehr Haltbarkeit nehmen, sonst weglegen
			if (c.toolSaver && hand.isDamageableItem() && hand.getMaxDamage() - hand.getDamageValue() <= TOOL_SAFE
					&& System.currentTimeMillis() - toolWarnedAt > 1500L) {
				toolWarnedAt = System.currentTimeMillis();
				int from = find(inv, hand, sel, true);
				if (from < 0) {
					from = emptySlot(inv);
				}
				if (from >= 0) {
					swapIntoHand(mc, from, sel);
					mc.player.displayClientMessage(Component.translatable("loothub.inv.tool_saved"), true);
				}
			}
		}
		refillDelay = 0;
		lastSelected = sel;
		lastHand = hand.copy();
	}

	/** Inventar-Index eines passenden Stapels (Hauptinventar zuerst, dann Schnellleiste), oder -1. */
	private static int find(Inventory inv, ItemStack like, int except, boolean tool) {
		int best = -1;
		for (int pass = 0; pass < 2 && best < 0; pass++) {
			int from = pass == 0 ? 9 : 0, to = pass == 0 ? 36 : 9;
			for (int i = from; i < to; i++) {
				if (i == except) {
					continue;
				}
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) {
					continue;
				}
				if (tool) {
					// gleiche Werkzeug-Art mit genug Haltbarkeit (Verzauberungen dürfen abweichen)
					if (s.getItem() == like.getItem() && s.getMaxDamage() - s.getDamageValue() > TOOL_SAFE) {
						return i;
					}
				} else if (same(s, like)) {
					return i;
				}
			}
		}
		return best;
	}

	private static int emptySlot(Inventory inv) {
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	/** Inventar-Index {@code from} mit dem Schnellleisten-Platz {@code hotbar} tauschen (Zahlentasten-Tausch). */
	private static void swapIntoHand(Minecraft mc, int from, int hotbar) {
		int menuSlot = from < 9 ? InventoryMenu.USE_ROW_SLOT_START + from : from; // 9..35 sind im Menü dieselben Nummern
		mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, menuSlot, hotbar, ClickType.SWAP, mc.player);
	}
}
