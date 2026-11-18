package de.loothub.client.bau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.joml.Matrix3x2f;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import de.loothub.Loothub;
import de.loothub.client.Fmt;

/**
 * 3D-Ansicht einer Schematic: alle Blöcke mit ihren Texturen, frei drehbar. Mausrad = drehen (360°),
 * Umschalt + Mausrad = Zoom, Ziehen = verschieben, rechte Maustaste ziehen = kippen. Ebenen lassen sich ausblenden.
 */
public final class Preview extends Screen {
	/** Ein Block der Schematic (relativ zur Schematic). */
	record Block(int x, int y, int z, BlockState state, boolean opaque) {
	}

	private static final int MAX_LOAD = 2_000_000;
	/** Höchstens so viele Flächen zeichnen (sonst wird es zu langsam). */
	private static final int MAX_FACES = 200_000;
	private static final Direction[] DIRS = Direction.values();

	private final Screen parent;
	private volatile List<Block> blocks;
	private boolean truncated, drawCut;
	private int minY, maxY, layer;
	private boolean layerOnly, ready;
	private float yaw = 45F, pitch = 30F;
	private float zoom = 8F, panX, panY;
	private boolean dragging, tilting;
	private double lastX, lastY;
	/** Gerechnetes Bild: je Fläche 4 Ecken (x, y) und Textur (u0, v0, u1, v1); dazu je Fläche eine Farbe. */
	private float[] mesh = new float[0];
	private int[] colors = new int[0];
	private boolean[] reversed = new boolean[0];
	private int faces, shownBlocks;
	private Identifier atlas;
	private float cx, cy, cz;

	public Preview(Screen parent, Plan plan) {
		super(Component.translatable("loothub.build.preview.title", plan.name));
		this.parent = parent;
		CompletableFuture.runAsync(() -> {
			try {
				List<Block> list = LitematicaBridge.previewBlocks(plan, MAX_LOAD);
				truncated = list.size() >= MAX_LOAD;
				blocks = list;
			} catch (RuntimeException | LinkageError e) {
				Loothub.LOGGER.warn("3D-Ansicht konnte nicht geladen werden", e);
				blocks = List.of();
			}
		});
	}

	@Override
	protected void init() {
		int y = height - 24;
		int x = 8;
		x = button(x, y, Component.translatable("loothub.build.preview.layer_down").getString(), b -> layer(-1));
		x = button(x, y, Component.translatable("loothub.build.preview.layer_up").getString(), b -> layer(1));
		x = button(x, y, Component.translatable("loothub.build.preview.layer_mode").getString(), b -> {
			layerOnly = !layerOnly;
			rebuild();
		});
		x = button(x, y, Component.translatable("loothub.build.preview.all").getString(), b -> {
			layer = maxY;
			layerOnly = false;
			rebuild();
		});
		x += 8;
		button(x, y, Component.translatable("loothub.build.preview.reset").getString(), b -> {
			yaw = 45F;
			pitch = 30F;
			panX = 0;
			panY = 0;
			fitZoom();
			rebuild();
		});
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width - 78, y, 70, 16).build());
	}

	private int button(int x, int y, String label, Button.OnPress press) {
		int w = font.width(label) + 14;
		addRenderableWidget(Button.builder(Component.literal(label), press).bounds(x, y, w, 16).build());
		return x + w + 4;
	}

	private void layer(int d) {
		layer = Math.max(minY, Math.min(maxY, layer + d));
		rebuild();
	}

	@Override
	public void tick() {
		List<Block> all = blocks;
		if (!ready && all != null) {
			ready = true;
			if (all.isEmpty()) {
				return;
			}
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
			for (Block b : all) {
				lo = Math.min(lo, b.y());
				hi = Math.max(hi, b.y());
				x0 = Math.min(x0, b.x());
				x1 = Math.max(x1, b.x());
				z0 = Math.min(z0, b.z());
				z1 = Math.max(z1, b.z());
			}
			minY = lo;
			maxY = hi;
			layer = hi;
			cx = (x0 + x1 + 1) / 2F;
			cy = (lo + hi + 1) / 2F;
			cz = (z0 + z1 + 1) / 2F;
			fitZoom();
			rebuild();
		}
	}

	/** Zoom so wählen, dass die ganze Schematic ins Bild passt. */
	private void fitZoom() {
		List<Block> all = blocks;
		if (all == null || all.isEmpty()) {
			return;
		}
		double r = 1;
		for (Block b : all) {
			double dx = b.x() + 0.5 - cx, dy = b.y() + 0.5 - cy, dz = b.z() + 0.5 - cz;
			r = Math.max(r, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		zoom = (float) Math.max(0.5, Math.min(40, Math.min(width, height - 40) / (2.2 * r)));
	}

	// ------------------------------------------------------------------ Bild rechnen

	/** Textur einer Blockseite (aus dem Blockmodell) und Färbung (Gras, Laub …; -1 = keine). */
	private record Face(float u0, float v0, float u1, float v1, int tint) {
	}

	private final Map<BlockState, Face[]> faceCache = new HashMap<>();
	private final RandomSource random = RandomSource.create(42L);

	private Face[] faces(BlockState state) {
		return faceCache.computeIfAbsent(state, st -> {
			Face[] out = new Face[6];
			Minecraft mc = Minecraft.getInstance();
			try {
				var model = mc.getModelManager().getBlockModelShaper().getBlockModel(st);
				var parts = model.collectParts(random);
				TextureAtlasSprite fallback = model.particleIcon();
				for (Direction d : DIRS) {
					BakedQuad q = null;
					for (var part : parts) {
						List<BakedQuad> qs = part.getQuads(d);
						if (!qs.isEmpty()) {
							q = qs.get(0);
							break;
						}
					}
					if (q == null) {
						for (var part : parts) {
							List<BakedQuad> qs = part.getQuads(null);
							if (!qs.isEmpty()) {
								q = qs.get(0);
								break;
							}
						}
					}
					TextureAtlasSprite sprite = q != null ? q.sprite() : fallback;
					if (atlas == null) {
						atlas = sprite.atlasLocation();
					}
					int tint = -1;
					if (q != null && q.isTinted()) {
						int c = mc.getBlockColors().getColor(st, null, null, q.tintIndex());
						tint = c == -1 ? 0xFF7CBD6B : 0xFF000000 | c; // ohne Welt: übliches Grasgrün
					}
					out[d.ordinal()] = new Face(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), tint);
				}
			} catch (RuntimeException e) {
				// Modell nicht lesbar: graue Fläche
			}
			return out;
		});
	}

	/** Ecken einer Würfelseite (relativ zur Blockecke): oben-links, oben-rechts, unten-rechts, unten-links. */
	private static final float[][][] CORNERS = new float[6][][];

	static {
		CORNERS[Direction.DOWN.ordinal()] = new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
		CORNERS[Direction.UP.ordinal()] = new float[][]{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
		CORNERS[Direction.NORTH.ordinal()] = new float[][]{{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}};
		CORNERS[Direction.SOUTH.ordinal()] = new float[][]{{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}};
		CORNERS[Direction.WEST.ordinal()] = new float[][]{{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}};
		CORNERS[Direction.EAST.ordinal()] = new float[][]{{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}};
	}

	/** Helligkeit je Seite (oben hell, unten dunkel) – damit die Form erkennbar ist. */
	private static float shade(Direction d) {
		return switch (d) {
			case UP -> 1.0F;
			case DOWN -> 0.5F;
			case NORTH, SOUTH -> 0.8F;
			default -> 0.65F;
		};
	}

	/** Sichtbare Flächen neu berechnen (nach Drehen, Kippen oder Ebenenwechsel) und von hinten nach vorn ordnen. */
	private void rebuild() {
		List<Block> all = blocks;
		if (all == null || all.isEmpty()) {
			return;
		}
		double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
		float cyw = (float) Math.cos(ry), syw = (float) Math.sin(ry), cp = (float) Math.cos(rp), sp = (float) Math.sin(rp);
		// Zeigt die Seite zum Betrachter?
		boolean[] facing = new boolean[6];
		for (Direction d : DIRS) {
			float z1 = -d.getStepX() * syw + d.getStepZ() * cyw;
			facing[d.ordinal()] = d.getStepY() * sp + z1 * cp > 0.001F;
		}
		Set<Long> solid = new HashSet<>();
		List<Block> shown = new ArrayList<>();
		for (Block b : all) {
			if (b.y() > layer || (layerOnly && b.y() != layer)) {
				continue;
			}
			shown.add(b);
			if (b.opaque()) {
				solid.add(BlockPos.asLong(b.x(), b.y(), b.z()));
			}
		}
		// je Block die sichtbaren Seiten; ganz verdeckte Blöcke fallen weg
		List<Block> drawn = new ArrayList<>();
		List<float[]> keys = new ArrayList<>();
		int count = 0;
		for (Block b : shown) {
			int mask = 0;
			for (Direction d : DIRS) {
				if (facing[d.ordinal()]
						&& !solid.contains(BlockPos.asLong(b.x() + d.getStepX(), b.y() + d.getStepY(), b.z() + d.getStepZ()))) {
					mask |= 1 << d.ordinal();
				}
			}
			if (mask == 0) {
				continue;
			}
			float x = b.x() + 0.5F - cx, y = b.y() + 0.5F - cy, z = b.z() + 0.5F - cz;
			float depth = y * sp + (-x * syw + z * cyw) * cp;
			keys.add(new float[]{depth, mask, drawn.size()});
			drawn.add(b);
			count += Integer.bitCount(mask);
		}
		shownBlocks = drawn.size();
		keys.sort((a, c) -> Float.compare(a[0], c[0])); // hinten zuerst, vorn zuletzt (verdeckt)
		drawCut = count > MAX_FACES;
		int skip = Math.max(0, count - MAX_FACES); // zu viele: die hintersten weglassen
		int total = Math.min(count, MAX_FACES);
		float[] m = new float[total * 12];
		int[] col = new int[total];
		boolean[] rev = new boolean[total];
		int f = 0;
		for (float[] k : keys) {
			int mask = (int) k[1];
			Block b = drawn.get((int) k[2]);
			Face[] tex = faces(b.state());
			for (Direction dir : DIRS) {
				if ((mask & (1 << dir.ordinal())) == 0) {
					continue;
				}
				if (skip > 0) {
					skip--;
					continue;
				}
				Face face = tex[dir.ordinal()];
				float[][] corners = CORNERS[dir.ordinal()];
				int o = f * 12;
				for (int i = 0; i < 4; i++) {
					float x = b.x() + corners[i][0] - cx, y = b.y() + corners[i][1] - cy, z = b.z() + corners[i][2] - cz;
					float x1 = x * cyw + z * syw;
					float z1 = -x * syw + z * cyw;
					m[o + i * 2] = x1;
					m[o + i * 2 + 1] = -(y * cp - z1 * sp);
				}
				if (face != null) {
					m[o + 8] = face.u0();
					m[o + 9] = face.v0();
					m[o + 10] = face.u1();
					m[o + 11] = face.v1();
				}
				// Umlaufsinn wie bei normalen GUI-Vierecken, sonst wirft die Grafikkarte die Fläche weg (Rückseite)
				float area = 0;
				for (int i = 0; i < 4; i++) {
					int j = (i + 1) % 4;
					area += m[o + i * 2] * m[o + j * 2 + 1] - m[o + j * 2] * m[o + i * 2 + 1];
				}
				rev[f] = area > 0;
				float s = shade(dir);
				int base = face == null ? 0xFF888888 : face.tint() == -1 ? 0xFFFFFFFF : face.tint();
				int r = (int) (((base >> 16) & 0xFF) * s), g = (int) (((base >> 8) & 0xFF) * s), bl = (int) ((base & 0xFF) * s);
				col[f] = 0xFF000000 | (r << 16) | (g << 8) | bl;
				f++;
			}
		}
		mesh = m;
		colors = col;
		reversed = rev;
		faces = f;
	}

	/** Alle Flächen auf einmal zeichnen (eigenes GUI-Element mit beliebigen Vierecken). */
	private record Mesh(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2f pose, float[] data, int[] colors,
			boolean[] reversed, int count, ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
		private static final int[] FORWARD = {0, 1, 2, 3}, BACKWARD = {0, 3, 2, 1};

		@Override
		public void buildVertices(VertexConsumer v) {
			for (int f = 0; f < count; f++) {
				int o = f * 12;
				float[] us = {data[o + 8], data[o + 10], data[o + 10], data[o + 8]};
				float[] vs = {data[o + 9], data[o + 9], data[o + 11], data[o + 11]};
				int c = colors[f];
				for (int i : reversed[f] ? BACKWARD : FORWARD) {
					v.addVertexWith2DPose(pose, data[o + i * 2], data[o + i * 2 + 1]).setUv(us[i], vs[i]).setColor(c);
				}
			}
		}
	}

	// ------------------------------------------------------------------ Zeichnen und Bedienung

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xF0100A0B);
		g.nextStratum(); // Modell über dem Hintergrund (sonst kann die Fläche es verdecken)
		if (blocks == null) {
			Component c = Component.translatable("loothub.build.counting");
			g.drawString(font, c, (width - font.width(c)) / 2, height / 2, 0xFFB9A9AB, false);
		} else if (faces > 0 && atlas != null) {
			try {
				var tex = Minecraft.getInstance().getTextureManager().getTexture(atlas);
				TextureSetup setup = TextureSetup.singleTexture(tex.getTextureView(), tex.getSampler());
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(width / 2F + panX, (height - 30) / 2F + panY);
				pose.scale(zoom, zoom);
				ScreenRectangle area = new ScreenRectangle(0, 0, width, height - 30);
				LitematicaBridge.submit(g, new Mesh(RenderPipelines.GUI_TEXTURED, setup, new Matrix3x2f(pose), mesh, colors,
						reversed, faces, area, area));
				pose.popMatrix();
			} catch (RuntimeException | LinkageError e) {
				Loothub.LOGGER.warn("3D-Ansicht kann nicht gezeichnet werden", e);
				faces = 0;
			}
		}
		super.render(g, mx, my, delta);
		g.drawString(font, title, 8, 8, 0xFFFFD66B, true);
		if (blocks != null && !blocks.isEmpty()) {
			String info = Component.translatable(layerOnly ? "loothub.build.preview.info_layer" : "loothub.build.preview.info",
					Fmt.whole(shownBlocks), layer - minY + 1, maxY - minY + 1).getString();
			g.drawString(font, info, 8, 20, 0xFFB9A9AB, true);
		}
		if (truncated || drawCut) {
			g.drawString(font, Component.translatable("loothub.build.preview.truncated"), 8, 32, 0xFFFF8A8A, true);
		}
		Component hint = Component.translatable("loothub.build.preview.hint");
		g.drawString(font, hint, width - 8 - font.width(hint), 8, 0xFF7A6A6C, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.y() < height - 30) {
			dragging = event.button() == 0;
			tilting = event.button() == 1;
			lastX = event.x();
			lastY = event.y();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			panX += (float) (event.x() - lastX);
			panY += (float) (event.y() - lastY);
		} else if (tilting) {
			// rechte Maustaste: kippen (von der Seite bis fast senkrecht von oben) und drehen
			pitch = Math.max(-10F, Math.min(89F, pitch + (float) (event.y() - lastY) * 0.5F));
			yaw = (yaw + (float) (event.x() - lastX) * 0.5F + 360F) % 360F;
			rebuild();
		} else {
			return super.mouseDragged(event, dx, dy);
		}
		lastX = event.x();
		lastY = event.y();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		tilting = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (Minecraft.getInstance().hasShiftDown()) {
			// Umschalt + Mausrad: zum Mauszeiger hin zoomen
			float old = zoom;
			zoom = Math.max(0.3F, Math.min(80F, zoom * (vertical > 0 ? 1.15F : 1 / 1.15F)));
			float ox = (float) mx - width / 2F - panX, oy = (float) my - (height - 30) / 2F - panY;
			panX -= ox * (zoom / old - 1);
			panY -= oy * (zoom / old - 1);
		} else {
			// Mausrad: rundherum drehen
			yaw = (yaw + (vertical > 0 ? 10F : -10F) + 360F) % 360F;
			rebuild();
		}
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
