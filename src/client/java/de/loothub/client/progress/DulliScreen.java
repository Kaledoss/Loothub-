package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Dulli-Jagd: Dulli-Hühner flattern über die Wiese – anklicken zum Treffen. 90 Sekunden, 8 Schuss im Magazin,
 * Rechtsklick (oder R/Leertaste) lädt nach. Weit entfernte (kleine) Hühner geben mehr Punkte, Treffer in Folge
 * geben einen Bonus.
 */
final class DulliScreen extends ArcadeScreen {
	private static final Identifier DULLI = tex("mg_dulli");
	private static final Identifier BG = tex("mg_dulli_bg");
	private static final int W = 320, H = 180, TIME = 90 * 20, MAG = 8;

	private static final class Chicken {
		float x, y, lx, ly, vx, vy, size, phase;
		boolean hit;
		int fallTicks;
	}

	private record Feather(float x, float y, float vx, float vy, int life) {
	}

	private record Popup(float x, float y, String text, int life) {
	}

	private final Random random = new Random();
	private final List<Chicken> chickens = new ArrayList<>();
	private final List<Feather> feathers = new ArrayList<>();
	private final List<Popup> popups = new ArrayList<>();
	private int timeLeft, ammo, reloadTicks, combo, spawnIn, ticks;
	private int mouseX, mouseY;

	DulliScreen(Screen parent) {
		super(parent, "dulli");
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		return new int[]{Math.min(maxW, W), Math.min(maxH, H)};
	}

	@Override
	protected void reset() {
		chickens.clear();
		feathers.clear();
		popups.clear();
		timeLeft = TIME;
		ammo = MAG;
		reloadTicks = 0;
		combo = 0;
		spawnIn = 10;
		ticks = 0;
	}

	@Override
	protected String scoreText() {
		return Component.translatable("loothub.lh.mg.dulli.score", score, (timeLeft + 19) / 20, best(game)).getString();
	}

	private void spawn() {
		Chicken c = new Chicken();
		float depth = random.nextFloat(); // 0 = nah (groß), 1 = weit (klein)
		c.size = 44 - depth * 30;
		boolean fromLeft = random.nextBoolean();
		float speed = (1.4f + random.nextFloat() * 1.6f) * (1.25f - depth * 0.5f) * (1f + Math.min(1f, ticks / 1200f) * 0.6f);
		c.x = c.lx = fromLeft ? -c.size : W + c.size;
		c.vx = fromLeft ? speed : -speed;
		c.y = c.ly = 14 + random.nextFloat() * (H - 80);
		c.vy = (random.nextFloat() - 0.5f) * 0.6f;
		c.phase = random.nextFloat() * 6.28f;
		chickens.add(c);
	}

	@Override
	protected void step() {
		ticks++;
		if (--timeLeft <= 0) {
			gameOver();
			return;
		}
		if (reloadTicks > 0 && --reloadTicks == 0) {
			ammo = MAG;
			sound(SoundEvents.CROSSBOW_LOADING_END.value(), 0.6f, 1.2f);
		}
		if (--spawnIn <= 0) {
			spawn();
			if (random.nextFloat() < Math.min(0.5f, ticks / 2400f)) {
				spawn(); // später kommen oft zwei auf einmal
			}
			spawnIn = Math.max(8, 28 - ticks / 90) + random.nextInt(12);
		}
		for (Chicken c : chickens) {
			c.lx = c.x;
			c.ly = c.y;
			if (c.hit) {
				c.vy += 0.6f;
				c.y += c.vy;
				c.x += c.vx * 0.2f;
				c.fallTicks++;
			} else {
				c.phase += 0.35f;
				c.x += c.vx;
				c.y += c.vy + (float) Math.sin(c.phase) * 0.7f; // flatternd auf und ab
				if (c.y < 6 || c.y > H - 70) {
					c.vy = -c.vy;
				}
			}
		}
		chickens.removeIf(c -> c.x < -60 || c.x > W + 60 || c.y > H + 40);
		for (int i = 0; i < feathers.size(); i++) {
			Feather f = feathers.get(i);
			feathers.set(i, new Feather(f.x + f.vx, f.y + f.vy, f.vx * 0.94f, f.vy * 0.9f + 0.12f, f.life - 1));
		}
		feathers.removeIf(f -> f.life <= 0);
		for (int i = 0; i < popups.size(); i++) {
			Popup p = popups.get(i);
			popups.set(i, new Popup(p.x, p.y - 0.6f, p.text, p.life - 1));
		}
		popups.removeIf(p -> p.life <= 0);
	}

	private void shoot(float gx, float gy) {
		if (reloadTicks > 0) {
			return;
		}
		if (ammo <= 0) {
			sound(SoundEvents.DISPENSER_FAIL, 0.5f, 1.4f); // leer: nachladen!
			return;
		}
		ammo--;
		sound(SoundEvents.GENERIC_EXPLODE.value(), 0.25f, 1.9f);
		Chicken target = null;
		// das vorderste (größte) Huhn unter dem Fadenkreuz
		for (Chicken c : chickens) {
			if (c.hit) {
				continue;
			}
			float r = c.size * 0.42f;
			if (Math.abs(gx - c.x) < r && Math.abs(gy - c.y) < r * 1.1f && (target == null || c.size > target.size)) {
				target = c;
			}
		}
		if (target == null) {
			combo = 0;
			return;
		}
		target.hit = true;
		target.vy = -3f;
		combo++;
		int pts = target.size < 22 ? 25 : target.size < 34 ? 10 : 5;
		int bonus = combo >= 3 ? combo - 2 : 0; // Treffer in Folge
		score += pts + bonus;
		popups.add(new Popup(target.x, target.y - target.size / 2, "+" + (pts + bonus), 24));
		for (int i = 0; i < 10; i++) {
			feathers.add(new Feather(target.x, target.y, (random.nextFloat() - 0.5f) * 5f, (random.nextFloat() - 0.8f) * 4f,
					18 + random.nextInt(14)));
		}
		sound(SoundEvents.CHICKEN_HURT, 0.6f, 0.9f + random.nextFloat() * 0.4f);
	}

	private void reload() {
		if (reloadTicks == 0 && ammo < MAG) {
			reloadTicks = 16;
			sound(SoundEvents.CROSSBOW_LOADING_START.value(), 0.6f, 1.1f);
		}
	}

	private void sound(net.minecraft.sounds.SoundEvent s, float vol, float pitch) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(s, vol, pitch);
		}
	}

	@Override
	protected boolean control(int key, boolean down) {
		if (down && (key == GLFW.GLFW_KEY_R || key == GLFW.GLFW_KEY_SPACE) && started) {
			reload();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (over || paused) {
			return super.mouseClicked(e, doubleClick);
		}
		boolean inField = e.x() >= fx && e.x() < fx + fw && e.y() >= fy && e.y() < fy + fh;
		if (!inField) {
			return super.mouseClicked(e, doubleClick);
		}
		if (!started) {
			begin();
			return true;
		}
		float sx = (float) (e.x() - fx) * W / fw, sy = (float) (e.y() - fy) * H / fh;
		if (e.button() == 1) {
			reload();
		} else if (e.button() == 0) {
			shoot(sx, sy);
		}
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		mouseX = mx;
		mouseY = my;
		super.render(g, mx, my, delta);
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		float sx = (float) fw / W, sy = (float) fh / H;
		g.blit(RenderPipelines.GUI_TEXTURED, BG, fx, fy, 0f, 0f, fw, fh, 640, 360, 640, 360);
		// Hühner (weit entfernte zuerst)
		List<Chicken> order = new ArrayList<>(chickens);
		order.sort((a, b) -> Float.compare(a.size, b.size));
		for (Chicken c : order) {
			float x = c.lx + (c.x - c.lx) * partial, y = c.ly + (c.y - c.ly) * partial;
			// Flügelschlag: leicht stauchen und strecken
			float flap = c.hit ? 1f : 1f + 0.08f * (float) Math.sin(c.phase * 2);
			int w = Math.round(c.size * sx / flap), h = Math.round(c.size * sy * flap);
			int dx = fx + Math.round(x * sx) - w / 2, dy = fy + Math.round(y * sy) - h / 2;
			if (c.hit) {
				g.pose().pushMatrix();
				g.pose().translate(dx + w / 2f, dy + h / 2f);
				g.pose().rotate(c.fallTicks * 0.25f * Math.signum(c.vx));
				g.blit(RenderPipelines.GUI_TEXTURED, DULLI, -w / 2, -h / 2, 0f, 0f, w, h, 96, 96, 96, 96);
				g.pose().popMatrix();
			} else {
				g.blit(RenderPipelines.GUI_TEXTURED, DULLI, dx, dy, 0f, 0f, w, h, 96, 96, 96, 96);
			}
		}
		for (Feather f : feathers) {
			int x = fx + Math.round(f.x * sx), y = fy + Math.round(f.y * sy);
			g.fill(x, y, x + 2, y + 1, 0xF0FFFFFF);
		}
		for (Popup p : popups) {
			g.drawCenteredString(font, p.text, fx + Math.round(p.x * sx), fy + Math.round(p.y * sy), 0xFFFFE066);
		}
		// Magazin unten rechts
		for (int i = 0; i < MAG; i++) {
			int x = fx + fw - 8 - i * 7, y = fy + fh - 14;
			boolean full = i < ammo;
			g.fill(x, y, x + 4, y + 10, full ? 0xFFE23B3B : 0x60000000);
			g.fill(x, y, x + 4, y + 3, full ? 0xFFF2C14E : 0x40000000);
		}
		if (ammo == 0 && reloadTicks == 0 && started && !over) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.dulli.reload"), fx + fw / 2, fy + fh - 24, 0xFFFF6B6B);
		}
		// Fadenkreuz
		if (mouseX >= fx && mouseX < fx + fw && mouseY >= fy && mouseY < fy + fh && !over) {
			int c = 0xFFFF3B3B;
			g.fill(mouseX - 6, mouseY, mouseX - 2, mouseY + 1, c);
			g.fill(mouseX + 3, mouseY, mouseX + 7, mouseY + 1, c);
			g.fill(mouseX, mouseY - 6, mouseX + 1, mouseY - 2, c);
			g.fill(mouseX, mouseY + 3, mouseX + 1, mouseY + 7, c);
		}
	}
}
