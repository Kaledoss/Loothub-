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
 * Pinguin-Turm (wie Icy Tower): Der Pinguin springt von Eisplatte zu Eisplatte nach oben. Mit Anlauf springt er höher,
 * an den Wänden prallt er ab. Ab Stockwerk 5 zieht der Turm von selbst nach oben – wer unten herausfällt, verliert.
 */
final class TowerScreen extends ArcadeScreen {
	private static final Identifier ICE = Identifier.withDefaultNamespace("textures/block/packed_ice.png");
	private static final Identifier WALL = Identifier.withDefaultNamespace("textures/block/blue_ice.png");
	private static final int W = 240, GAP = 64, PLAT_H = 10, SIZE = 22, WALL_W = 12;

	private record Platform(int floor, float x, float w) {
	}

	private final Random random = new Random();
	private final List<Platform> platforms = new ArrayList<>();
	private float px, py, vx, vy, lastPx, lastPy;
	private float cam, lastCam, scroll;
	private boolean left, right, jumpHeld, onGround, facingLeft;
	private int best;

	TowerScreen(Screen parent) {
		super(parent, "tower");
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		return new int[]{W, Math.min(maxH, 320)};
	}

	@Override
	protected void reset() {
		platforms.clear();
		platforms.add(new Platform(0, WALL_W, W - 2 * WALL_W));
		for (int i = 1; i < 12; i++) {
			addPlatform(i);
		}
		px = W / 2f - SIZE / 2f;
		py = 0;
		vx = vy = 0;
		cam = lastCam = -(Math.max(200, fh) - 24); // Boden unten im Bild
		scroll = 0;
		best = 0;
		lastPx = px;
		lastPy = py;
		onGround = true;
	}

	@Override
	protected void init() {
		super.init();
		if (!started) {
			cam = lastCam = -(fh - 24);
		}
	}

	private void addPlatform(int floor) {
		// je höher, desto schmaler
		float w = Math.max(40, 120 - floor * 0.6f) + random.nextInt(30);
		float x = WALL_W + random.nextFloat() * (W - 2 * WALL_W - w);
		if (floor % 50 == 0) {
			x = WALL_W;
			w = W - 2 * WALL_W; // alle 50 Stockwerke ein ganzer Boden
		}
		platforms.add(new Platform(floor, x, w));
	}

	private static float floorY(int floor) {
		return -floor * GAP;
	}

	@Override
	protected void step() {
		lastPx = px;
		lastPy = py;
		lastCam = cam;
		// laufen mit Schwung
		float acc = onGround ? 1.1f : 0.7f;
		if (left) {
			vx -= acc;
			facingLeft = true;
		}
		if (right) {
			vx += acc;
			facingLeft = false;
		}
		if (!left && !right && onGround) {
			vx *= 0.8f;
		}
		vx = Math.max(-11, Math.min(11, vx));
		if (jumpHeld && onGround) {
			// je schneller, desto höher
			vy = -(11.5f + Math.abs(vx) * 0.75f);
			onGround = false;
			if (minecraft != null && minecraft.player != null) {
				minecraft.player.playSound(SoundEvents.SNOW_STEP, 0.6F, 1.4F);
			}
		}
		vy = Math.min(14, vy + 1.0f);
		px += vx;
		// Wände: abprallen
		if (px < WALL_W) {
			px = WALL_W;
			vx = -vx * 0.9f;
		} else if (px > W - WALL_W - SIZE) {
			px = W - WALL_W - SIZE;
			vx = -vx * 0.9f;
		}
		float oldY = py;
		py += vy;
		onGround = false;
		if (vy >= 0) {
			for (Platform p : platforms) {
				float top = floorY(p.floor);
				if (oldY <= top && py >= top && px + SIZE - 4 > p.x && px + 4 < p.x + p.w) {
					py = top;
					vy = 0;
					onGround = true;
					if (p.floor > best) {
						int jumped = p.floor - best;
						best = p.floor;
						score = best;
						if (jumped >= 2 && minecraft != null && minecraft.player != null) {
							minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.4F, 0.8F + Math.min(jumped, 8) * 0.1F);
						}
					}
					break;
				}
			}
		}
		// Kamera: folgt nach oben, ab Stockwerk 5 zieht sie von selbst (immer schneller)
		if (best >= 5) {
			scroll = Math.min(4.5f, 0.6f + best / 60f);
			cam -= scroll;
		}
		float topLimit = cam + fh * 0.35f;
		if (py - SIZE < topLimit) {
			cam -= topLimit - (py - SIZE);
		}
		// neue Platten oben, alte unten weg
		int maxFloor = platforms.get(platforms.size() - 1).floor;
		while (floorY(maxFloor) > cam - fh) {
			addPlatform(++maxFloor);
		}
		platforms.removeIf(p -> floorY(p.floor) > cam + fh + 2 * GAP);
		// unten herausgefallen?
		if (py - SIZE > cam + fh) {
			gameOver();
		}
	}

	@Override
	protected boolean control(int k, boolean down) {
		switch (k) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> left = down;
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> right = down;
			case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> jumpHeld = down;
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		// Leertaste springt; beim ersten Druck startet das Spiel
		if (!over && !started && (e.key() == GLFW.GLFW_KEY_SPACE)) {
			jumpHeld = true;
			begin();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		float c = lastCam + (cam - lastCam) * partial;
		// Hintergrund: Eisturm (Ludo), scrollt langsamer mit (Tiefenwirkung)
		int bgOff = Math.floorMod(Math.round(-c * 0.35f), 288);
		for (int y = bgOff - 288; y < fh; y += 288) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_tower_bg"), fx, fy + y, 0f, 0f, fw, 288, 216, 288, 216, 288);
		}
		// Wände aus blauem Eis
		for (int y = (int) (-(c % 16) - 16); y < fh + 16; y += 16) {
			g.blit(RenderPipelines.GUI_TEXTURED, WALL, fx, fy + y, 0f, 0f, WALL_W, 16, 16, 16, 16, 16);
			g.blit(RenderPipelines.GUI_TEXTURED, WALL, fx + fw - WALL_W, fy + y, 0f, 0f, WALL_W, 16, 16, 16, 16, 16);
		}
		for (Platform p : platforms) {
			int sy = fy + Math.round(floorY(p.floor) - c);
			if (sy < fy - 20 || sy > fy + fh + 20) {
				continue;
			}
			int sx = fx + Math.round(p.x);
			for (int x = 0; x < p.w; x += 16) {
				int w = (int) Math.min(16, p.w - x);
				g.blit(RenderPipelines.GUI_TEXTURED, ICE, sx + x, sy, 0f, 0f, w, PLAT_H, w, PLAT_H, 16, 16);
			}
			g.fill(sx, sy, sx + Math.round(p.w), sy + 1, 0xFFE8F6FF);
			if (p.floor > 0 && p.floor % 10 == 0) {
				String n = String.valueOf(p.floor);
				g.drawString(font, n, sx + (Math.round(p.w) - font.width(n)) / 2, sy + 1, 0xFF1A2C52, false);
			}
		}
		float x = lastPx + (px - lastPx) * partial, y = lastPy + (py - lastPy) * partial;
		int sx = fx + Math.round(x), sy = fy + Math.round(y - c) - SIZE;
		g.blit(RenderPipelines.GUI_TEXTURED, tex(facingLeft ? "mg_penguin_l" : "mg_penguin"), sx - 3, sy - 6, 0f, 0f, SIZE + 6, SIZE + 6, 64, 64,
				64, 64);
		// Tempo-Anzeige links oben
		if (scroll > 0) {
			g.drawString(font, "▲", fx + WALL_W + 3, fy + 3, scroll > 3 ? 0xFFFF6B6B : 0xFFB9D7FF, true);
		}
	}
}
