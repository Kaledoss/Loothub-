package de.loothub.client.feedback;

import java.util.Map;

import net.minecraft.client.resources.language.I18n;

/** Lesbare Namen der Loothub-Schalter für die Admin-Übersicht (Titel aus den Einstellungen, sonst eigene Namen). */
final class FeatureNames {
	private static final Map<String, String> SETTINGS = Map.ofEntries(
			Map.entry("afkView", "loothub.settings.afk_view"),
			Map.entry("amountShortcut", "loothub.settings.amount_shortcut"),
			Map.entry("auctionAlarm", "loothub.settings.auction_alarm"),
			Map.entry("autoRefill", "loothub.settings.auto_refill"),
			Map.entry("baitWarning", "loothub.settings.bait_warning"),
			Map.entry("chatCopy", "loothub.settings.chat_copy"),
			Map.entry("chatFilter", "loothub.settings.chat_filter"),
			Map.entry("chatMention", "loothub.settings.chat_mention"),
			Map.entry("chatMentionSound", "loothub.settings.chat_mention_sound"),
			Map.entry("chatTimestamps", "loothub.settings.chat_time"),
			Map.entry("compressorIcon", "loothub.settings.compressor_icon"),
			Map.entry("connectedTextures", "loothub.settings.connected_textures"),
			Map.entry("containerValue", "loothub.settings.container_value"),
			Map.entry("customChat", "loothub.settings.custom_chat"),
			Map.entry("dropProtection", "loothub.settings.drop_protection"),
			Map.entry("durabilityNumbers", "loothub.settings.durability_numbers"),
			Map.entry("fishAlert", "loothub.settings.fish_alert"),
			Map.entry("fishChatHide", "loothub.settings.fish_chat_hide"),
			Map.entry("flyPose", "loothub.settings.fly_pose"),
			Map.entry("foodHud", "loothub.settings.food_hud"),
			Map.entry("glowingOres", "loothub.settings.ore_glow"),
			Map.entry("hideOffhandBackpack", "loothub.settings.hide_offhand"),
			Map.entry("hideOwnCape", "loothub.settings.hide_cape"),
			Map.entry("hideOwnHeadItem", "loothub.settings.hide_head"),
			Map.entry("hotbarAnim", "loothub.settings.hotbar_anim"),
			Map.entry("invHudEnabled", "loothub.settings.inv_hud"),
			Map.entry("invMove", "loothub.settings.inv_move"),
			Map.entry("invSort", "loothub.settings.inv_sort"),
			Map.entry("inventoryAnim", "loothub.settings.inventory_anim"),
			Map.entry("inventoryDesign", "loothub.settings.inventory_design"),
			Map.entry("itemdexEnabled", "loothub.settings.itemdex"),
			Map.entry("jobWidgetEnabled", "loothub.settings.section.job_widget"),
			Map.entry("lookInfo", "loothub.settings.look_info"),
			Map.entry("loothubCrown", "loothub.settings.crown"),
			Map.entry("marketSales", "loothub.settings.market_sales"),
			Map.entry("menuSkin", "loothub.settings.menu_skin"),
			Map.entry("musicEnabled", "loothub.settings.music"),
			Map.entry("offhandFilter", "loothub.settings.offhand"),
			Map.entry("pcgEnabled", "loothub.settings.pcg"),
			Map.entry("pcgSound", "loothub.settings.pcg_sound"),
			Map.entry("pickupWarning", "loothub.settings.pickup_warning"),
			Map.entry("pokedexPage", "loothub.settings.pokedex_page"),
			Map.entry("progress", "loothub.settings.progress"),
			Map.entry("progressTitles", "loothub.settings.progress_titles"),
			Map.entry("pulseBorders", "loothub.settings.pulse"),
			Map.entry("quickPanelEnabled", "loothub.settings.quick_panel"),
			Map.entry("rarityFrames", "loothub.settings.rarity_frames"),
			Map.entry("renameWindow", "loothub.settings.rename_window"),
			Map.entry("sellHighlight", "loothub.settings.sell_highlight"),
			Map.entry("signWindow", "loothub.settings.sign_window"),
			Map.entry("statsEnabled", "loothub.settings.stats"),
			Map.entry("streamerMode", "loothub.settings.streamer"),
			Map.entry("timerSound", "loothub.settings.timer_sound"),
			Map.entry("timersEnabled", "loothub.settings.timers"),
			Map.entry("toolProtection", "loothub.settings.tool_protection"),
			Map.entry("toolSaver", "loothub.settings.tool_saver"),
			Map.entry("tooltipPrices", "loothub.settings.tooltip_prices"),
			Map.entry("updateCheck", "loothub.settings.update_check"),
			Map.entry("villagerTrade", "loothub.settings.villager_trade"));

	private FeatureNames() {
	}

	static String of(String field) {
		String key = SETTINGS.get(field);
		if (key != null && I18n.exists(key)) {
			return I18n.get(key).replaceAll("§.", "");
		}
		if (I18n.exists("loothub.feat." + field)) {
			return I18n.get("loothub.feat." + field);
		}
		return field.replaceAll("([a-z])([A-Z])", "$1 $2");
	}
}
