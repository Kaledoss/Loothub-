package de.loothub.client.quick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Lebendige Hotbar (nur Anzeige): die Auswahl gleitet von Slot zu Slot, das gewählte Item hebt sich leicht an, Items
 * hüpfen beim Aufsammeln, stauchen sich beim Platzieren/Verbrauchen/Essen, wackeln, wenn ein Werkzeug Haltbarkeit
 * verliert, und ploppen herein, wenn ein neues Item im Slot landet. Zwei Stile: „weich“ und „federnd“.
 */
public final class HotbarAnim {
	private static final int SLOTS = 10; // 9 Hotbar-Slots + Zweithand
	private static final int NONE = 0, POP = 1, SQUISH = 2, SHAKE = 3, APPEAR = 4;
	private static final float DURATION = 0.35F;

	private record Snap(Item item, int count, int damage) {
	}

	private static final Snap[] PREV = new Snap[SLOTS];
	private static final int[] KIND = new int[SLOTS];
	private static final long[] START = new long[SLOTS];
	private static final float[] LIFT = new float[SLOTS];
	private static final boolean[] PUSHED = new boolean[SLOTS];

	private static float selX = Float.NaN;
	private static float selV;
	private static long lastSel;
	private static long lastSlotFrame;
	private static boolean selPushed;

	private HotbarAnim() {
	}

	public static boolean on() {
		return WidgetConfig.get().hotbarAnim;
	}

	private static boolean bouncy() {
		return "bouncy".equals(WidgetConfig.get().hotbarAnimStyle);
	}

	// ------------------------------------------------------------------ Auswahlrahmen

	/** Vor dem Zeichnen des Auswahlrahmens: um die gleitende Position verschieben. */
	public static void beforeSelection(GuiGraphics g) {
		selPushed = false;
		Player p = Minecraft.getInstance().player;
		if (!on() || p == null) {
			selX = Float.NaN;
			return;
		}
		float target = p.getInventory().getSelectedSlot();
		long now = System.nanoTime();
		float dt = lastSel == 0 ? 0F : Math.min(0.05F, (now - lastSel) / 1e9F);
		lastSel = now;
		if (Float.isNaN(selX)) {
			selX = target;
			selV = 0F;
		}
		if (bouncy()) {
			// Feder: schießt etwas über das Ziel hinaus und schwingt aus
			float acc = (target - selX) * 320F - selV * 20F;
			selV += acc * dt;
			selX += selV * dt;
		} else {
			selX += (target - selX) * (1F - (float) Math.exp(-dt * 20F));
			selV = 0F;
		}
		if (Math.abs(target - selX) < 0.002F && Math.abs(selV) < 0.01F) {
			selX = target;
			selV = 0F;
		}
		float dx = (selX - target) * 20F;
		if (dx != 0F) {
			g.pose().pushMatrix();
			g.pose().translate(dx, 0F);
			selPushed = true;
		}
	}

	public static void afterSelection(GuiGraphics g) {
		if (selPushed) {
			g.pose().popMatrix();
			selPushed = false;
		}
	}

	// ------------------------------------------------------------------ Items in den Slots

	/** Vor dem Zeichnen eines Slots: Ereignisse erkennen und das Item passend verformen. */
	public static void beforeSlot(GuiGraphics g, int x, int y, Player player, ItemStack stack, int seed) {
		int idx = seed - 1;
		if (idx < 0 || idx >= SLOTS) {
			return;
		}
		PUSHED[idx] = false;
		if (!on() || player == null) {
			PREV[idx] = null;
			return;
		}
		long now = System.nanoTime();
		// Ereignis aus der Änderung seit dem letzten Bild
		Snap cur = stack.isEmpty() ? null : new Snap(stack.getItem(), stack.getCount(), stack.getDamageValue());
		Snap old = PREV[idx];
		if (cur != null && old != null && cur.item() == old.item()) {
			if (cur.count() > old.count()) {
				start(idx, POP, now);
			} else if (cur.count() < old.count()) {
				start(idx, SQUISH, now);
			} else if (cur.damage() > old.damage()) {
				start(idx, SHAKE, now);
			}
		} else if (cur != null && (old == null || cur.item() != old.item()) && PREV_KNOWN[idx]) {
			start(idx, APPEAR, now);
		}
		PREV[idx] = cur;
		PREV_KNOWN[idx] = true;
		if (cur == null) {
			KIND[idx] = NONE;
			return;
		}

		// gewähltes Item etwas anheben (gleitend)
		boolean selected = idx < 9 && idx == player.getInventory().getSelectedSlot();
		float frame = lastSlotFrame == 0 ? 0F : Math.min(0.05F, (now - lastSlotFrame) / 1e9F);
		if (idx == 0) {
			lastSlotFrame = now;
		}
		LIFT[idx] += ((selected ? -2F : 0F) - LIFT[idx]) * (1F - (float) Math.exp(-frame * 14F));

		float sx = 1F, sy = 1F, rot = 0F;
		float t = KIND[idx] == NONE ? 1F : (now - START[idx]) / 1e9F / DURATION;
		if (t >= 1F) {
			KIND[idx] = NONE;
		} else {
			float b = bump(t);
			switch (KIND[idx]) {
				case POP -> {
					sx = sy = 1F + 0.28F * b;
				}
				case SQUISH -> {
					sx = 1F + 0.16F * b;
					sy = 1F - 0.22F * b;
				}
				case SHAKE -> rot = (float) Math.sin(t * 38F) * 0.28F * (1F - t);
				case APPEAR -> {
					float s = bouncy() ? 1F - (float) (Math.exp(-6F * t) * Math.cos(t * 14F)) * 0.6F
							: 0.45F + 0.55F * easeOut(t);
					sx = sy = s;
				}
				default -> {
				}
			}
		}
		// beim Essen/Trinken/Bogenspannen wackelt das benutzte Item leicht
		if (selected && player.isUsingItem() && player.getUseItem() == stack) {
			rot += (float) Math.sin(now / 1e9 * 14.0) * 0.07F;
			float pulse = 1F - 0.06F * (0.5F + 0.5F * (float) Math.sin(now / 1e9 * 9.0));
			sx *= pulse;
			sy *= pulse;
		}
		float lift = LIFT[idx];
		if (sx == 1F && sy == 1F && rot == 0F && Math.abs(lift) < 0.01F) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x + 8F, y + 8F + lift);
		if (rot != 0F) {
			g.pose().rotate(rot);
		}
		g.pose().scale(sx, sy);
		g.pose().translate(-(x + 8F), -(y + 8F));
		PUSHED[idx] = true;
	}

	public static void afterSlot(GuiGraphics g, int seed) {
		int idx = seed - 1;
		if (idx >= 0 && idx < SLOTS && PUSHED[idx]) {
			g.pose().popMatrix();
			PUSHED[idx] = false;
		}
	}

	/** Beim ersten Bild nach dem Einloggen nichts hereinploppen lassen. */
	private static final boolean[] PREV_KNOWN = new boolean[SLOTS];

	private static void start(int idx, int kind, long now) {
		KIND[idx] = kind;
		START[idx] = now;
	}

	/** 0 → 1 → 0: weich als Bogen, federnd als ausschwingende Welle. */
	private static float bump(float t) {
		if (bouncy()) {
			return (float) (Math.exp(-5F * t) * Math.sin(t * Math.PI * 2.5F));
		}
		return (float) Math.sin(t * Math.PI) * (1F - t * 0.3F);
	}

	private static float easeOut(float t) {
		float u = 1F - t;
		return 1F - u * u * u;
	}

	/** Beim Weltwechsel alles zurücksetzen. */
	public static void reset() {
		java.util.Arrays.fill(PREV, null);
		java.util.Arrays.fill(PREV_KNOWN, false);
		java.util.Arrays.fill(KIND, NONE);
		selX = Float.NaN;
	}
}
