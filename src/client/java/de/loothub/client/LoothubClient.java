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
	private static KeyMapping emoteKey;
	private static boolean updateNoticeShown;
	/** /loothub öffnet die Einstellungen erst im nächsten Tick – sonst schließt der Chat sie gleich wieder. */
	private static boolean openSettings;
	private static volatile String openComposer;

	@Override
	public void onInitializeClient() {
		// L öffnet das Loothub+-Menü mit dem ersten Reiter „Verlauf“ (daneben „Markt“ und „Job-Widget“).
		// Hinweis: In Minecraft liegt L standardmäßig auf „Fortschritte“ – das bitte unter Steuerung umlegen.
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.loothub.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY));
		// B öffnet das Emote-Rad der Mini-Mes
		emoteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.loothub.emotes", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));

		// Eigene Statistik
		ClientTickEvents.END_CLIENT_TICK.register(StatsTracker::tick);
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> StatsTracker.onBlockBroken(pos, state));
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
			if (openComposer != null && client.screen == null) {
				String text = openComposer;
				openComposer = null;
				client.setScreen(new de.loothub.client.quick.ChatComposerScreen(text));
			}
			while (emoteKey.consumeClick()) {
				if (client.screen == null && client.player != null) {
					client.setScreen(new de.loothub.client.cosmetics.EmoteWheelScreen());
				}
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

		de.loothub.client.quick.OreGlow.register();
		de.loothub.client.quick.ConnectedTextures.register();
		de.loothub.client.quick.LookInfo.register();
		de.loothub.client.quick.RarityFrames.register();
		de.loothub.client.quick.Timers.register();
		de.loothub.client.Sounds.register();
		de.loothub.client.quick.CustomChat.register();
		de.loothub.client.cosmetics.Life.register();
		de.loothub.client.quick.ShaderPack.install();
		// Eigene Timer (Restzeit)
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "timers"), de.loothub.client.quick.Timers::renderHud);
		// Block-Info oben in der Mitte (was man anschaut)
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "look_info"), de.loothub.client.quick.LookInfo::renderHud);

		// Inventar über der Hotbar
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "inventory_hud"), de.loothub.client.quick.InventoryHud::renderHud);

		// YouTube Music: „Läuft gerade“ im HUD, im Chat verschiebbar
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "music"), de.loothub.client.music.MusicWidget::renderHud);
		de.loothub.client.music.MusicWidget.register();

		// Streamer-Modus: offene Kanalbelohnungen im HUD, Twitch-Chat und Belohnungen im Hintergrund
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "redemptions"), de.loothub.client.streamer.RedemptionHud::renderHud);
		de.loothub.client.streamer.RedemptionHud.register();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "stream_alerts"), de.loothub.client.streamer.Alerts::renderHud);
		de.loothub.client.streamer.Streamer.apply();
		// Fang-Einblendung (gefangener Fisch), im Chat verschiebbar
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "fish_alert"), de.loothub.client.jobs.FishAlert::renderHud);
		de.loothub.client.jobs.FishAlert.register();
		de.loothub.client.quick.AuctionWatch.register();
		de.loothub.client.music.YtMusic.ensure(de.loothub.client.jobs.WidgetConfig.get().musicEnabled);

		// Beim Verlassen des Servers nur speichern – die Job-Sitzung läuft bis zum Schließen des Spiels weiter
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			StatsStore.saveIfDirty(true);
			AsyncSave.flushAll(); // ausstehende Speicherungen noch schreiben (nicht beenden: man kann wieder beitreten)
		});
		// Beim Beenden des Spiels die laufende Sitzung noch im Verlauf speichern
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			de.loothub.client.pcg.TwitchChat.stop();
			de.loothub.client.streamer.Streamer.stopAll();
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
		// /chat → Nachricht mit Farbcodes in Ruhe vorbereiten (mit Vorschau); /chat <Text> füllt das Feld vor
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("chat").executes(ctx -> {
					openComposer = "";
					return 1;
				}).then(ClientCommandManager.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
						.executes(ctx -> {
							openComposer = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text");
							return 1;
						}))));
		// Eingebautes Ressourcenpaket: Inventar in Schwarz-Rot (unter Optionen → Ressourcenpakete abschaltbar)
		FabricLoader.getInstance().getModContainer(Loothub.MOD_ID).ifPresent(mod -> {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "dark_inventory"), mod,
					Component.literal("Loothub"),
					de.loothub.client.jobs.WidgetConfig.get().inventoryDesign
							? PackActivationType.DEFAULT_ENABLED : PackActivationType.NORMAL);
		});

		PriceTooltip.register();
		de.loothub.client.quick.QuickCommandPanel.register();
		de.loothub.client.quick.InventoryTitle.register();
		de.loothub.client.quick.ToolProtection.register();
		de.loothub.client.quick.RenameScreen.register();
		de.loothub.client.quick.AmountShortcut.register();
		DailyMerchantItem.register();
		de.loothub.client.quick.ContainerValue.register();
		de.loothub.client.jobs.MarketSales.register();
		de.loothub.client.quick.AfkView.register();
		de.loothub.client.quick.OtherAfk.register();
		de.loothub.client.cosmetics.Cosmetics.register();
		de.loothub.client.cosmetics.CosmeticsServer.register();
		de.loothub.client.cosmetics.EmoteShare.register();
		de.loothub.client.cosmetics.CosmeticEffects.register();
		de.loothub.client.itemdex.Itemdex.register();
		de.loothub.client.jobs.TreasureWatcher.register();
		de.loothub.client.jobs.FishingLog.register();
		// Preise gleich beim Start im Hintergrund laden, damit der erste Tooltip schon Daten hat
		MarketPrices.refreshIfStale();
	}
}
