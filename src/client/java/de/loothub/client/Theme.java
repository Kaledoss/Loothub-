package de.loothub.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Farbschema: Akzentfarbe für Menüs, pulsierende Ränder und das eingebaute Ressourcenpaket
 * (je Farbe ein eigenes Paket „dark_inventory[_farbe]“), dazu die Deckkraft der Hintergründe.
 */
public final class Theme {
	/** Akzent: Name, Paket-Endung, Hauptfarbe, dunkle Farbe, Puls dunkel/hell (RGB). */
	public enum Accent {
		RED("Rot", "", 0xFFE23B3B, 0xFF8E1F26, 0x5A1018, 0xFF3040),
		BLUE("Blau", "_blue", 0xFF3B7BE2, 0xFF1F468E, 0x10285A, 0x4080FF),
		GREEN("Grün", "_green", 0xFF3BC25A, 0xFF1F7A35, 0x105A20, 0x40E060),
		PURPLE("Lila", "_purple", 0xFFA94BE2, 0xFF5E1F8E, 0x38105A, 0xB050FF),
		GOLD("Gold", "_gold", 0xFFE2A93B, 0xFF8E631F, 0x5A3A10, 0xFFC040),
		CYAN("Türkis", "_cyan", 0xFF3BC8D2, 0xFF1F7A82, 0x10505A, 0x40E8F0);

		public final String label;
		final String packSuffix;
		final int main, dark, pulseDark, pulseBright;

		Accent(String label, String packSuffix, int main, int dark, int pulseDark, int pulseBright) {
			this.label = label;
			this.packSuffix = packSuffix;
			this.main = main;
			this.dark = dark;
			this.pulseDark = pulseDark;
			this.pulseBright = pulseBright;
		}
	}

	private Theme() {
	}

	public static Accent accent() {
		try {
			return Accent.valueOf(WidgetConfig.get().accent);
		} catch (RuntimeException e) {
			return Accent.RED;
		}
	}

	/** Menüfarben setzen (beim Start und nach jedem Wechsel). */
	public static void applyColors() {
		Accent a = accent();
		LoothubScreen.RED = a.main;
		LoothubScreen.RED_DARK = a.dark;
	}

	public static int pulseDark() {
		return accent().pulseDark;
	}

	public static int pulseBright() {
		return accent().pulseBright;
	}

	/** Hintergrundfarbe mit eingestellter Deckkraft (Alpha der Vorlage × Regler). */
	public static int bg(int argb) {
		double f = Math.max(0.1, Math.min(1.0, WidgetConfig.get().backgroundOpacity));
		int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * f);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	/** Passendes Ressourcenpaket aktivieren, die anderen Farbvarianten abwählen, dann neu laden. */
	public static void applyPack() {
		Minecraft mc = Minecraft.getInstance();
		PackRepository repo = mc.getResourcePackRepository();
		String wanted = "dark_inventory" + accent().packSuffix;
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		boolean wasOn = selected.removeIf(id -> id.contains("loothub") && id.contains("dark_inventory"));
		if (!wasOn && !WidgetConfig.get().themePackWanted) {
			return; // Paket war vom Spieler abgeschaltet
		}
		for (String id : repo.getAvailableIds()) {
			if (id.contains("loothub") && (id.endsWith(":" + wanted) || id.endsWith("/" + wanted))) {
				selected.add(id);
				break;
			}
		}
		repo.setSelected(selected);
		mc.options.updateResourcePacks(repo);
	}
}
