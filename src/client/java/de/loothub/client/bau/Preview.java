package de.loothub.client.bau;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import de.loothub.Loothub;
import de.loothub.client.Fmt;

/**
 * 3D-Ansicht einer Schematic: alle Blöcke als kleine Würfel (wie im Inventar) in Schrägansicht. Ziehen = verschieben,
 * Mausrad = Zoom, Knöpfe = in 90°-Schritten drehen und Ebenen ein-/ausblenden (zum Nachbauen Schicht für Schicht).
 */
public final class Preview extends Screen {
	/** Ein Block der Schematic (relativ zur Schematic) und das Item, das ihn zeigt. */
	record Block(int x, int y, int z, ItemStack item, boolean opaque) {
	}

	/** Höchstens so viele Blöcke laden bzw. zeichnen (sonst wird es zu langsam). */
	private static final int MAX_LOAD = 2_000_000;
	private static final int MAX_DRAW = 60_000;
	/** Versatz je Block in der Schrägansicht (Pixel bei Zoom 1) – passend zu den Würfeln der Inventar-Items. */
	private static final float AX = 7.07F, AY = 3.54F, UP = 8.66F;

	private final Screen parent;
	private final Plan plan;
	private volatile List<Block> blocks;
	private List<Block> shown = List.of();
	private int rotation;
	private int minY, maxY, layer;
	private boolean layerOnly;
	private float zoom = 1.0F, panX, panY;
	private boolean dragging;
	private double lastX, lastY;
	private boolean truncated, drawCut;

	public Preview(Screen parent, Plan plan) {
		super(Component.translatable("loothub.build.preview.title", plan.name));
		this.parent = parent;
		this.plan = plan;
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
		x = button(x, y, "⟲", b -> rotate(1));
		x = button(x, y, "⟳", b -> rotate(-1));
		x += 8;
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
			zoom = 1.0F;
			panX = 0;
			panY = 0;
		});
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width - 78, y, 70, 16).build());
	}

	private int button(int x, int y, String label, Button.OnPress press) {
		int w = font.width(label) + 14;
		addRenderableWidget(Button.builder(Component.literal(label), press).bounds(x, y, w, 16).build());
		return x + w + 4;
	}

	private void rotate(int dir) {
		rotation = (rotation + dir + 4) % 4;
		rebuild();
	}

	private void layer(int d) {
		layer = Math.max(minY, Math.min(maxY, layer + d));
		rebuild();
	}

	/** Gedrehte Lage eines Blocks: {x, z} um die senkrechte Achse in 90°-Schritten. */
	private int[] turned(Block b) {
		return switch (rotation) {
			case 1 -> new int[]{-b.z(), b.x()};
			case 2 -> new int[]{-b.x(), -b.z()};
			case 3 -> new int[]{b.z(), -b.x()};
			default -> new int[]{b.x(), b.z()};
		};
	}

	/** Sichtbare Blöcke neu zusammenstellen und von hinten nach vorn sortieren (vordere verdecken hintere). */
	private void rebuild() {
		List<Block> all = blocks;
		if (all == null) {
			return;
		}
		List<Block> candidates = new ArrayList<>();
		java.util.Set<Long> solid = new java.util.HashSet<>();
		for (Block b : all) {
			if (b.y() > layer || (layerOnly && b.y() != layer)) {
				continue;
			}
			candidates.add(b);
			if (b.opaque()) {
				int[] t = turned(b);
				solid.add(net.minecraft.core.BlockPos.asLong(t[0], b.y(), t[1]));
			}
		}
		// Blöcke weglassen, deren drei sichtbare Seiten (vorn rechts, vorn links, oben) von festen Blöcken verdeckt sind
		List<Block> list = new ArrayList<>();
		for (Block b : candidates) {
			int[] t = turned(b);
			if (solid.contains(net.minecraft.core.BlockPos.asLong(t[0] + 1, b.y(), t[1]))
					&& solid.contains(net.minecraft.core.BlockPos.asLong(t[0], b.y(), t[1] - 1))
					&& solid.contains(net.minecraft.core.BlockPos.asLong(t[0], b.y() + 1, t[1]))) {
				continue;
			}
			list.add(b);
		}
		// Blickrichtung von +x, -z und oben: weiter weg = kleineres x, größeres z, kleineres y
		list.sort(Comparator.comparingInt((Block b) -> {
			int[] t = turned(b);
			return t[0] - t[1] + b.y();
		}));
		drawCut = list.size() > MAX_DRAW;
		// zu viele: die vordersten behalten (die sieht man)
		shown = list.size() > MAX_DRAW ? list.subList(list.size() - MAX_DRAW, list.size()) : list;
	}

	@Override
	public void tick() {
		if (blocks != null && shown.isEmpty() && !blocks.isEmpty() && maxY == 0 && minY == 0) {
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
			for (Block b : blocks) {
				lo = Math.min(lo, b.y());
				hi = Math.max(hi, b.y());
			}
			minY = lo;
			maxY = hi;
			layer = hi;
			rebuild();
		}
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xF0100A0B);
		List<Block> list = shown;
		if (blocks == null) {
			Component c = Component.translatable("loothub.build.counting");
			g.drawString(font, c, (width - font.width(c)) / 2, height / 2, 0xFFB9A9AB, false);
		} else if (!list.isEmpty()) {
			// Mitte der Schematic in die Bildmitte
			float cx = 0, cy = 0;
			{
				int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
				int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
				for (Block b : list) {
					int[] t = turned(b);
					lo[0] = Math.min(lo[0], t[0]);
					hi[0] = Math.max(hi[0], t[0]);
					lo[1] = Math.min(lo[1], b.y());
					hi[1] = Math.max(hi[1], b.y());
					lo[2] = Math.min(lo[2], t[1]);
					hi[2] = Math.max(hi[2], t[1]);
				}
				float mxw = (lo[0] + hi[0]) / 2F, myw = (lo[1] + hi[1]) / 2F, mzw = (lo[2] + hi[2]) / 2F;
				cx = -(mxw + mzw) * -AX;
				cy = -(mxw * AY - mzw * AY - myw * UP);
				// so weit herauszoomen, dass alles passt (nur beim ersten Mal sinnvoll; Zoom bleibt änderbar)
			}
			g.enableScissor(0, 0, width, height - 30);
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(width / 2F + panX, (height - 30) / 2F + panY);
			pose.scale(zoom, zoom);
			pose.translate(cx, cy);
			for (Block b : list) {
				int[] t = turned(b);
				float sx = -AX * t[0] - AX * t[1];
				float sy = AY * t[0] - AY * t[1] - UP * b.y();
				pose.pushMatrix();
				pose.translate(sx - 8, sy - 8);
				g.renderItem(b.item(), 0, 0);
				pose.popMatrix();
			}
			pose.popMatrix();
			g.disableScissor();
		}
		super.render(g, mx, my, delta);
		g.drawString(font, title, 8, 8, 0xFFFFD66B, true);
		String info = blocks == null ? "" : Component.translatable(layerOnly ? "loothub.build.preview.info_layer"
				: "loothub.build.preview.info", Fmt.whole(list.size()), layer - minY + 1, maxY - minY + 1).getString();
		g.drawString(font, info, 8, 20, 0xFFB9A9AB, true);
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
			dragging = true;
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
			lastX = event.x();
			lastY = event.y();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		float old = zoom;
		zoom = Math.max(0.1F, Math.min(6.0F, zoom * (vertical > 0 ? 1.15F : 1 / 1.15F)));
		// zum Mauszeiger hin zoomen
		float ox = (float) mx - width / 2F - panX, oy = (float) my - (height - 30) / 2F - panY;
		panX -= ox * (zoom / old - 1);
		panY -= oy * (zoom / old - 1);
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
