package de.loothub.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import org.lwjgl.glfw.GLFW;

import de.loothub.Loothub;
import de.loothub.client.jobs.JobHistoryScreen;
import de.loothub.client.jobs.JobTracker;
import de.loothub.client.stats.StatsStore;
import de.loothub.client.stats.StatsTracker;
import de.loothub.client.jobs.JobWidget;

public class LoothubClient implements ClientModInitializer {
	// Eigene Kategorie unter Optionen > Steuerung > Tastenbelegung
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "main"));

	private static KeyMapping menuKey;
	private static boolean updateNoticeShown;
	/** /loothub öffnet die Einstellungen erst im nächsten Tick – sonst schließt der Chat sie gleich wieder. */
	private static boolean openSettings;

	@Override
	public void onInitializeClient() {
		// L öffnet das Loothub+-Menü mit dem ersten Reiter „Verlauf“ (daneben „Markt“ und „Job-Widget“).
		// Hinweis: In Minecraft liegt L standardmäßig auf „Fortschritte“ – das bitte unter Steuerung umlegen.
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.loothub.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY));

		// Eigene Statistik
		ClientTickEvents.END_CLIENT_TICK.register(StatsTracker::tick);
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> StatsTracker.onBlockBroken(state));
		// Werkzeug-Einsätze per Rechtsklick (in die Luft bzw. auf einen Block, z. B. Hacke, Axt, Schaufel)
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClientSide() && player == net.minecraft.client.Minecraft.getInstance().player) {
				StatsTracker.onToolUse(player.getItemInHand(hand));
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClientSide() && player == net.minecraft.client.Minecraft.getInstance().player) {
				StatsTracker.onToolUse(player.getItemInHand(hand));
			}
			return InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClientSide()) {
				StatsTracker.onAttack(entity);
			}
			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Einmal pro Spielstart im Chat auf ein verfügbares Update hinweisen
			if (!updateNoticeShown && client.player != null && Updater.state() == Updater.State.AVAILABLE) {
				updateNoticeShown = true;
				client.player.displayClientMessage(Component.translatable("loothub.update.notice",
						Updater.latestVersion()).withStyle(ChatFormatting.GOLD), false);
			}
			if (openSettings && client.screen == null) {
				openSettings = false;
				client.setScreen(new SettingsScreen());
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

		// Pokémon Community Game: Spawn-Anzeige oben rechts, Knöpfe über dem Inventar, Twitch-Chat im Hintergrund
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "pcg"), de.loothub.client.pcg.PcgOverlay::renderHud);
		de.loothub.client.pcg.PcgPanel.register();
		{
			de.loothub.client.jobs.WidgetConfig wc = de.loothub.client.jobs.WidgetConfig.get();
			de.loothub.client.pcg.TwitchChat.ensure(wc.pcgEnabled, wc.pcgChannel, wc.pcgUser);
		}

		// YouTube Music: „Läuft gerade“ im HUD, im Chat verschiebbar
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "music"), de.loothub.client.music.MusicWidget::renderHud);
		de.loothub.client.music.MusicWidget.register();
		de.loothub.client.music.YtMusic.ensure(de.loothub.client.jobs.WidgetConfig.get().musicEnabled);

		// Beim Verlassen des Servers beginnt eine neue Job-Sitzung
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			JobTracker.reset();
			StatsStore.saveIfDirty(true);
		});
		// Beim Beenden des Spiels die laufende Sitzung noch im Verlauf speichern
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			de.loothub.client.pcg.TwitchChat.stop();
			JobTracker.reset();
			StatsStore.saveIfDirty(true);
		});

		Theme.applyColors();
		Updater.checkOnStartup();

		// /loothub → Einstellungen
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal(Loothub.MOD_ID).executes(ctx -> {
					openSettings = true;
					return 1;
				})));
		// Eingebautes Ressourcenpaket: Inventar in Schwarz-Rot (unter Optionen → Ressourcenpakete abschaltbar)
		FabricLoader.getInstance().getModContainer(Loothub.MOD_ID).ifPresent(mod -> {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "dark_inventory"), mod,
					Component.literal("Loothub+ Inventar (Schwarz-Rot)"), PackActivationType.DEFAULT_ENABLED);
			// Farbvarianten – gewählt wird über /loothub → Farbschema
			for (Theme.Accent a : Theme.Accent.values()) {
				if (a != Theme.Accent.RED) {
					String path = "dark_inventory_" + a.name().toLowerCase(java.util.Locale.ROOT);
					ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, path), mod,
							Component.literal("Loothub+ Inventar (Schwarz-" + a.label + ")"), PackActivationType.NORMAL);
				}
			}
			// Bossleisten (z. B. Erfolge, Jobitems) mit Farbverlauf Rot → Orange → Gelb → Grün
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "progress_bars"), mod,
					Component.literal("Loothub+ Fortschrittsbalken"), PackActivationType.DEFAULT_ENABLED);
		});

		PriceTooltip.register();
		de.loothub.client.quick.QuickCommandPanel.register();
		de.loothub.client.quick.InventoryTitle.register();
		de.loothub.client.quick.ToolProtection.register();
		// Preise gleich beim Start im Hintergrund laden, damit der erste Tooltip schon Daten hat
		MarketPrices.refreshIfStale();
	}
}
