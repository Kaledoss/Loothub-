package de.loothub.client.streamer;

import net.minecraft.client.Minecraft;

import de.loothub.client.jobs.WidgetConfig;

/** Schaltet Twitch-Chat und Kanalbelohnungen passend zu Streamer-Modus, Anmeldung und Einstellungen ein und aus. */
public final class Streamer {
	private Streamer() {
	}

	public static boolean enabled() {
		return WidgetConfig.get().streamerMode;
	}

	/** Verbindungen an den aktuellen Stand anpassen (Spiel-Thread). */
	public static void apply() {
		WidgetConfig c = WidgetConfig.get();
		boolean on = c.streamerMode && TwitchAuth.connected();
		Redemptions.ensure(on);
		StreamerChat.ensure(on, c.streamerChat, TwitchAuth.login()); // liest immer mit: Stream-Serien, Einblendungen
	}

	/** Wie {@link #apply()}, aber von einem beliebigen Thread aus. */
	public static void applyLater() {
		Minecraft.getInstance().execute(Streamer::apply);
	}

	/** Nach einer neuen Anmeldung: Verbindungen mit dem neuen Schlüssel neu aufbauen. */
	public static void restartLater() {
		Minecraft.getInstance().execute(() -> {
			stopAll();
			apply();
		});
	}

	public static void stopAll() {
		Redemptions.ensure(false);
		StreamerChat.stop();
	}
}
