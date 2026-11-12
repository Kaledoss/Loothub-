package de.loothub.client.quick;

import java.util.List;
import java.util.stream.Collectors;

import de.loothub.Loothub;

/**
 * Diagnose für Befehle, die an den Server gehen: schreibt den Befehl und die Aufrufer ins Log
 * („Loothub-Befehlsprotokoll“). Die erste Stelle außerhalb von Minecraft/Fabric/Mixin zeigt, welche Mod oder welches
 * LabyMod-Addon ihn ausgelöst hat.
 */
public final class CommandTrace {
	private static final List<String> SKIP = List.of("java.", "jdk.", "sun.", "net.minecraft.", "com.mojang.",
			"net.fabricmc.", "org.spongepowered.", "com.llamalad7.", "de.loothub.client.mixin.", "de.loothub.client.quick.CommandTrace");

	private CommandTrace() {
	}

	public static void log(String command) {
		try {
			List<String> frames = StackWalker.getInstance().walk(s -> s
					.map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
					.limit(40).collect(Collectors.toList()));
			String origin = frames.stream().filter(f -> SKIP.stream().noneMatch(f::startsWith)).findFirst()
					.orElse("Minecraft selbst (z. B. eingetippt)");
			String path = frames.stream().filter(f -> !f.startsWith("java.") && !f.startsWith("jdk."))
					.skip(1).limit(8).collect(Collectors.joining(" <- "));
			Loothub.LOGGER.info("Loothub-Befehlsprotokoll: /{} – Auslöser: {} | Weg: {}", command, origin, path);
		} catch (RuntimeException e) {
			Loothub.LOGGER.info("Loothub-Befehlsprotokoll: /{}", command);
		}
	}
}
