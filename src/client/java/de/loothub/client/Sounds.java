package de.loothub.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Alle Loothub-Töne an einer Stelle: Lautstärke je Bereich (Einstellungen → Töne) und kleine Klangfolgen
 * (mehrere Töne nacheinander, z. B. Gong, Wecker, Einblendungen). Nur Töne im eigenen Spiel.
 */
public final class Sounds {
	/** Bereiche mit eigener Lautstärke. */
	public enum Group {
		TIMER("timer"),
		STREAM("stream"),
		FISH("fish"),
		JOBS("jobs"),
		PCG("pcg"),
		PROTECT("protect");

		public final String id;

		Group(String id) {
			this.id = id;
		}

		public String key() {
			return "loothub.sound.group." + id;
		}
	}

	/** Ein Ton in einer Folge: nach {@code delay} Ticks (1/20 s). */
	public record Note(int delay, SoundEvent sound, float pitch, float volume) {
	}

	private record Pending(long at, Group group, SoundEvent sound, float pitch, float volume) {
	}

	private static final List<Pending> QUEUE = new ArrayList<>();
	private static long tick;

	private Sounds() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			tick++;
			synchronized (QUEUE) {
				Iterator<Pending> it = QUEUE.iterator();
				while (it.hasNext()) {
					Pending p = it.next();
					if (p.at() <= tick) {
						it.remove();
						playNow(p.group(), p.sound(), p.pitch(), p.volume());
					}
				}
			}
		});
	}

	/** Lautstärke des Bereichs 0..1. */
	public static float volume(Group g) {
		WidgetConfig c = WidgetConfig.get();
		if (c.soundVolumes == null) {
			return 1.0F;
		}
		Integer v = c.soundVolumes.get(g.id);
		return v == null ? 1.0F : Math.max(0, Math.min(100, v)) / 100.0F;
	}

	public static void setVolume(Group g, int percent) {
		WidgetConfig c = WidgetConfig.get();
		if (c.soundVolumes == null) {
			c.soundVolumes = new java.util.HashMap<>();
		}
		c.soundVolumes.put(g.id, Math.max(0, Math.min(100, percent)));
	}

	public static Note n(int delay, SoundEvent s, float pitch, float volume) {
		return new Note(delay, s, pitch, volume);
	}

	public static Note n(int delay, Holder<SoundEvent> s, float pitch, float volume) {
		return new Note(delay, s.value(), pitch, volume);
	}

	public static void play(Group g, SoundEvent s, float pitch, float volume) {
		play(g, List.of(new Note(0, s, pitch, volume)));
	}

	public static void play(Group g, Holder<SoundEvent> s, float pitch, float volume) {
		play(g, s.value(), pitch, volume);
	}

	/** Klangfolge abspielen (Spiel-Thread oder beliebig – wird eingereiht). */
	public static void play(Group g, List<Note> notes) {
		if (volume(g) <= 0.001F) {
			return;
		}
		synchronized (QUEUE) {
			for (Note n : notes) {
				QUEUE.add(new Pending(tick + Math.max(0, n.delay()), g, n.sound(), n.pitch(), n.volume()));
			}
		}
	}

	private static void playNow(Group g, SoundEvent s, float pitch, float volume) {
		Minecraft mc = Minecraft.getInstance();
		float v = volume * volume(g);
		if (v <= 0.001F || mc.getSoundManager() == null) {
			return;
		}
		mc.getSoundManager().play(SimpleSoundInstance.forUI(s, pitch, Math.min(1.0F, v)));
	}

	// ------------------------------------------------------------------ fertige Klänge

	/** Timer-Töne zur Auswahl: Glocke (bisher), Gong, alter Wecker. */
	public static final String[] TIMER_SOUNDS = {"bell", "gong", "alarm"};

	public static void timer(String which) {
		switch (which == null ? "bell" : which) {
			case "gong" -> play(Group.TIMER, List.of(
					n(0, SoundEvents.BELL_BLOCK, 0.55F, 1.0F),
					n(0, SoundEvents.NOTE_BLOCK_BASS, 0.5F, 0.8F),
					n(2, SoundEvents.BELL_RESONATE, 0.6F, 0.7F),
					n(30, SoundEvents.BELL_BLOCK, 0.55F, 0.7F)));
			case "alarm" -> {
				// Alter Wecker: vier schnelle, schrille Pieptöne, kurze Pause – dreimal
				List<Note> l = new ArrayList<>();
				for (int burst = 0; burst < 3; burst++) {
					for (int beep = 0; beep < 4; beep++) {
						l.add(n(burst * 16 + beep * 2, SoundEvents.NOTE_BLOCK_BIT, 2.0F, 1.0F));
						l.add(n(burst * 16 + beep * 2, SoundEvents.NOTE_BLOCK_PLING, 1.9F, 0.6F));
					}
				}
				play(Group.TIMER, l);
			}
			default -> play(Group.TIMER, List.of(
					n(0, SoundEvents.NOTE_BLOCK_BELL, 1.2F, 1.0F),
					n(0, SoundEvents.NOTE_BLOCK_BELL, 1.6F, 1.0F)));
		}
	}

	/** Dreiklang aufwärts mit einem Notenblock-Klang. */
	public static List<Note> arpeggio(Holder<SoundEvent> s, float base, float volume, int step) {
		return List.of(n(0, s, base, volume), n(step, s, base * 1.26F, volume), n(2 * step, s, base * 1.5F, volume),
				n(3 * step, s, base * 2.0F, volume * 0.8F));
	}
}
