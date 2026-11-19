package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Lebendige Inventare (nur Anzeige): Items unter dem Mauszeiger werden etwas größer, trägt man ein Item, schweben
 * gleiche Items im Container sanft auf und ab, das getragene Item wackelt bei schnellen Bewegungen nach, und seltene
 * Items ziehen eine kleine Funkenspur in ihrer Seltenheitsfarbe hinter sich her.
 */
public final class InvAnim {
	private static final Map<Slot, float[]> SCALE = new WeakHashMap<>();
	private static Slot pushedSlot;

	private static float wiggle, wiggleV;
	private static double lastMx = Double.NaN;
	private static long lastFrame;
	private static boolean carriedPushed;

	private record Spark(float x, float y, float vx, float vy, int rgb, long born) {
	}

	private static final List<Spark> SPARKS = new ArrayList<>();
	private static float sparkCarry;

	private InvAnim() {
	}

	public static boolean on() {
		return WidgetConfig.get().inventoryAnim;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof AbstractContainerScreen<?>) {
				ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> sparks(s, g, mx, my));
				ScreenEvents.remove(screen).register(s -> {
					SPARKS.clear();
					lastMx = Double.NaN;
				});
			}
		});
	}

	// ------------------------------------------------------------------ Slots

	/** Vor dem Zeichnen eines Slots (Koordinaten im Container-Raum). */
	public static void beforeSlot(GuiGraphics g, Slot slot) {
		pushedSlot = null;
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen) || !slot.hasItem()) {
			return;
		}
		ItemStack carried = mc.player.containerMenu.getCarried();
		boolean hovered = ((AbstractContainerScreenAccessor) screen).loothub$hoveredSlot() == slot && carried.isEmpty();
		float[] st = SCALE.computeIfAbsent(slot, k -> new float[]{1F, 0F});
		long now = System.nanoTime();
		float dt = Math.min(0.05F, Math.max(0F, (now - (long) st[1]) / 1e9F));
		st[1] = now;
		float target = hovered ? 1.18F : 1F;
		st[0] += (target - st[0]) * (1F - (float) Math.exp(-dt * 16F));
		float lift = 0F;
		if (!carried.isEmpty() && ItemStack.isSameItem(carried, slot.getItem())) {
			// passendes Item zum getragenen: schwebt sanft auf und ab
			lift = -1.5F - 1.5F * (float) Math.sin(now / 1e9 * 3.2 + slot.index * 0.7);
		}
		float s = st[0];
		if (Math.abs(s - 1F) < 0.003F && lift == 0F) {
			return;
		}
		float cx = slot.x + 8F, cy = slot.y + 8F;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy + lift);
		g.pose().scale(s, s);
		g.pose().translate(-cx, -cy);
		pushedSlot = slot;
	}

	public static void afterSlot(GuiGraphics g, Slot slot) {
		if (pushedSlot != null && pushedSlot == slot) {
			g.pose().popMatrix();
			pushedSlot = null;
		}
	}

	// ------------------------------------------------------------------ getragenes Item

	/** Vor dem Zeichnen des getragenen Items: kippt bei schnellen Bewegungen nach (gefedert). */
	public static void beforeCarried(GuiGraphics g, int x, int y) {
		carriedPushed = false;
		if (!on()) {
			return;
		}
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0F : Math.min(0.05F, (now - lastFrame) / 1e9F);
		lastFrame = now;
		double mx = x;
		float vx = Double.isNaN(lastMx) || dt <= 0F ? 0F : (float) ((mx - lastMx) / dt);
		lastMx = mx;
		// Zielwinkel aus der Geschwindigkeit, dazu eine Feder für das Nachschwingen
		float targetAngle = Math.max(-0.6F, Math.min(0.6F, -vx * 0.0012F));
		wiggleV += ((targetAngle - wiggle) * 180F - wiggleV * 14F) * dt;
		wiggle += wiggleV * dt;
		if (Math.abs(wiggle) < 0.002F) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x + 8F, y + 2F); // um den „Griff“ oben am Item drehen
		g.pose().rotate(wiggle);
		g.pose().translate(-(x + 8F), -(y + 2F));
		carriedPushed = true;
	}

	public static void afterCarried(GuiGraphics g) {
		if (carriedPushed) {
			g.pose().popMatrix();
			carriedPushed = false;
		}
	}

	// ------------------------------------------------------------------ Funkenspur seltener Items

	private static void sparks(Screen screen, GuiGraphics g, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		if (on() && mc.player != null) {
			ItemStack carried = mc.player.containerMenu.getCarried();
			int rgb = carried.isEmpty() ? 0 : sparkColor(carried);
			if (rgb != 0) {
				sparkCarry += 0.6F;
				while (sparkCarry >= 1F) {
					sparkCarry -= 1F;
					float a = (float) (Math.random() * Math.PI * 2);
					float sp = 4F + (float) Math.random() * 10F;
					SPARKS.add(new Spark(mx + (float) (Math.random() * 10 - 5), my + (float) (Math.random() * 10 - 5),
							(float) Math.cos(a) * sp, (float) Math.sin(a) * sp + 6F, rgb, now));
				}
			}
		}
		if (SPARKS.size() > 120) {
			SPARKS.subList(0, SPARKS.size() - 120).clear();
		}
		g.nextStratum();
		for (Iterator<Spark> it = SPARKS.iterator(); it.hasNext(); ) {
			Spark s = it.next();
			float age = (now - s.born()) / 1e9F;
			if (age > 0.7F) {
				it.remove();
				continue;
			}
			float k = 1F - age / 0.7F;
			int x = (int) (s.x() + s.vx() * age), y = (int) (s.y() + s.vy() * age);
			int alpha = (int) (k * 220) << 24;
			int size = k > 0.5F ? 2 : 1;
			g.fill(x, y, x + size, y + size, alpha | s.rgb());
		}
	}

	/** Farbe der Spur: OPSUCHT-Seltenheit aus der Beschreibung, sonst die Minecraft-Seltenheit (gewöhnlich = keine). */
	private static int sparkColor(ItemStack stack) {
		int rgb = RarityFrames.rarityColor(stack);
		if (rgb != 0) {
			return rgb & 0xFFFFFF;
		}
		Rarity r = stack.getRarity();
		if (r == Rarity.COMMON) {
			return 0;
		}
		Integer c = r.color().getColor();
		return c == null ? 0xFFFFFF : c;
	}
}
