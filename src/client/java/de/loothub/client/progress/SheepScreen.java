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
 * Schäfchen zählen: Das Schaf läuft unter dem Nachthimmel und springt über Zäune. Leertaste (gedrückt halten = höher).
 * Die Zäune kommen immer schneller, manche sind doppelt hoch.
 */
final class SheepScreen extends ArcadeScreen {
	private static final Identifier GRASS = Identifier.withDefaultNamespace("textures/block/grass_block_top.png");
	private static final Identifier DIRT = Identifier.withDefaultNamespace("textures/block/dirt.png");
	private static final int W = 320, H = 180, GROUND = 30, SW = 26, SH = 20;

	private record Fence(float x, int h, boolean[] counted) {
	}

	private final Random random = new Random();
	private final List<Fence> fences = new ArrayList<>();
	private final int[][] stars = new int[40][3];
	private float y, vy, lastY;
	private float speed, scrollX, lastScroll;
	private float nextIn;
	private boolean jumpHeld, onGround;
	private int holdTicks;

	SheepScreen(Screen parent) {
		super(parent, "sheep");
		for (int[] s : stars) {
			s[0] = random.nextInt(W);
			s[1] = random.nextInt(H - GROUND - 40);
			s[2] = random.nextInt(3);
		}
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		return new int[]{Math.min(maxW, W), Math.min(maxH, H)};
	}

	@Override
	protected void reset() {
		fences.clear();
		y = lastY = 0;
		vy = 0;
		speed = 3.2f;
		scrollX = lastScroll = 0;
		nextIn = 140;
		onGround = true;
	}

	@Override
	protected void step() {
		lastY = y;
		lastScroll = scrollX;
		scrollX += speed;
		speed = Math.min(8.5f, 3.2f + score * 0.06f);
		// springen: gedrückt halten trägt etwas länger
		if (jumpHeld && onGround) {
			vy = -8.2f;
			onGround = false;
			holdTicks = 0;
			if (minecraft != null && minecraft.player != null) {
				minecraft.player.playSound(SoundEvents.SHEEP_AMBIENT, 0.35F, 1.3F + random.nextFloat() * 0.3F);
			}
		}
		if (!onGround) {
			boolean hold = jumpHeld && holdTicks++ < 7 && vy < 0;
			vy += hold ? 0.45f : 0.95f;
			y += vy;
			if (y >= 0) {
				y = 0;
				vy = 0;
				onGround = true;
			}
		}
		// Zäune bewegen, neue anlegen
		for (int i = 0; i < fences.size(); i++) {
			Fence f = fences.get(i);
			fences.set(i, new Fence(f.x - speed, f.h, f.counted));
		}
		fences.removeIf(f -> f.x < -30);
		nextIn -= speed;
		if (nextIn <= 0) {
			boolean high = score >= 8 && random.nextInt(4) == 0;
			fences.add(new Fence(fw + 10, high ? 2 : 1, new boolean[1]));
			// Abstand: zufällig, aber immer schaffbar
			nextIn = 120 + random.nextInt(140) + speed * 8;
		}
		// Treffer und Zählen
		int sheepX = 40;
		for (Fence f : fences) {
			int fh = f.h * 16;
			boolean overlapX = sheepX + SW - 5 > f.x && sheepX + 5 < f.x + 8;
			if (overlapX && -y < fh - 2) {
				gameOver();
				return;
			}
			if (!f.counted[0] && f.x + 8 < sheepX) {
				f.counted[0] = true;
				score++;
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.3F, 1.0F + Math.min(score, 40) * 0.01F);
				}
			}
		}
	}

	@Override
	protected boolean control(int k, boolean down) {
		if (k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_W) {
			jumpHeld = down;
			return true;
		}
		return false;
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		g.fillGradient(fx, fy, fx + fw, fy + fh, 0xFF070B22, 0xFF1C2A55);
		long t = System.currentTimeMillis();
		for (int[] s : stars) {
			boolean on = ((t / 400 + s[0]) % 7) != 0;
			if (s[0] < fw && on) {
				g.fill(fx + s[0], fy + s[1], fx + s[0] + 1 + s[2] / 2, fy + s[1] + 1 + s[2] / 2, 0xFFFFF6C8);
			}
		}
		// Mond
		g.fill(fx + fw - 46, fy + 14, fx + fw - 26, fy + 34, 0xFFFFF3B0);
		g.fill(fx + fw - 40, fy + 14, fx + fw - 26, fy + 28, 0xFF1A2550);
		// Boden scrollt
		float sc = lastScroll + (scrollX - lastScroll) * partial;
		int off = Math.round(sc) % 16;
		int gy = fy + fh - GROUND;
		for (int x = -off; x < fw; x += 16) {
			g.blit(RenderPipelines.GUI_TEXTURED, GRASS, fx + x, gy, 0f, 0f, 16, 6, 16, 6, 16, 16);
			for (int yy = gy + 6; yy < fy + fh; yy += 16) {
				g.blit(RenderPipelines.GUI_TEXTURED, DIRT, fx + x, yy, 0f, 0f, 16, 16, 16, 16, 16, 16);
			}
		}
		g.fill(fx, gy, fx + fw, gy + 6, 0x5A1F6B1F);
		g.fill(fx, gy, fx + fw, fy + fh, 0x40000020); // Nacht
		// Zäune (Eichenzaun: Pfosten und zwei Latten)
		for (Fence f : fences) {
			int x = fx + Math.round(f.x - speed * partial);
			int top = gy - f.h * 16;
			g.fill(x + 2, top, x + 6, gy, 0xFF9C7A4A);
			g.fill(x + 2, top, x + 3, gy, 0xFFB8946A);
			for (int r = 0; r < f.h; r++) {
				int ry = gy - r * 16;
				g.fill(x - 3, ry - 13, x + 11, ry - 11, 0xFF8A6B3E);
				g.fill(x - 3, ry - 7, x + 11, ry - 5, 0xFF8A6B3E);
			}
		}
		float yy = lastY + (y - lastY) * partial;
		int bob = onGround ? (int) ((t / 120) % 2) : 0;
		g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_sheep"), fx + 40, gy - SH + Math.round(yy) - bob, 0f, 0f, SW, SH, 16, 12, 16, 12);
	}
}
