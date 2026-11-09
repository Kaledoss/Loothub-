package de.loothub.client.streamer;

import java.io.IOException;
import java.util.Locale;

import de.loothub.Loothub;

/** Link im Standard-Browser öffnen (Windows, macOS, Linux). Nur auf Knopfdruck des Spielers. */
public final class Browser {
	private Browser() {
	}

	public static void open(String url) {
		if (url == null || !(url.startsWith("https://www.twitch.tv/") || url.startsWith("https://twitch.tv/"))) {
			return; // nur Twitch-Seiten öffnen
		}
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] cmd = os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
				: os.contains("mac") ? new String[]{"open", url}
				: new String[]{"xdg-open", url};
		try {
			new ProcessBuilder(cmd).start();
		} catch (IOException e) {
			Loothub.LOGGER.warn("Browser konnte nicht geöffnet werden", e);
		}
	}
}
