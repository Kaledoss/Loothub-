package de.loothub.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Daten, die zu einem Minecraft-Konto gehören (Statistik, Job-Verlauf, Fänge, Rucksack), liegen je Konto in einer
 * eigenen Datei: {@code loothub-stats.<uuid>.json}. Die alten gemeinsamen Dateien bekommt einmalig das erste Konto,
 * das nach dem Update spielt (in {@code loothub-konto-alt.txt} vermerkt); andere Konten fangen bei null an.
 */
public final class Accounts {
	private static final String CLAIM = "loothub-konto-alt.txt";

	private Accounts() {
	}

	/** Kennung des angemeldeten Kontos (UUID ohne Striche), oder "offline". */
	public static String id() {
		UUID u = Minecraft.getInstance().getUser().getProfileId();
		return u == null ? "offline" : u.toString().replace("-", "").toLowerCase(Locale.ROOT);
	}

	/** Pfad für eine kontobezogene Datei, z. B. file("loothub-stats.json") → loothub-stats.&lt;id&gt;.json. */
	public static synchronized Path file(String name) {
		Path dir = FabricLoader.getInstance().getConfigDir();
		int dot = name.lastIndexOf('.');
		String mine = name.substring(0, dot) + "." + id() + name.substring(dot);
		Path p = dir.resolve(mine);
		Path legacy = dir.resolve(name);
		if (!Files.exists(p) && Files.exists(legacy) && claimLegacy(dir)) {
			try {
				Files.move(legacy, p, StandardCopyOption.REPLACE_EXISTING);
				Loothub.LOGGER.info("Loothub: {} gehört jetzt zum Konto {}", name, id());
			} catch (IOException e) {
				Loothub.LOGGER.warn("Loothub: {} konnte nicht dem Konto zugeordnet werden", name, e);
			}
		}
		return p;
	}

	/** Darf dieses Konto die alten gemeinsamen Dateien übernehmen? Nur das erste nach dem Update. */
	private static boolean claimLegacy(Path dir) {
		Path c = dir.resolve(CLAIM);
		try {
			if (Files.exists(c)) {
				return Files.readString(c, StandardCharsets.UTF_8).strip().equals(id());
			}
			if (id().equals("offline")) {
				return false;
			}
			Files.writeString(c, id(), StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
