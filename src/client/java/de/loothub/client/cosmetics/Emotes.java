package de.loothub.client.cosmetics;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Kleine Gesten der Mini-Mes: winken, wenn der Spieler eine Weile stillsteht; jubeln beim Job-Level-Up;
 * sich strecken und gähnen, wenn sie in der AFK-Ansicht mitschlafen. Jedes Mini-Me zu seiner eigenen Zeit.
 */
public final class Emotes {
	public static final int NONE = 0, WAVE = 1, CHEER = 2, YAWN = 3, SCRATCH = 4, HOLD = 5, RAIN = 6, SHIVER = 7, FAN = 8,
			DANGLE = 9, NOD = 10, HIGHFIVE = 11, TIRED = 12, POKE = 13, DANCE = 14, BOW = 15, CLAP = 16, FACEPALM = 17,
			GAME = 18, HEART = 19;
	/** Gesten im Emote-Rad (Reihenfolge im Kreis). */
	public static final int[] WHEEL = {WAVE, CHEER, DANCE, BOW, CLAP, HEART, FACEPALM, YAWN};
	private static final long PLAY_MS = 4_000L;
	private static volatile int played;
	private static volatile long playedAt;

	/** Wo die Geste begonnen wurde – sie läuft in Schleife, bis sich der Spieler von hier bewegt. */
	private static volatile double[] playedPos;

	/** Geste aus dem Emote-Rad: der Spieler und alle eigenen Mini-Mes machen sie in Schleife, bis man sich bewegt. */
	public static void play(int type) {
		played = type;
		playedAt = System.currentTimeMillis();
		var pl = net.minecraft.client.Minecraft.getInstance().player;
		playedPos = pl == null ? null : new double[]{pl.getX(), pl.getY(), pl.getZ()};
		EmoteShare.send(type); // andere Loothub-Spieler sehen die Geste auch
	}

	/** Läuft gerade eine Geste aus dem Emote-Rad? {Art, Fortschritt 0..1} oder null – auch der Spieler selbst macht sie. */
	/** Geste aus dem Emote-Rad für diesen Spieler-Zustand: eigene oder die eines anderen Loothub-Spielers. */
	public static float[] wheel(Object playerState) {
		return Motion.local(playerState) ? wheel() : EmoteShare.remote(Cosmetics.owner(playerState));
	}

	public static float[] wheel() {
		long d = System.currentTimeMillis() - playedAt;
		return played != NONE && d >= 0 ? loop(played, d) : null;
	}

	/** Hat sich der Spieler seit {@code at} bewegt (laufen, springen, fallen)? Umschauen zählt nicht. */
	static boolean moved(double[] at, net.minecraft.world.entity.player.Player p) {
		double dx = p.getX() - at[0], dz = p.getZ() - at[2];
		return dx * dx + dz * dz > 0.15 * 0.15 || Math.abs(p.getY() - at[1]) > 0.3;
	}

	/** Eigene Geste beenden, sobald man sich bewegt. */
	static void stopIfMoved(net.minecraft.world.entity.player.Player p) {
		double[] at = playedPos;
		// auch beenden, wenn man sich AFK hinlegt
		if (played != NONE && (at != null && moved(at, p) || de.loothub.client.quick.AfkView.lieProgress() > 0.05F)) {
			played = NONE;
			playedPos = null;
		}
	}

	/** Spielt gerade Musik (Musik-Anzeige)? Dann tanzen die Mini-Mes ab und zu. */
	private static boolean music() {
		var s = de.loothub.client.music.YtMusic.song();
		return s != null && !s.paused();
	}
	/** Dauerhafte Gesten (kein Ein-/Ausblenden über den Verlauf). */
	public static boolean steady(int type) {
		return type == HOLD || type == RAIN || type == SHIVER || type == DANGLE || type == TIRED;
	}

	/** Wer gewinnt das Schere-Stein-Papier-Spiel dieser Runde (für beide gleich)? */
	public static boolean wins(int slot) {
		long round = Math.floorDiv(System.currentTimeMillis(), 60_000L) + slot / 2;
		return (round % 2 == 0) == (slot % 2 == 0);
	}
	private static final long CHEER_MS = 3200L;
	private static volatile long cheerAt;
	/** Geste je Mini-Me-Zustand: {Art, Fortschritt 0..1}. */
	private static final Map<Object, float[]> STATES = new WeakHashMap<>();

	private Emotes() {
	}

	/** Eigener Job-Level-Up: alle eigenen Mini-Mes jubeln. */
	public static void cheer() {
		cheerAt = System.currentTimeMillis();
	}

	/** Zurück vom AFK: nach dem Aufstehen freuen sich die Mini-Mes (jubeln, dann abklatschen). */
	public static void welcomeBack(long delayMs) {
		cheerAt = System.currentTimeMillis() + delayMs;
	}

	private static volatile long mentionAt, pmAt;

	/** Jemand erwähnt den eigenen Namen im Chat: die Mini-Mes schauen auf und winken. */
	public static void mention() {
		mentionAt = System.currentTimeMillis();
	}

	/** Privatnachricht bekommen: kurz ein Briefumschlag über einem Mini-Me. */
	public static void privateMessage() {
		pmAt = System.currentTimeMillis();
	}

	/** Briefumschlag sichtbar: Fortschritt 0..1, sonst −1. */
	public static float envelope() {
		long d = System.currentTimeMillis() - pmAt;
		return d >= 0 && d < 4_000L ? d / 4_000F : -1F;
	}

	/** Gerade erwähnt worden (für den Blick nach oben)? */
	public static boolean mentioned() {
		return System.currentTimeMillis() - mentionAt < 2_500L;
	}

	/** Tageszeit der Welt 0..24000 (−1 ohne Welt oder ohne Tag/Nacht, z. B. im Nether). */
	static long dayTime() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null || !mc.level.dimensionType().hasSkyLight() || mc.level.dimensionType().hasFixedTime()) {
			return -1;
		}
		return Math.floorMod(mc.level.getDayTime(), 24_000L);
	}

	/** Heiß: Nether oder heißes Biom (Wüste, Savanne, Tafelberge). */
	static boolean hot(Object playerState) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null || !(playerState instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState st)) {
			return false;
		}
		if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
			return true;
		}
		net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(st.x, st.y, st.z);
		return mc.level.getBiome(pos).value().getBaseTemperature() >= 1.5F;
	}

	/**
	 * Welche Geste Mini-Me {@code slot} des Spielers gerade macht: {Art, Fortschritt}. Reaktionen auf die Umgebung
	 * (festhalten beim Sprinten, Regen, Lava, Schnee) kommen immer; Gesten im Stand nur mit eingeschalteten Gesten.
	 */
	public static float[] pick(Object playerState, int slot, boolean sleeping, boolean sitting) {
		return pick(playerState, slot, sleeping, sitting, false, "");
	}

	/**
	 * Wie oben, dazu: {@code partner} = das andere Mini-Me desselben Paars ist da (Gespräch/Spielen/Abklatschen);
	 * {@code trait} = Charakter („playful“, „shy“, „brave“, „lazy“, „curious“ oder leer).
	 */
	public static float[] pick(Object playerState, int slot, boolean sleeping, boolean sitting, boolean partner, String trait) {
		long now = System.currentTimeMillis();
		float tick = (now % 100_000L) / 1000.0F;
		boolean gestures = Cosmetics.has(playerState, Cosmetics.EMOTES);
		boolean local = Motion.local(playerState);
		boolean lazy = "lazy".equals(trait), playful = "playful".equals(trait);
		// Emote-Rad: Vorrang vor allem anderen
		if (local && played != NONE && now >= playedAt) {
			// in Schleife; leicht versetzt, damit nicht alle exakt gleich zappeln
			return loop(played, Math.max(0L, now - playedAt - slot * 120L));
		}
		if (!local) {
			// Geste, die ein anderer Loothub-Spieler gerade im Emote-Rad gewählt hat
			float[] r = EmoteShare.remote(Cosmetics.owner(playerState));
			if (r != null) {
				float q = r[1] - slot * 0.03F;
				return new float[]{r[0], q < 0F ? q + 1F : q, r.length > 2 ? r[2] : 0F};
			}
		}
		if (gestures && local && now >= cheerAt && now - cheerAt < CHEER_MS) {
			return new float[]{CHEER, (now - cheerAt) / (float) CHEER_MS};
		}
		// nach dem Jubeln klatschen sich die beiden auf den Schultern ab
		if (gestures && local && slot < 2 && partner && now - cheerAt >= CHEER_MS && now - cheerAt < CHEER_MS + 1_400L) {
			return new float[]{HIGHFIVE, (now - cheerAt - CHEER_MS) / 1_400F};
		}
		// im Chat erwähnt: winken
		if (gestures && local && !sleeping && now - mentionAt < 2_500L) {
			return new float[]{WAVE, (now - mentionAt) / 2_500F};
		}
		if (sleeping) {
			float p = window(now, 17_000L, slot * 2_700L, 2_200L);
			return !gestures || p < 0 ? null : new float[]{YAWN, p};
		}
		int env = Motion.env(playerState);
		// auf der Schulter beim Sprinten: am Kopf festhalten
		if (slot < 2 && Motion.forward(playerState) > 0.18F) {
			return new float[]{HOLD, tick};
		}
		if ((env & Motion.ENV_RAIN) != 0 && (env & Motion.ENV_WATER) == 0) {
			return new float[]{RAIN, tick};
		}
		if ((env & Motion.ENV_LAVA) != 0) {
			float p = window(now, 5_000L, slot * 900L, 2_500L);
			if (p >= 0) {
				return new float[]{FAN, p};
			}
		}
		if ((env & Motion.ENV_SNOW) != 0) {
			return new float[]{SHIVER, tick};
		}
		if (!gestures) {
			return null;
		}
		// Hitze (Nether, Wüste): ab und zu Luft zufächeln
		if (hot(playerState)) {
			float p = window(now, 14_000L, slot * 1_900L, 2_400L);
			if (p >= 0) {
				return new float[]{FAN, p};
			}
		}
		// Musik läuft: immer wieder gemeinsam tanzen (verspielte öfter)
		if (local && music() && !sitting) {
			float p = window(now, playful ? 14_000L : lazy ? 40_000L : 22_000L, slot * 300L, 6_000L);
			if (p >= 0) {
				return new float[]{DANCE, p};
			}
		}
		// fremde Mini-Mes in der Nähe: zuwinken oder höflich verbeugen
		if (Life.foreignNear(playerState)) {
			long cycleNo = Math.floorDiv(now - slot * 700L, 9_000L);
			float p = window(now, 9_000L, slot * 700L, 2_000L);
			if (p >= 0) {
				return new float[]{cycleNo % 3 == 2 ? BOW : WAVE, p};
			}
		}
		// Paare spielen ab und zu Schere-Stein-Papier: dreimal schütteln, dann jubelt einer, der andere ärgert sich
		if (partner && slot < 4) {
			float p = window(now, playful ? 35_000L : 60_000L, (slot / 2) * 7_100L + 21_000L, 3_600L);
			if (p >= 0) {
				return new float[]{GAME, p};
			}
		}
		// Gespräch (Paare): beide gleichzeitig, abwechselnd nicken – Krabbler spielen stattdessen
		if (partner) {
			long cycle = playful ? 18_000L : lazy ? 45_000L : 28_000L;
			float p = window(now, cycle, (slot / 2) * 5_300L + 9_000L, 4_500L);
			if (p >= 0 && (slot < 4 || sitting)) {
				return new float[]{slot >= 4 ? POKE : NOD, p};
			}
		}
		// nachts müde (Kopf hängt), morgens strecken und gähnen
		long day = dayTime();
		if (day >= 0) {
			boolean night = day >= 13_000L && day < 23_000L;
			boolean morning = day >= 23_000L || day < 1_000L;
			if (morning) {
				float p = window(now, 20_000L, slot * 3_100L, 2_200L);
				if (p >= 0) {
					return new float[]{YAWN, p};
				}
			}
			// nachts gähnen sie ab und zu auch unterwegs
			if (night && !(lazy) && Motion.idle(playerState) <= 6.0F) {
				float p = window(now, 40_000L, slot * 4_700L + 6_000L, 2_200L);
				if (p >= 0) {
					return new float[]{YAWN, p};
				}
			}
			if ((night || lazy) && Motion.idle(playerState) > (lazy ? 3.0F : 6.0F)) {
				float p = window(now, lazy ? 12_000L : 18_000L, slot * 2_300L, 2_200L);
				if (p >= 0) {
					return new float[]{YAWN, p};
				}
				if (night) {
					return new float[]{TIRED, tick};
				}
			}
		}
		if (Motion.idle(playerState) > (playful ? 2.0F : lazy ? 8.0F : 4.0F)) {
			float p = window(now, playful ? 9_000L : 16_000L, slot * 2_100L, 2_400L);
			if (p >= 0) {
				return new float[]{WAVE, p};
			}
			p = window(now, 16_000L, slot * 2_100L + 6_000L, 2_000L);
			if (p >= 0) {
				return new float[]{SCRATCH, p};
			}
			if (sitting) {
				return new float[]{DANGLE, tick}; // Beine baumeln lassen
			}
		}
		return null;
	}

	/** Fortschritt 0..1 im Zeitfenster eines Zyklus, sonst −1. */
	private static float window(long now, long cycle, long offset, long length) {
		long pos = Math.floorMod(now - offset, cycle);
		return pos < length ? pos / (float) length : -1.0F;
	}

	public static void attach(Object miniState, float[] emote) {
		if (emote == null) {
			STATES.remove(miniState);
		} else {
			STATES.put(miniState, emote);
		}
	}

	public static float[] of(Object miniState) {
		return STATES.get(miniState);
	}

	/** Geste in Endlosschleife: {Art, Fortschritt im Durchgang, 1 = schon voll eingeblendet}. */
	static float[] loop(int type, long elapsed) {
		return new float[]{type, (elapsed % PLAY_MS) / (float) PLAY_MS, elapsed > PLAY_MS / 2 ? 1F : 0F};
	}

	/** Stärke einer Geste: einmal weich einblenden, in der Schleife dann voll bleiben (kein Absacken je Durchgang). */
	public static float strength(float[] e) {
		return e.length > 2 && e[2] > 0F ? 1F : envelope(e[1]);
	}

	/** Weiches Ein- und Ausblenden über den Verlauf (0 → 1 → 0). */
	public static float envelope(float p) {
		return (float) Math.sin(Math.PI * Math.min(1.0F, Math.max(0.0F, p)));
	}
}
