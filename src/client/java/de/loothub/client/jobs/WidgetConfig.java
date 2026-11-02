package de.loothub.client.jobs;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Einstellungen von Loothub+ (Job-Widget, Tooltip-Preise, Statistik, Updates), gespeichert in {@code config/loothub.json}.
 * Die Position wird relativ gespeichert (0 = ganz links/oben, 1 = ganz rechts/unten),
 * damit das Widget auch nach einer Fenstergrößen-Änderung an der richtigen Stelle sitzt.
 */
public final class WidgetConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static WidgetConfig instance;

	public boolean jobWidgetEnabled = true;
	/** Marktpreise im Item-Tooltip anzeigen. */
	public boolean tooltipPrices = true;
	/** Eigene Statistik zählen. */
	public boolean statsEnabled = true;
	/** Beim Start nach Updates suchen. */
	public boolean updateCheck = true;
	public double jobWidgetX = 0.0;
	public double jobWidgetY = 0.35;

	public static WidgetConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Loothub.MOD_ID + ".json");
	}

	private static WidgetConfig load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				WidgetConfig c = GSON.fromJson(r, WidgetConfig.class);
				if (c != null) {
					c.jobWidgetX = clamp01(c.jobWidgetX);
					c.jobWidgetY = clamp01(c.jobWidgetY);
					return c;
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Einstellungen konnten nicht gelesen werden, nutze Standardwerte", e);
			}
		}
		return new WidgetConfig();
	}

	public void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			Loothub.LOGGER.warn("Einstellungen konnten nicht gespeichert werden", e);
		}
	}

	static double clamp01(double v) {
		return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
	}
}
