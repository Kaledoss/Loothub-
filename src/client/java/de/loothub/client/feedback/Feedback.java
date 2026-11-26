package de.loothub.client.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import com.mojang.blaze3d.platform.NativeImage;

import de.loothub.client.jobs.WidgetConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Feedback/Fehlermeldung an den Loothub-Server: Text des Spielers plus Mod-Version, Spielername, Loothub-Einstellungen,
 * die letzten Loothub-Zeilen aus dem Log und auf Wunsch ein Bildschirmfoto (verkleinert). Nur Admins sehen die Liste.
 */
public final class Feedback {
	/** Bildschirmfoto als PNG (verkleinert), oder null. */
	static byte[] shot;
	private static int captureIn = -1;
	private static Runnable afterCapture;

	private Feedback() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (captureIn < 0) {
				return;
			}
			if (captureIn-- > 0) {
				return; // ein paar Bilder warten, bis das Menü weg ist
			}
			Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
				try (img) {
					shot = shrink(img, 960);
				} catch (Exception e) {
					shot = null;
				}
				Runnable r = afterCapture;
				afterCapture = null;
				if (r != null) {
					mc.execute(r);
				}
			});
		});
	}

	/** Menü kurz schließen, Spielbild aufnehmen, danach {@code reopen} ausführen. */
	static void capture(Runnable reopen) {
		afterCapture = reopen;
		captureIn = 3;
		Minecraft.getInstance().setScreen(null);
	}

	private static byte[] shrink(NativeImage img, int maxW) throws IOException {
		int w = img.getWidth(), h = img.getHeight();
		if (w <= maxW) {
			return png(img);
		}
		int nw = maxW, nh = Math.max(1, h * maxW / w);
		try (NativeImage small = new NativeImage(nw, nh, false)) {
			for (int y = 0; y < nh; y++) {
				for (int x = 0; x < nw; x++) {
					small.setPixel(x, y, img.getPixel(x * w / nw, y * h / nh));
				}
			}
			byte[] png = png(small);
			if (png.length > 560_000 && maxW > 480) {
				return shrink(img, maxW * 3 / 4); // noch zu groß: kleiner machen
			}
			return png;
		}
	}

	/** Bild als PNG-Bytes (über eine kurze Temp-Datei). */
	private static byte[] png(NativeImage img) throws IOException {
		Path tmp = Files.createTempFile("loothub-feedback", ".png");
		try {
			img.writeToFile(tmp);
			return Files.readAllBytes(tmp);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** Meldung abschicken; done bekommt null (ok) oder einen Fehlerschlüssel. */
	static void send(String text, boolean withShot, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "feedback"); // muss zuerst stehen (Server erlaubt dann größere Meldungen)
		p.addProperty("text", text);
		p.addProperty("ver", de.loothub.client.Updater.currentVersion());
		p.addProperty("set", settings());
		p.addProperty("log", log());
		if (withShot && shot != null) {
			p.addProperty("shot", Base64.getEncoder().encodeToString(shot));
		}
		de.loothub.client.progress.Progress.admin(p, res -> {
			if (res == null) {
				done.accept("offline");
			} else if (res.has("error")) {
				done.accept(res.get("error").getAsString());
			} else {
				shot = null;
				done.accept(null);
			}
		});
	}

	/** Loothub-Einstellungen (ohne Zugangsdaten – die liegen in eigenen Dateien). */
	private static String settings() {
		try {
			Path p = FabricLoader.getInstance().getConfigDir().resolve("loothub.json");
			String s = Files.readString(p, StandardCharsets.UTF_8);
			return s.length() > 60000 ? s.substring(0, 60000) : s;
		} catch (Exception e) {
			return "";
		}
	}

	/** Die letzten Loothub-Zeilen aus logs/latest.log (Meldungen und Fehler der Mod, keine Chats). */
	private static String log() {
		try {
			Path p = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
			if (!Files.exists(p)) {
				return "";
			}
			List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
			List<String> out = new ArrayList<>();
			boolean trace = false;
			for (String line : all) {
				String l = line.toLowerCase(Locale.ROOT);
				boolean chat = l.contains("[chat]") || l.contains("[system] [chat]");
				if (chat) {
					trace = false;
					continue;
				}
				boolean mine = l.contains("loothub");
				boolean stack = line.startsWith("\tat ") || line.startsWith("Caused by") || line.startsWith("java.");
				if (mine || (trace && stack)) {
					out.add(line);
					trace = mine && (l.contains("error") || l.contains("exception") || l.contains("warn")) || (trace && stack);
				} else {
					trace = false;
				}
			}
			int from = Math.max(0, out.size() - 200);
			String s = String.join("\n", out.subList(from, out.size()));
			return s.length() > 40000 ? s.substring(s.length() - 40000) : s;
		} catch (Exception e) {
			return "";
		}
	}
}
