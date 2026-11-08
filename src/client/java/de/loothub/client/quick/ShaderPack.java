package de.loothub.client.quick;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Das eigene Shaderpack „LoothubShader“ für Iris: wird beim Start in den Ordner shaderpacks gelegt (nur mit Iris,
 * nur wenn es fehlt oder veraltet ist) und lässt sich aus den Loothub-Einstellungen ein- und ausschalten.
 * Iris wird nur per Reflexion angesprochen – ohne Iris passiert einfach nichts.
 */
public final class ShaderPack {
	public static final String NAME = "LoothubShader";
	private static Boolean cachedActive;
	private static long cachedAt;

	private ShaderPack() {
	}

	public static boolean irisLoaded() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	private static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks").resolve(NAME);
	}

	private static String version() {
		return FabricLoader.getInstance().getModContainer(Loothub.MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/** Beim Start: Pack aus der Mod-Datei in shaderpacks/LoothubShader schreiben, falls nötig. */
	public static void install() {
		if (!irisLoaded()) {
			return;
		}
		try {
			Path dir = dir();
			Path stamp = dir.resolve("loothub.version");
			String ver = version();
			if (Files.exists(stamp) && Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(ver)) {
				return;
			}
			String list;
			try (InputStream in = ShaderPack.class.getResourceAsStream("/loothub_shader/files.txt")) {
				if (in == null) {
					return;
				}
				list = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			for (String name : list.split("\\R")) {
				name = name.strip();
				if (name.isEmpty()) {
					continue;
				}
				try (InputStream in = ShaderPack.class.getResourceAsStream("/loothub_shader/" + name)) {
					if (in == null) {
						continue;
					}
					Path out = dir.resolve(name);
					Files.createDirectories(out.getParent());
					Files.write(out, in.readAllBytes());
				}
			}
			Files.writeString(stamp, ver, StandardCharsets.UTF_8);
			Loothub.LOGGER.info("Loothub: Shaderpack {} bereitgelegt ({})", NAME, ver);
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Loothub: Shaderpack konnte nicht bereitgelegt werden", e);
		}
	}

	private static Object irisConfig() throws ReflectiveOperationException {
		return Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
	}

	/** Ist unser Pack gerade ausgewählt und sind Shader an? (kurz zwischengespeichert) */
	public static boolean active() {
		if (!irisLoaded()) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (cachedActive != null && now - cachedAt < 1000) {
			return cachedActive;
		}
		boolean on = false;
		try {
			Object cfg = irisConfig();
			Object name = cfg.getClass().getMethod("getShaderPackName").invoke(cfg);
			boolean enabled = (boolean) cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
			on = enabled && name instanceof Optional<?> o && o.isPresent() && NAME.equals(o.get());
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// andere Iris-Version: Status unbekannt
		}
		cachedActive = on;
		cachedAt = now;
		return on;
	}

	/** Unser Pack auswählen und Shader einschalten bzw. Shader ausschalten; Iris lädt neu. */
	public static void set(boolean on) {
		if (!irisLoaded()) {
			return;
		}
		install();
		try {
			Object cfg = irisConfig();
			if (on) {
				cfg.getClass().getMethod("setShaderPackName", String.class).invoke(cfg, NAME);
			}
			cfg.getClass().getMethod("setShadersEnabled", boolean.class).invoke(cfg, on);
			cfg.getClass().getMethod("save").invoke(cfg);
			Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Rückfall: nur an/aus über die öffentliche Iris-Schnittstelle
			try {
				Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
				Object apiCfg = api.getClass().getMethod("getConfig").invoke(api);
				apiCfg.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(apiCfg, on);
			} catch (ReflectiveOperationException | RuntimeException e2) {
				Loothub.LOGGER.warn("Loothub: Iris ließ sich nicht umschalten", e2);
			}
		}
		cachedActive = null;
	}
}
