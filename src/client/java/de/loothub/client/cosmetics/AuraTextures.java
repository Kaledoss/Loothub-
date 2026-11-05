package de.loothub.client.cosmetics;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import de.loothub.Loothub;

/**
 * Aura in beliebiger Farbe: aus dem weißen Grundmuster wird je Farbe einmal eine eingefärbte Textur erzeugt
 * (helle Stellen bleiben fast weiß, damit die Flammen einen leuchtenden Kern haben).
 */
public final class AuraTextures {
	private static final Identifier BASE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/aura_base.png");
	private static final Map<Integer, Identifier> CACHE = new HashMap<>();
	private static final Map<String, Integer> NAMED = Map.of("gold", 0xFFCD3C, "blue", 0x46AAFF, "red", 0xFF463C,
			"green", 0x5AEB6E, "purple", 0xBE5AFF, "white", 0xEBF0FF);
	private static NativeImage base;
	private static boolean failed;

	private AuraTextures() {
	}

	/** „#FFAA00“, „ffaa00“ oder ein Name (gold, blue …) → RGB; ungültig → Gold. */
	public static int parse(String color) {
		if (color == null || color.isBlank()) {
			return NAMED.get("gold");
		}
		String c = color.strip().toLowerCase(Locale.ROOT);
		if (NAMED.containsKey(c)) {
			return NAMED.get(c);
		}
		try {
			return Integer.parseInt(c.replace("#", ""), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return NAMED.get("gold");
		}
	}

	/** Textur für diese Farbe (wird beim ersten Mal erzeugt); null, wenn das Grundmuster fehlt. */
	public static Identifier get(int rgb) {
		Identifier cached = CACHE.get(rgb);
		if (cached != null || failed) {
			return cached;
		}
		try {
			if (base == null) {
				Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(BASE);
				if (res.isEmpty()) {
					failed = true;
					return null;
				}
				try (InputStream in = res.get().open()) {
					base = NativeImage.read(in);
				}
			}
			int w = base.getWidth();
			int h = base.getHeight();
			NativeImage img = new NativeImage(w, h, true);
			int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = base.getPixel(x, y); // ARGB
					int a = (p >>> 24) & 0xFF;
					float lum = ((p >> 16) & 0xFF) / 255f;
					float core = (float) Math.pow(lum, 4) * 0.45f; // heller Kern
					int nr = Math.min(255, (int) (r * lum + (255 - r * lum) * core));
					int ng = Math.min(255, (int) (g * lum + (255 - g * lum) * core));
					int nb = Math.min(255, (int) (b * lum + (255 - b * lum) * core));
					img.setPixel(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
				}
			}
			Identifier id = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "dynamic/aura_" + String.format("%06x", rgb));
			DynamicTexture tex = new DynamicTexture(() -> "Loothub Aura", img);
			tex.upload();
			Minecraft.getInstance().getTextureManager().register(id, tex);
			CACHE.put(rgb, id);
			return id;
		} catch (Exception e) {
			Loothub.LOGGER.warn("Aura-Textur konnte nicht erzeugt werden", e);
			failed = true;
			return null;
		}
	}
}
