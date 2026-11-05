package de.loothub.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Farben frei wählbar: „Innen“ (Akzente in Menüs und im Inventar-Paket), „Außen“ (Rahmen) und „Pulsieren“
 * (Farbe des pulsierenden Rands). Für eine andere Innen-Farbe als Rot erzeugt die Mod aus dem eingebauten
 * Paket eine eingefärbte Kopie unter {@code resourcepacks/Loothub+ Farbe} und aktiviert sie.
 */
public final class Theme {
	public static final int DEFAULT_INNER = 0xE23B3B;
	public static final int DEFAULT_OUTER = 0x8E1F26;
	public static final int DEFAULT_PULSE = 0xFF3040;
	/** Vorschläge im Farbwähler. */
	public static final int[] PRESETS = {0xE23B3B, 0x3B7BE2, 0x3BC25A, 0xA94BE2, 0xE2A93B, 0x3BC8D2, 0xE23BA0, 0xE0E0E0};

	private static final String PACK_DIR = "Loothub+ Farbe";

	private Theme() {
	}

	public static int inner() {
		return WidgetConfig.get().colorInner & 0xFFFFFF;
	}

	public static int outer() {
		return WidgetConfig.get().colorOuter & 0xFFFFFF;
	}

	public static int pulse() {
		return WidgetConfig.get().colorPulse & 0xFFFFFF;
	}

	/** Menüfarben setzen (beim Start und nach jedem Wechsel). */
	public static void applyColors() {
		LoothubScreen.RED = 0xFF000000 | inner();
		LoothubScreen.RED_DARK = 0xFF000000 | outer();
	}

	/** Dunkler Endpunkt des Pulsierens: Pulsfarbe stark abgedunkelt. */
	public static int pulseDark() {
		int c = pulse();
		return (((c >> 16) & 0xFF) * 35 / 100 << 16) | (((c >> 8) & 0xFF) * 35 / 100 << 8) | ((c & 0xFF) * 35 / 100);
	}

	public static int pulseBright() {
		return pulse();
	}

	/** Hintergrundfarbe mit eingestellter Deckkraft (Alpha der Vorlage × Regler). */
	public static int bg(int argb) {
		double f = Math.max(0.1, Math.min(1.0, WidgetConfig.get().backgroundOpacity));
		int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	// ------------------------------------------------------------------ Ressourcenpaket

	/** Paket passend zur Innen-Farbe erzeugen (im Hintergrund) und danach aktivieren. */
	public static void applyPack() {
		int color = inner();
		Thread t = new Thread(() -> {
			boolean custom = color != DEFAULT_INNER;
			if (custom) {
				try {
					generate(color);
				} catch (IOException | RuntimeException e) {
					Loothub.LOGGER.warn("Farb-Paket konnte nicht erzeugt werden", e);
					return;
				}
			}
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> select(custom));
		}, "Loothub-Theme");
		t.setDaemon(true);
		t.start();
	}

	/** Inventar-Paket an- oder abschalten (Schalter „Inventar-Design“). */
	public static void setPackActive(boolean on) {
		WidgetConfig.get().themePackWanted = on;
		if (on) {
			applyPack();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		PackRepository repo = mc.getResourcePackRepository();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		if (selected.removeIf(id -> (id.contains("loothub") && id.contains("dark_inventory")) || id.contains(PACK_DIR))) {
			repo.setSelected(selected);
			mc.options.updateResourcePacks(repo);
		}
	}

	private static void select(boolean custom) {
		Minecraft mc = Minecraft.getInstance();
		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		boolean wasOn = selected.removeIf(id -> (id.contains("loothub") && id.contains("dark_inventory")) || id.contains(PACK_DIR));
		if (!wasOn && !WidgetConfig.get().themePackWanted) {
			return; // Paket war vom Spieler abgeschaltet
		}
		for (String id : repo.getAvailableIds()) {
			boolean match = custom ? id.endsWith(PACK_DIR) : id.contains("loothub") && id.endsWith("dark_inventory");
			if (match) {
				selected.add(id);
				break;
			}
		}
		repo.setSelected(selected);
		mc.options.updateResourcePacks(repo);
	}

	private static void generate(int color) throws IOException {
		ModContainer mod = FabricLoader.getInstance().getModContainer(Loothub.MOD_ID).orElseThrow();
		Path src = mod.findPath("resourcepacks/dark_inventory").orElseThrow();
		Path dst = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK_DIR);
		Files.createDirectories(dst);
		float[] target = rgbToHsl(color);
		try (Stream<Path> files = Files.walk(src)) {
			for (Path p : (Iterable<Path>) files::iterator) {
				if (Files.isDirectory(p)) {
					continue;
				}
				String rel = src.relativize(p).toString().replace('\\', '/');
				Path out = dst.resolve(rel);
				Files.createDirectories(out.getParent());
				if (rel.endsWith(".png")) {
					BufferedImage img;
					try (InputStream in = Files.newInputStream(p)) {
						img = ImageIO.read(in);
					}
					recolor(img, target);
					try (OutputStream os = Files.newOutputStream(out)) {
						ImageIO.write(img, "png", os);
					}
				} else if (rel.equals("pack.mcmeta")) {
					String txt = Files.readString(p, StandardCharsets.UTF_8)
							.replace("Inventar in Schwarz-Rot", "Inventar in eigener Farbe");
					Files.writeString(out, txt, StandardCharsets.UTF_8);
				} else {
					Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Rötliche Pixel in den Farbton/die Sättigung der Zielfarbe drehen, Helligkeit bleibt. */
	private static void recolor(BufferedImage img, float[] target) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int argb = img.getRGB(x, y);
				int a = argb >>> 24;
				if (a == 0) {
					continue;
				}
				float[] hsl = rgbToHsl(argb & 0xFFFFFF);
				if (hsl[1] > 0.15f && (hsl[0] < 0.08f || hsl[0] > 0.92f)) {
					float s = Math.min(1f, hsl[1] * (target[1] / 0.75f));
					int rgb = hslToRgb(target[0], s, hsl[2]);
					img.setRGB(x, y, (a << 24) | rgb);
				}
			}
		}
	}

	public static float[] rgbToHsl(int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float l = (max + min) / 2, h = 0, s = 0;
		if (max != min) {
			float d = max - min;
			s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
			if (max == r) {
				h = (g - b) / d + (g < b ? 6 : 0);
			} else if (max == g) {
				h = (b - r) / d + 2;
			} else {
				h = (r - g) / d + 4;
			}
			h /= 6;
		}
		return new float[]{h, s, l};
	}

	public static int hslToRgb(float h, float s, float l) {
		float r, g, b;
		if (s == 0) {
			r = g = b = l;
		} else {
			float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
			float p = 2 * l - q;
			r = hue(p, q, h + 1f / 3);
			g = hue(p, q, h);
			b = hue(p, q, h - 1f / 3);
		}
		return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}

	private static float hue(float p, float q, float t) {
		if (t < 0) {
			t += 1;
		}
		if (t > 1) {
			t -= 1;
		}
		if (t < 1f / 6) {
			return p + (q - p) * 6 * t;
		}
		if (t < 0.5f) {
			return q;
		}
		if (t < 2f / 3) {
			return p + (q - p) * (2f / 3 - t) * 6;
		}
		return p;
	}

	/** HSV → RGB (für den Farbwähler). */
	public static int hsvToRgb(float h, float s, float v) {
		int i = (int) Math.floor(h * 6) % 6;
		float f = h * 6 - (float) Math.floor(h * 6);
		float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
		float r, g, b;
		switch (i) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}

	/** RGB → HSV (für den Farbwähler). */
	public static float[] rgbToHsv(int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
		float h = 0;
		if (d != 0) {
			if (max == r) {
				h = ((g - b) / d) % 6;
			} else if (max == g) {
				h = (b - r) / d + 2;
			} else {
				h = (r - g) / d + 4;
			}
			h /= 6;
			if (h < 0) {
				h += 1;
			}
		}
		return new float[]{h, max == 0 ? 0 : d / max, max};
	}
}
