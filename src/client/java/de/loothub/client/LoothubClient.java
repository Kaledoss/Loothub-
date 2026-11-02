package de.loothub.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import org.lwjgl.glfw.GLFW;

import de.loothub.Loothub;
import de.loothub.client.jobs.JobHistoryScreen;
import de.loothub.client.jobs.JobTracker;
import de.loothub.client.jobs.JobWidget;

public class LoothubClient implements ClientModInitializer {
	// Eigene Kategorie unter Optionen > Steuerung > Tastenbelegung
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "main"));

	private static KeyMapping menuKey;
	private static boolean updateNoticeShown;

	@Override
	public void onInitializeClient() {
		// L öffnet das Loothub+-Menü mit dem ersten Reiter „Verlauf“ (daneben „Markt“ und „Job-Widget“).
		// Hinweis: In Minecraft liegt L standardmäßig auf „Fortschritte“ – das bitte unter Steuerung umlegen.
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.loothub.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Einmal pro Spielstart im Chat auf ein verfügbares Update hinweisen
			if (!updateNoticeShown && client.player != null && Updater.state() == Updater.State.AVAILABLE) {
				updateNoticeShown = true;
				client.player.displayClientMessage(Component.translatable("loothub.update.notice",
						Updater.latestVersion()).withStyle(ChatFormatting.GOLD), false);
			}
			while (menuKey.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new JobHistoryScreen());
				}
			}
		});

		// Job-Widget im HUD (vor dem Chat zeichnen)
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "job_widget"), JobWidget::renderHud);

		// Beim Verlassen des Servers beginnt eine neue Job-Sitzung
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> JobTracker.reset());
		// Beim Beenden des Spiels die laufende Sitzung noch im Verlauf speichern
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> JobTracker.reset());

		Updater.checkAsync();
		PriceTooltip.register();
		// Preise gleich beim Start im Hintergrund laden, damit der erste Tooltip schon Daten hat
		MarketPrices.refreshIfStale();
	}
}
