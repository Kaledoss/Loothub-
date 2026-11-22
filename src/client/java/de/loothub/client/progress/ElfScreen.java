package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * Elfen-Sprung (wie Doodle Jump): Die Elfe hüpft von selbst; man lenkt nur nach links und rechts (am Rand geht es auf
 * der anderen Seite weiter). Schneeplatten, wandernde Eisplatten, brüchige Platten und Geschenke als Sprungfeder.
 */
final class ElfScreen extends ArcadeScreen {
	private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/block/snow.png");
	private static final Identifier ICE = Identifier.withDefaultNamespace("textures/block/blue_ice.png");
	private static final Identifier CRACK = Identifier.withDefaultNamespace("textures/block/powder_snow.png");
	private static final int W = 220, PW = 40, PH = 8, SIZE = 22;
	private static final float JUMP = -10.5f, SPRING = -19f, GRAV = 0.42f;

	private static final class Plat {
		float x, y, dir;
		int kind; // 0 Schnee, 1 wandert, 2 brüchig
		boolean gift, broken;

		Plat(float x, float y, int kind) {
			this.x = x;
			this.y = y;
			this.kind = kind;
			this.dir = kind == 1 ? 1.2f : 0;
		}
	}

	private final Random random = new Random();
	private final List<Plat> plats = new ArrayList<>();
	private float px, py, vx, vy, lastPx, lastPy, cam, lastCam, top;
	private boolean left, right, facingLeft;
	private float highest;

	ElfScreen(Screen parent) {
		super(parent, "elf");
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		return new int[]{W, Math.min(maxH, 320)};
	}

	@Override
	protected void reset() {
		plats.clear();
		px = lastPx = W / 2f - SIZE / 2f;
		py = lastPy = 0;
		vx = 0;
		vy = JUMP;
		cam = lastCam = -(Math.max(200, fh) - 30);
		highest = 0;
		plats.add(new Plat(W / 2f - PW / 2f, 4, 0));
		top = 4;
		fill();
	}

	@Override
	protected void init() {
		super.init();
		if (!started) {
			reset(); // Kamera an die echte Feldhöhe anpassen
		}
	}

	/** Neue Platten oberhalb anlegen; mit der Höhe werden die Abstände größer und Sonderplatten häufiger. */
	private void fill() {
		while (top > cam - 80) {
			float h = -top;
			float gap = Math.min(78, 34 + h / 160f) + random.nextInt(14);
			top -= gap;
			int kind = 0;
			int r = random.nextInt(100);
			if (h > 600 && r < Math.min(30, h / 150)) {
				kind = 1;
			} else if (h > 1200 && r > 100 - Math.min(20, h / 300)) {
				kind = 2;
			}
			Plat p = new Plat(random.nextFloat() * (W - PW), top, kind);
			p.gift = kind == 0 && random.nextInt(14) == 0;
			plats.add(p);
			// brüchige Platten nie allein: daneben immer eine feste
			if (kind == 2) {
				plats.add(new Plat(random.nextFloat() * (W - PW), top - gap / 2, 0));
			}
		}
	}

	@Override
	protected void step() {
		lastPx = px;
		lastPy = py;
		lastCam = cam;
		if (left) {
			vx = Math.max(-5.5f, vx - 0.9f);
			facingLeft = true;
		} else if (right) {
			vx = Math.min(5.5f, vx + 0.9f);
			facingLeft = false;
		} else {
			vx *= 0.82f;
		}
		px += vx;
		// am Rand auf der anderen Seite wieder herein
		if (px + SIZE / 2f < 0) {
			px += W;
			lastPx = px;
		} else if (px + SIZE / 2f > W) {
			px -= W;
			lastPx = px;
		}
		float oldY = py;
		vy += GRAV;
		py += vy;
		for (Plat p : plats) {
			if (p.kind == 1) {
				p.x += p.dir;
				if (p.x < 0 || p.x > W - PW) {
					p.dir = -p.dir;
				}
			}
		}
		if (vy > 0) {
			for (Plat p : plats) {
				if (p.broken || !(oldY <= p.y && py >= p.y && px + SIZE - 5 > p.x && px + 5 < p.x + PW)) {
					continue;
				}
				if (p.kind == 2) {
					p.broken = true; // bricht ein, trägt nicht
					sound(SoundEvents.SNOW_BREAK, 0.9F);
					continue;
				}
				py = p.y;
				boolean spring = p.gift && px + SIZE / 2f > p.x + PW / 2f - 9 && px + SIZE / 2f < p.x + PW / 2f + 9;
				vy = spring ? SPRING : JUMP;
				sound(spring ? SoundEvents.FIREWORK_ROCKET_LAUNCH : SoundEvents.SNOW_STEP, spring ? 1.2F : 1.6F);
				break;
			}
		}
		highest = Math.max(highest, -py);
		score = (int) (highest / 10);
		float limit = cam + fh * 0.4f;
		if (py - SIZE < limit) {
			cam -= limit - (py - SIZE);
		}
		fill();
		plats.removeIf(p -> p.y > cam + fh + 40);
		if (py - SIZE > cam + fh) {
			gameOver();
		}
	}

	private void sound(net.minecraft.sounds.SoundEvent s, float pitch) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(s, 0.45F, pitch);
		}
	}

	@Override
	protected boolean control(int k, boolean down) {
		switch (k) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> left = down;
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> right = down;
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		float c = lastCam + (cam - lastCam) * partial;
		// Winterhimmel; wird mit der Höhe dunkler
		float h = Math.min(1f, highest / 6000f);
		g.fillGradient(fx, fy, fx + fw, fy + fh, SnakeScreen.lerp(0xFF7FB2E0, 0xFF0A1030, h), SnakeScreen.lerp(0xFFCFE4F5, 0xFF2A3A70, h));
		// fallende Schneeflocken (Deko)
		long t = System.currentTimeMillis();
		for (int i = 0; i < 24; i++) {
			int sx = (int) ((i * 53 + t / 40 * (i % 3 + 1) / 3) % fw);
			int sy = (int) ((i * 97 + t / 25 * (i % 2 + 1)) % fh);
			g.fill(fx + sx, fy + sy, fx + sx + 1 + i % 2, fy + sy + 1 + i % 2, 0xC0FFFFFF);
		}
		for (Plat p : plats) {
			int sx = fx + Math.round(p.x), sy = fy + Math.round(p.y - c);
			if (sy < fy - 30 || sy > fy + fh + 10) {
				continue;
			}
			if (p.broken) {
				g.fill(sx + 4, sy + 2, sx + PW / 2 - 2, sy + PH, 0x60FFFFFF);
				g.fill(sx + PW / 2 + 2, sy + 4, sx + PW - 4, sy + PH + 2, 0x60FFFFFF);
				continue;
			}
			Identifier tx = p.kind == 1 ? ICE : p.kind == 2 ? CRACK : SNOW;
			for (int x = 0; x < PW; x += 16) {
				int w = Math.min(16, PW - x);
				g.blit(RenderPipelines.GUI_TEXTURED, tx, sx + x, sy, 0f, 0f, w, PH, w, PH, 16, 16);
			}
			if (p.kind == 2) {
				g.fill(sx + PW / 2, sy, sx + PW / 2 + 1, sy + PH, 0xFF8090A0);
			}
			if (p.gift) {
				// Geschenk als Sprungfeder
				g.fill(sx + PW / 2 - 6, sy - 10, sx + PW / 2 + 6, sy, 0xFFD83A3A);
				g.fill(sx + PW / 2 - 1, sy - 10, sx + PW / 2 + 1, sy, 0xFFF2C14E);
				g.fill(sx + PW / 2 - 6, sy - 6, sx + PW / 2 + 6, sy - 4, 0xFFF2C14E);
				g.fill(sx + PW / 2 - 3, sy - 13, sx + PW / 2, sy - 10, 0xFFF2C14E);
				g.fill(sx + PW / 2 + 1, sy - 13, sx + PW / 2 + 4, sy - 10, 0xFFF2C14E);
			}
		}
		float x = lastPx + (px - lastPx) * partial, y = lastPy + (py - lastPy) * partial;
		int sx = fx + Math.round(x), sy = fy + Math.round(y - c) - SIZE;
		Identifier elf = tex(facingLeft ? "mg_elf_l" : "mg_elf");
		g.blit(RenderPipelines.GUI_TEXTURED, elf, sx, sy, 0f, 0f, SIZE, SIZE, 16, 16, 16, 16);
		// am Rand: halb auf der anderen Seite zeigen
		if (sx < fx) {
			g.blit(RenderPipelines.GUI_TEXTURED, elf, sx + fw, sy, 0f, 0f, SIZE, SIZE, 16, 16, 16, 16);
		} else if (sx + SIZE > fx + fw) {
			g.blit(RenderPipelines.GUI_TEXTURED, elf, sx - fw, sy, 0f, 0f, SIZE, SIZE, 16, 16, 16, 16);
		}
	}
}
