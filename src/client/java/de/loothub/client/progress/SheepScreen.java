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
	private static final int W = 320, H = 180, GROUND = 30, SW = 26, SH = 20, FENCE_W = 14;

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
		// wird immer schneller: erst zügig, ab Punkt 90 langsamer weiter, bis zu einem hohen Höchsttempo
		speed = score < 90 ? 3.2f + score * 0.06f : Math.min(14f, 8.6f + (score - 90) * 0.03f);
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
			// Höhe: doppelte Zäune werden häufiger, ab 40 Punkten auch dreifache
			float highChance = score < 8 ? 0 : Math.min(0.55f, 0.2f + score * 0.004f);
			int h = 1;
			if (score >= 40 && random.nextFloat() < Math.min(0.25f, (score - 40) * 0.005f)) {
				h = 3;
			} else if (random.nextFloat() < highChance) {
				h = 2;
			}
			fences.add(new Fence(fw + 10, h, new boolean[1]));
			// ab 25 Punkten manchmal ein zweiter niedriger Zaun direkt dahinter (mit einem Sprung über beide)
			if (score >= 25 && h == 1 && random.nextInt(5) == 0) {
				fences.add(new Fence(fw + 10 + 34, 1, new boolean[1]));
			}
			// Abstand: wird mit der Zeit kürzer, bleibt aber immer schaffbar
			int base = Math.max(70, 120 - score / 2), spread = Math.max(50, 140 - score);
			nextIn = base + random.nextInt(spread) + speed * 7 + (h == 3 ? 30 : 0);
		}
		// Treffer und Zählen
		int sheepX = 40;
		for (Fence f : fences) {
			int fh = f.h * 16;
			boolean overlapX = sheepX + SW - 5 > f.x && sheepX + 5 < f.x + FENCE_W;
			if (overlapX && -y < fh - 2) {
				gameOver();
				return;
			}
			if (!f.counted[0] && f.x + FENCE_W < sheepX) {
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
		long t = System.currentTimeMillis();
		float sc = lastScroll + (scrollX - lastScroll) * partial;
		// Nachtwiese (Ludo), scrollt langsam (Tiefenwirkung)
		int bgW = 392, bgOff = Math.floorMod(Math.round(sc * 0.25f), bgW);
		for (int x = -bgOff; x < fw; x += bgW) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_sheep_bg"), fx + x, fy, 0f, 0f, bgW, fh, 392, 160, 392, 160);
		}
		for (int[] s : stars) {
			boolean on = ((t / 400 + s[0]) % 9) == 0;
			if (s[0] < fw && on) {
				g.fill(fx + s[0], fy + s[1] / 2, fx + s[0] + 1, fy + s[1] / 2 + 1, 0xFFFFF6C8); // funkeln
			}
		}
		// Boden scrollt
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
		// Zäune (Ludo): einfach oder doppelt hoch
		for (Fence f : fences) {
			int x = fx + Math.round(f.x - speed * partial);
			int fhgt = f.h * 16 + 2;
			int w = Math.round(fhgt * 81f / 48f);
			g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_fence"), x - (w - FENCE_W) / 2, gy - fhgt + 1, 0f, 0f, w, fhgt, 81, 48, 81, 48);
		}
		float yy = lastY + (y - lastY) * partial;
		int bob = onGround ? (int) ((t / 120) % 2) : 0;
		g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_sheep"), fx + 40 - 4, gy - 30 + 4 + Math.round(yy) - bob, 0f, 0f, 34, 34, 64, 64, 64, 64);
	}
}
