package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.jobs.JobTracker;
import de.loothub.client.jobs.JobWidget;
import de.loothub.client.jobs.JobWidgetEditScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Seite „Einstellungen“ (auch über {@code /loothub}): oben Version und Update-Stand, darunter Karten mit
 * Schalter links und Beschreibung rechts – Auto-Update, Preise im Tooltip, Statistik und Job-Widget.
 */
public class SettingsScreen extends LoothubScreen {
	private static final int CARD = 0xE01C1315;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int TRACK_OFF = 0xFF3A2A2C;
	private static final int GREEN = 0xFF5BD68A;
	private static final int GOLD = 0xFFFFC040;
	private static final int GAP = 6;

	/** Ein Schalter auf einer Karte. */
	private record Toggle(int x, int y, BooleanSupplier get, Consumer<Boolean> set) {
		static final int W = 30;
		static final int H = 12;

		boolean contains(double mx, double my) {
			return mx >= x && mx < x + W && my >= y && my < y + H;
		}
	}

	/** Eine Karte: Titel + optionaler Schalter links, Beschreibung rechts. */
	private record Card(int y, int h, String titleKey, String descKey, Toggle toggle) {
	}

	/** Kästchen für eine Zeile des Job-Widgets. */
	private record Check(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y - 1 && my < y + 10;
		}
	}

	private final List<Card> cards = new ArrayList<>();
	/** Abschnitts-Überschriften: {y} und Schlüssel (bei der Suche ausgeblendet). */
	private final List<Object[]> sections = new ArrayList<>();
	private final List<Check> checks = new ArrayList<>();
	private final List<net.minecraft.client.gui.components.AbstractWidget> buttons = new ArrayList<>();
	/** Scroll-Position bleibt beim Neuaufbau (Fenstergröße, Scrollen) erhalten. */
	private static int scroll;
	private int contentH;
	private boolean needsRebuild;
	private int rowsLabelY;
	private int pcgLabelY;
	/** Position der kleinen Farbfelder auf den drei Farbknöpfen. */
	private final int[][] colorSwatches = new int[3][];
	private int[] invHudSwatch;
	private int musicCardY;
	private int pcgFieldX;
	private int pcgFieldW;
	private Button resetButton;
	private long resetFeedbackUntil;
	private int versionY;
	private int labelW;

	/** Reiter der Einstellungen. */
	private enum Tab {
		GENERAL("loothub.settings.tab.general"),
		LOOK("loothub.settings.tab.look"),
		PACK("loothub.settings.tab.pack"),
		CHAT("loothub.settings.tab.chat"),
		INVENTORY("loothub.settings.tab.inventory"),
		JOBS("loothub.settings.tab.jobs"),
		SOUNDS("loothub.settings.tab.sounds"),
		EXTRAS("loothub.settings.tab.extras");

		final String key;

		Tab(String key) {
			this.key = key;
		}
	}

	/** Suche über alle Einstellungen: Treffer (Reiter, Karte, Abstand zum Anfang des Reiters). */
	private record Result(Tab tab, String titleKey, String descKey, int offset) {
	}

	private static final int SEARCH_W = 110;
	private static String query = "";
	private static boolean searchFocused;
	private List<Result> index;
	private boolean indexing;
	private net.minecraft.client.gui.components.EditBox searchBox;

	private static final int TAB_H = 22;
	private static Tab tab = Tab.GENERAL;
	/** Unterseiten des Inventar-Reiters. */
	private static final String[] INV_SUBS = {"loothub.settings.inv.sub.menus", "loothub.settings.inv.sub.tools",
			"loothub.settings.inv.sub.display", "loothub.settings.inv.sub.quick"};
	private static int invSub;
	/** Oberkante des scrollbaren Bereichs (unter den Reitern). */
	private int top;

	public SettingsScreen() {
		super(Page.SETTINGS);
	}

	@Override
	protected void initPage() {
		pickButton = null;
		cards.clear();
		checks.clear();
		sections.clear();
		buttons.clear();
		WidgetConfig c = WidgetConfig.get();
		labelW = Math.min(130, cw / 3);
		colorSwatches[0] = colorSwatches[1] = colorSwatches[2] = null;
		invHudSwatch = null;

		// Einmal alle Reiter durchgehen und die Karten für die Suche merken
		if (index == null && !indexing) {
			indexing = true;
			Tab saved = tab;
			int savedScroll = scroll;
			List<Result> idx = new ArrayList<>();
			for (Tab t : Tab.values()) {
				tab = t;
				scroll = 0;
				rebuildWidgets();
				for (Card cd : cards) {
					idx.add(new Result(t, cd.titleKey(), cd.descKey(), cd.y() - top));
				}
			}
			tab = saved;
			scroll = savedScroll;
			needsRebuild = false;
			index = idx;
			indexing = false;
			rebuildWidgets();
			return;
		}

		// Reiter oben (fest, scrollt nicht mit), rechts daneben die Suche
		int tabX = cl;
		int searchW = Math.min(SEARCH_W, cw / 4);
		int tabW = (cw - searchW - 4 - (Tab.values().length - 1) * 3) / Tab.values().length;
		searchBox = new net.minecraft.client.gui.components.EditBox(font, cr - searchW, ct, searchW, 16,
				Component.translatable("loothub.settings.search"));
		searchBox.setMaxLength(40);
		searchBox.setHint(Component.translatable("loothub.settings.search").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setValue(query);
		searchBox.setResponder(v -> {
			if (!v.equals(query)) {
				query = v;
				searchFocused = true;
				scroll = 0;
				needsRebuild = true; // im nächsten Tick neu aufbauen (nicht mitten in der Eingabe)
			}
		});
		addRenderableWidget(searchBox);
		if (searchFocused) {
			setFocused(searchBox);
		}
		// Bei der Suche: Reiter ohne Treffer überspringen
		if (searching() && matchCount(tab) == 0) {
			for (Tab t : Tab.values()) {
				if (matchCount(t) > 0) {
					tab = t;
					scroll = 0;
					break;
				}
			}
		}
		for (Tab t : Tab.values()) {
			int n = searching() ? matchCount(t) : -1;
			Component label = n < 0 ? Component.translatable(t.key)
					: Component.translatable(t.key).append(Component.literal(" (" + n + ")").withStyle(n > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
			Button tb = Button.builder(label, b -> {
				tab = t;
				scroll = 0;
				rebuildWidgets();
			}).bounds(tabX, ct, tabW, 16).build();
			tb.active = t != tab && n != 0;
			addRenderableWidget(tb);
			tabX += tabW + 3;
		}
		top = ct + TAB_H;
		int y = top - scroll;
		int contentStart = y;

		switch (tab) {
			case GENERAL -> {
				// Versionsblock
				versionY = y;
				int bw = 100;
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.update_now"), b -> Updater.checkAgain())
						.bounds(cr - 8 - bw, versionY + 24, bw, 16).build()));
				y = versionY + 50;
				y = card(y, 40, "loothub.settings.update_check", "loothub.settings.update_check.tooltip",
						() -> c.updateCheck, v -> c.updateCheck = v);
				{
					int hY = y;
					int hDescX = cl + labelW + 12;
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.hud_layout.open"),
							b -> minecraft.setScreen(new HudLayoutScreen(this)))
							.bounds(hDescX, hY + 24, Math.min(160, cr - 8 - hDescX), 16).build()));
					y = cardNoToggle(hY, 46, "loothub.settings.hud_layout", "loothub.settings.hud_layout.tooltip");
				}
				y = card(y, 40, "loothub.settings.stats", "loothub.settings.stats.tooltip",
						() -> c.statsEnabled, v -> c.statsEnabled = v);
				y = card(y, 40, "loothub.settings.timers", "loothub.settings.timers.tooltip",
						() -> c.timersEnabled, v -> c.timersEnabled = v);
				y = card(y, 40, "loothub.settings.itemdex", "loothub.settings.itemdex.tooltip",
						() -> c.itemdexEnabled, v -> c.itemdexEnabled = v);
			}
			case LOOK -> {
				y = section(y, "loothub.settings.section.world");
				// Farbschema und Deckkraft
				{
					int thY = y;
					int tDescX = cl + labelW + 12;
					int tW = Math.min(300, cr - 8 - tDescX);
					// Drei Farbknöpfe: innen, außen, pulsieren – je ein Farbwähler
					int third = (tW - 8) / 3;
					String[] keys = {"inner", "outer", "pulse"};
					for (int i = 0; i < 3; i++) {
						String key = keys[i];
						buttons.add(addRenderableWidget(Button.builder(
										Component.translatable("loothub.settings.theme." + key), b -> {
											int current = switch (key) {
												case "inner" -> Theme.inner();
												case "outer" -> Theme.outer();
												default -> Theme.pulse();
											};
											minecraft.setScreen(new ColorPickerScreen(this,
													Component.translatable("loothub.settings.theme." + key + ".title"), current, rgb -> {
												switch (key) {
													case "inner" -> c.colorInner = rgb;
													case "outer" -> c.colorOuter = rgb;
													default -> c.colorPulse = rgb;
												}
												c.save();
												Theme.applyColors();
												if (key.equals("inner")) {
													Theme.applyPack();
												}
											}));
										})
								.bounds(tDescX + i * (third + 4), thY + 18, third, 16).build()));
						colorSwatches[i] = new int[]{tDescX + i * (third + 4) + third - 12, thY + 22};
			}
					buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
							tDescX, thY + 38, tW, 16, Component.empty(), (c.backgroundOpacity - 0.1) / 0.9) {
						{
							updateMessage();
						}

						@Override
						protected void updateMessage() {
							setMessage(Component.translatable("loothub.settings.theme.opacity", (int) Math.round((0.1 + value * 0.9) * 100)));
						}

						@Override
						protected void applyValue() {
							c.backgroundOpacity = 0.1 + value * 0.9;
						}
					}));
					y = cardNoToggle(thY, 60, "loothub.settings.theme", "loothub.settings.theme.tooltip");
				}
				y = card(y, 40, "loothub.settings.crown", "loothub.settings.crown.tooltip",
						() -> c.loothubCrown, v -> c.loothubCrown = v);
				y = card(y, 40, "loothub.settings.connected_textures", "loothub.settings.connected_textures.tooltip",
						() -> c.connectedTextures, v -> {
							c.connectedTextures = v;
							de.loothub.client.quick.ConnectedTextures.refresh();
						});
				{
					// Block-Info: an/aus, Werkzeug/Preis einzeln, Größe; verschieben im Chat
					int liY = y;
					int lDescX = cl + labelW + 12;
					int lW = Math.min(300, cr - 8 - lDescX);
					int third = (lW - 12) / 4;
					buttons.add(addRenderableWidget(Button.builder(lookLabel("tool", c.lookInfoTool), b -> {
						c.lookInfoTool = !c.lookInfoTool;
						b.setMessage(lookLabel("tool", c.lookInfoTool));
					}).bounds(lDescX, liY + 38, third, 16).build()));
					buttons.add(addRenderableWidget(Button.builder(lookLabel("price", c.lookInfoPrice), b -> {
						c.lookInfoPrice = !c.lookInfoPrice;
						b.setMessage(lookLabel("price", c.lookInfoPrice));
					}).bounds(lDescX + third + 4, liY + 38, third, 16).build()));
					buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
							lDescX + 2 * (third + 4), liY + 38, third, 16, Component.empty(), (c.lookInfoScale - 0.5) / 1.5) {
						{
							updateMessage();
						}

						@Override
						protected void updateMessage() {
							setMessage(Component.translatable("loothub.settings.look_info.size", (int) Math.round((0.5 + value * 1.5) * 100)));
						}

						@Override
						protected void applyValue() {
							c.lookInfoScale = (float) (0.5 + value * 1.5);
						}
					}));
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.look_info.move"),
							b -> minecraft.setScreen(new de.loothub.client.quick.LookInfoEditScreen(this)))
							.bounds(lDescX + 3 * (third + 4), liY + 38, third, 16).build()));
					buttons.add(addRenderableWidget(Button.builder(lookLabel("contents", c.lookInfoContents), b -> {
						c.lookInfoContents = !c.lookInfoContents;
						b.setMessage(lookLabel("contents", c.lookInfoContents));
					}).bounds(lDescX, liY + 58, 2 * third + 4, 16).build()));
					y = card(liY, 80, "loothub.settings.look_info", "loothub.settings.look_info.tooltip",
							() -> c.lookInfo, v -> c.lookInfo = v);
				}
				y = section(y, "loothub.settings.section.players");
				y = card(y, 40, "loothub.settings.hide_head", "loothub.settings.hide_head.tooltip",
						() -> c.hideOwnHeadItem, v -> c.hideOwnHeadItem = v);
				y = card(y, 40, "loothub.settings.hide_cape", "loothub.settings.hide_cape.tooltip",
						() -> c.hideOwnCape, v -> c.hideOwnCape = v);
				y = card(y, 40, "loothub.settings.fly_pose", "loothub.settings.fly_pose.tooltip",
						() -> c.flyPose, v -> c.flyPose = v);
				y = card(y, 40, "loothub.settings.hide_offhand", "loothub.settings.hide_offhand.tooltip",
						() -> c.hideOffhandBackpack, v -> c.hideOffhandBackpack = v);
				y = card(y, 40, "loothub.settings.afk_view", "loothub.settings.afk_view.tooltip",
						() -> c.afkView, v -> c.afkView = v);
			}
			case PACK -> {
				y = card(y, 40, "loothub.settings.inventory_design", "loothub.settings.inventory_design.tooltip",
						() -> c.inventoryDesign, v -> {
							c.inventoryDesign = v;
							Theme.setPackActive(v);
						});
				y = card(y, 40, "loothub.settings.pulse", "loothub.settings.pulse.tooltip",
						() -> c.pulseBorders, v -> c.pulseBorders = v);
				// Leuchtende Erze: an/aus + Stärke + Reichweite
				int oreY = y;
				int oDescX = cl + labelW + 12;
				int oW = Math.min(300, cr - 8 - oDescX);
				int half = (oW - 4) / 2;
				buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
						oDescX, oreY + 38, half, 16, Component.empty(), c.oreGlowStrength / 100.0) {
					{
						updateMessage();
					}

					@Override
					protected void updateMessage() {
						setMessage(Component.translatable("loothub.settings.ore_glow.strength", (int) Math.round(value * 100)));
					}

					@Override
					protected void applyValue() {
						c.oreGlowStrength = (int) Math.round(value * 100);
					}
				}));
				buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
						oDescX + half + 4, oreY + 38, half, 16, Component.empty(), (c.oreGlowRange - 4) / 28.0) {
					{
						updateMessage();
					}

					@Override
					protected void updateMessage() {
						setMessage(Component.translatable("loothub.settings.ore_glow.range", 4 + (int) Math.round(value * 28)));
					}

					@Override
					protected void applyValue() {
						c.oreGlowRange = 4 + (int) Math.round(value * 28);
					}
				}));
				// Erz-Auswahl als Kästchen
				String[] kinds = de.loothub.client.quick.OreGlow.KINDS;
				int oCols = 5;
				int oColW = (cr - 8 - oDescX) / oCols;
				for (int i = 0; i < kinds.length; i++) {
					String k = kinds[i];
					checks.add(new Check(oDescX + (i % oCols) * oColW, oreY + 60 + (i / oCols) * 14, oColW - 4,
							Component.translatable("loothub.ore." + k).getString(),
							() -> c.oreGlowOff == null || !c.oreGlowOff.contains(k), v -> {
								if (c.oreGlowOff == null) {
									c.oreGlowOff = new java.util.ArrayList<>();
								}
								c.oreGlowOff.remove(k);
								if (!v) {
									c.oreGlowOff.add(k);
								}
							}));
				}
				int oRows = (kinds.length + oCols - 1) / oCols;
				y = card(oreY, 64 + oRows * 14, "loothub.settings.ore_glow", "loothub.settings.ore_glow.tooltip",
						() -> c.glowingOres, v -> c.glowingOres = v);
				// eigenes Shaderpack (Iris)
				boolean iris = de.loothub.client.quick.ShaderPack.irisLoaded();
				y = card(y, 40, "loothub.settings.shader", iris ? "loothub.settings.shader.tooltip" : "loothub.settings.shader.no_iris",
						de.loothub.client.quick.ShaderPack::active, v -> de.loothub.client.quick.ShaderPack.set(v));
			}
			case CHAT -> {
				y = section(y, "loothub.settings.section.chat_window");
				y = card(y, 40, "loothub.settings.chat_time", "loothub.settings.chat_time.tooltip",
						() -> c.chatTimestamps, v -> c.chatTimestamps = v);
				y = card(y, 40, "loothub.settings.chat_copy", "loothub.settings.chat_copy.tooltip",
						() -> c.chatCopy, v -> c.chatCopy = v);
				y = card(y, 40, "loothub.settings.chat_mention", "loothub.settings.chat_mention.tooltip",
						() -> c.chatMention, v -> c.chatMention = v);
				y = card(y, 40, "loothub.settings.chat_mention_sound", "loothub.settings.chat_mention_sound.tooltip",
						() -> c.chatMentionSound, v -> c.chatMentionSound = v);
				y = card(y, 40, "loothub.settings.custom_chat", "loothub.settings.custom_chat.tooltip",
						() -> c.customChat, v -> {
							c.customChat = v;
							de.loothub.client.quick.CustomChat.invalidate();
						});
				{
					int fy = y;
					int fDescX = cl + labelW + 12;
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.chat_filter.edit",
									c.chatFilterWords == null ? 0 : c.chatFilterWords.size()),
							b -> minecraft.setScreen(new de.loothub.client.quick.ChatFilterScreen(this)))
							.bounds(fDescX, fy + 34, Math.min(160, cr - 8 - fDescX), 16).build()));
					y = card(y, 56, "loothub.settings.chat_filter", "loothub.settings.chat_filter.tooltip",
							() -> c.chatFilter, v -> c.chatFilter = v);
				}
				y = section(y, "loothub.settings.section.chat_tools");
				{
					int cY = y;
					int cDescX = cl + labelW + 12;
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.composer.open"),
							b -> minecraft.setScreen(new de.loothub.client.quick.ChatComposerScreen("")))
							.bounds(cDescX, cY + 30, Math.min(160, cr - 8 - cDescX), 16).build()));
					y = cardNoToggle(cY, 52, "loothub.settings.composer", "loothub.settings.composer.tooltip");
				}
				y = card(y, 40, "loothub.settings.fish_chat_hide", "loothub.settings.fish_chat_hide.tooltip",
						() -> c.fishChatHide, v -> c.fishChatHide = v);
			}
			case INVENTORY -> {
				// Unterseiten statt einer langen Liste (bei der Suche werden alle durchsucht)
				if (!searching()) {
					int sx = cl, sw = (cw - 3 * (INV_SUBS.length - 1)) / INV_SUBS.length;
					for (int i = 0; i < INV_SUBS.length; i++) {
						final int sub = i;
						Button sb = Button.builder(Component.translatable(INV_SUBS[i]), b -> {
							invSub = sub;
							scroll = 0;
							rebuildWidgets();
						}).bounds(sx + i * (sw + 3), y, sw, 16).build();
						sb.active = invSub != i;
						buttons.add(addRenderableWidget(sb));
					}
					y += 22;
				}
				if (invSub(0)) {
					y = card(y, 40, "loothub.settings.tooltip_prices", "loothub.settings.tooltip_prices.tooltip",
							() -> c.tooltipPrices, v -> c.tooltipPrices = v);
					y = card(y, 40, "loothub.settings.amount_shortcut", "loothub.settings.amount_shortcut.tooltip",
							() -> c.amountShortcut, v -> c.amountShortcut = v);
					y = card(y, 40, "loothub.settings.rename_window", "loothub.settings.rename_window.tooltip",
							() -> c.renameWindow, v -> c.renameWindow = v);
					y = card(y, 40, "loothub.settings.sign_window", "loothub.settings.sign_window.tooltip",
							() -> c.signWindow, v -> c.signWindow = v);
					{
						int aY = y;
						int aDescX = cl + labelW + 12;
						buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
								aDescX, aY + 34, Math.min(200, cr - 8 - aDescX), 16, Component.empty(),
								(Math.max(1, Math.min(60, c.auctionAlarmMinutes)) - 1) / 59.0) {
							{
								updateMessage();
							}

							@Override
							protected void updateMessage() {
								setMessage(Component.translatable("loothub.settings.auction_alarm.minutes", 1 + (int) Math.round(value * 59)));
							}

							@Override
							protected void applyValue() {
								c.auctionAlarmMinutes = 1 + (int) Math.round(value * 59);
							}
						}));
						y = card(aY, 56, "loothub.settings.auction_alarm", "loothub.settings.auction_alarm.tooltip",
								() -> c.auctionAlarm, v -> c.auctionAlarm = v);
					}
				}
				if (invSub(1)) {
					y = card(y, 40, "loothub.settings.inv_sort", "loothub.settings.inv_sort.tooltip",
							() -> c.invSort, v -> c.invSort = v);
					y = card(y, 40, "loothub.settings.inv_move", "loothub.settings.inv_move.tooltip",
							() -> c.invMove, v -> c.invMove = v);
					y = card(y, 40, "loothub.settings.auto_refill", "loothub.settings.auto_refill.tooltip",
							() -> c.autoRefill, v -> c.autoRefill = v);
					y = card(y, 40, "loothub.settings.tool_saver", "loothub.settings.tool_saver.tooltip",
							() -> c.toolSaver, v -> c.toolSaver = v);
					// Werkzeugschutz: Schalter, Regler für die Grenze, Kästchen je Werkzeugart
					{
						int tpY = y;
						int tDescX = cl + labelW + 12;
						int tW = cr - 8 - tDescX;
						List<String[]> kinds = de.loothub.client.quick.ToolProtection.KINDS;
						int tCols = 4;
						int tRows = (kinds.size() + tCols - 1) / tCols;
						int tColW = tW / tCols;
						buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
								tDescX, tpY + 24, Math.min(220, tW), 16, Component.empty(), (c.toolProtectionPercent - 1) / 99.0) {
							{
								updateMessage();
							}

							@Override
							protected void updateMessage() {
								setMessage(Component.translatable("loothub.settings.tool_protection.min", 1 + (int) Math.round(value * 99)));
							}

							@Override
							protected void applyValue() {
								c.toolProtectionPercent = 1 + (int) Math.round(value * 99);
							}
						}));
						for (int i = 0; i < kinds.size(); i++) {
							String id = kinds.get(i)[0];
							checks.add(new Check(tDescX + (i % tCols) * tColW, tpY + 46 + (i / tCols) * 14, tColW - 4, kinds.get(i)[1],
									() -> !c.toolProtectionOff.contains(id), v -> {
										c.toolProtectionOff.remove(id);
										if (!v) {
											c.toolProtectionOff.add(id);
										}
									}));
				}
						y = card(tpY, 50 + tRows * 14, "loothub.settings.tool_protection", "loothub.settings.tool_protection.tooltip",
								() -> c.toolProtection, v -> c.toolProtection = v);
					}
					y = card(y, 40, "loothub.settings.durability_numbers", "loothub.settings.durability_numbers.tooltip",
							() -> c.durabilityNumbers, v -> c.durabilityNumbers = v);
					y = card(y, 40, "loothub.settings.drop_protection", "loothub.settings.drop_protection.tooltip",
							() -> c.dropProtection, v -> c.dropProtection = v);
				}
				if (invSub(2)) {
					y = card(y, 40, "loothub.settings.rarity_frames", "loothub.settings.rarity_frames.tooltip",
							() -> c.rarityFrames, v -> c.rarityFrames = v);
					y = card(y, 40, "loothub.settings.compressor_icon", "loothub.settings.compressor_icon.tooltip",
							() -> c.compressorIcon, v -> c.compressorIcon = v);
					// Inventar über der Hotbar: Schalter, Farbe, Deckkraft
					{
						int ihY = y;
						int iDescX = cl + labelW + 12;
						int iW = Math.min(300, cr - 8 - iDescX);
						int half = (iW - 4) / 2;
						buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.inv_hud.color"),
										b -> minecraft.setScreen(new ColorPickerScreen(this,
												Component.translatable("loothub.settings.inv_hud.color"), c.invHudColor, rgb -> {
											c.invHudColor = rgb;
											c.save();
										})))
								.bounds(iDescX, ihY + 30, half, 16).build()));
						invHudSwatch = new int[]{iDescX + half - 12, ihY + 34};
						buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
								iDescX + half + 4, ihY + 30, half, 16, Component.empty(), c.invHudOpacity) {
							{
								updateMessage();
							}

							@Override
							protected void updateMessage() {
								setMessage(Component.translatable("loothub.settings.theme.opacity", (int) Math.round(value * 100)));
							}

							@Override
							protected void applyValue() {
								c.invHudOpacity = value;
							}
						}));
						buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
										b -> minecraft.setScreen(new de.loothub.client.quick.InventoryHudEditScreen(this)))
								.bounds(iDescX, ihY + 50, half, 16).build()));
						y = card(ihY, 72, "loothub.settings.inv_hud", "loothub.settings.inv_hud.tooltip",
								() -> c.invHudEnabled, v -> c.invHudEnabled = v);
					}
					y = card(y, 40, "loothub.settings.sell_highlight", "loothub.settings.sell_highlight.tooltip",
							() -> c.sellHighlight, v -> c.sellHighlight = v);
					y = card(y, 40, "loothub.settings.container_value", "loothub.settings.container_value.tooltip",
							() -> c.containerValue, v -> c.containerValue = v);
				}
				if (invSub(3)) {
					{
						int oy = y;
						int oDx = cl + labelW + 12;
						buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.offhand.edit",
										c.offhandItems == null ? 0 : c.offhandItems.size()),
								b -> minecraft.setScreen(new de.loothub.client.quick.OffhandFilterScreen(this)))
								.bounds(oDx, oy + 34, Math.min(160, cr - 8 - oDx), 16).build()));
						y = card(y, 56, "loothub.settings.offhand", "loothub.settings.offhand.tooltip",
								() -> c.offhandFilter, v -> c.offhandFilter = v);
					}
					y = card(y, 40, "loothub.settings.quick_panel", "loothub.settings.quick_panel.tooltip",
							() -> c.quickPanelEnabled, v -> c.quickPanelEnabled = v);
					// Knöpfe der Befehls-Leiste einzeln an/aus
					List<de.loothub.client.quick.QuickCommand> qcs = c.quickCommands == null ? List.of() : c.quickCommands;
					int qCols = 4;
					int qRows = Math.max(1, (qcs.size() + qCols - 1) / qCols);
					int qDescX = cl + labelW + 12;
					int qColW = (cr - 8 - qDescX) / qCols;
					for (int i = 0; i < qcs.size(); i++) {
						de.loothub.client.quick.QuickCommand q = qcs.get(i);
						String label = q.label == null || q.label.isBlank() ? "/" + q.command : q.label;
						checks.add(new Check(qDescX + (i % qCols) * qColW, y + 22 + (i / qCols) * 14, qColW - 4, label,
								() -> !q.hidden, v -> q.hidden = !v));
					}
					int qBtnY = y + 24 + qRows * 14;
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.quickedit.open"),
									b -> minecraft.setScreen(new de.loothub.client.quick.QuickButtonsScreen(this)))
							.bounds(qDescX, qBtnY, Math.min(200, cr - 8 - qDescX), 16).build()));
					y = cardNoToggle(y, 48 + qRows * 14, "loothub.settings.quick_buttons", "loothub.settings.quick_buttons.tooltip");
				}
			}
			case JOBS -> {
				y = section(y, "loothub.settings.section.jobs");
				// Job-Widget: Schalter + zwei Knöpfe unter der Beschreibung + Kästchen für jede Zeile
				int jobY = y;
				y = card(jobY, 64 + ((JobWidget.ROW_IDS.size() + 3) / 4) * 14, "loothub.settings.section.job_widget", "loothub.settings.job_widget.tooltip",
						() -> c.jobWidgetEnabled, v -> c.jobWidgetEnabled = v);
				int descX = cl + labelW + 12;
				int half = (cr - 8 - descX - 4) / 2;
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
								b -> minecraft.setScreen(new JobWidgetEditScreen(this)))
						.bounds(descX, jobY + 36, half, 16).build()));
				resetButton = add(Button.builder(Component.translatable("loothub.settings.job_session.reset"), b -> {
							JobTracker.reset();
							resetFeedbackUntil = System.currentTimeMillis() + 2000;
							b.setMessage(Component.translatable("loothub.settings.job_session.reset.done").withStyle(ChatFormatting.GREEN));
						})
						.bounds(descX + half + 4, jobY + 36, half, 16).build());
				buttons.add(resetButton);

				rowsLabelY = jobY + 60;
				List<String> ids = JobWidget.ROW_IDS;
				int perRow = 4;
				int colW = (cr - 8 - descX) / perRow;
				for (int i = 0; i < ids.size(); i++) {
					int col = i % perRow;
					int row = i / perRow;
					String id = ids.get(i);
					checks.add(new Check(descX + col * colW, jobY + 60 + row * 14, colW - 4, rowLabel(id),
							() -> c.jobRowVisible(id), v -> c.setJobRowVisible(id, v)));
				}

				// Jobschatz-Zähler: Stand korrigieren
				{
					int tY = y;
					int tx = cl + labelW + 12;
					net.minecraft.client.gui.components.EditBox treasureBox = new net.minecraft.client.gui.components.EditBox(
							font, tx, tY + 26, 100, 16, Component.translatable("loothub.settings.treasure"));
					treasureBox.setMaxLength(7);
					treasureBox.setValue(String.valueOf((long) c.treasureXp));
					treasureBox.setResponder(t -> {
						try {
							c.treasureXp = Math.max(0, Math.min(99_999, Long.parseLong(t.strip().replace(".", ""))));
						} catch (NumberFormatException ignored) {
							// nur Zahlen übernehmen
						}
					});
					buttons.add(addRenderableWidget(treasureBox));
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.treasure.reset"), b -> {
						c.treasureXp = 0;
						c.treasureChances = 0;
						c.treasuresFound = 0;
						c.save();
						rebuildWidgets();
					}).bounds(tx + 106, tY + 26, 110, 16).build()));
					y = cardNoToggle(tY, 50, "loothub.settings.treasure", "loothub.settings.treasure.tooltip");
				}
				y = card(y, 40, "loothub.settings.market_sales", "loothub.settings.market_sales.tooltip",
						() -> c.marketSales, v -> c.marketSales = v);
				y = section(y, "loothub.settings.section.fishing");
				{
					// Fang-Einblendung: an/aus, Seltenheiten als Kästchen, Test und Verschieben
					int fY = y;
					int fDescX = cl + labelW + 12;
					int fW = Math.min(240, cr - 8 - fDescX);
					int fHalf = (fW - 4) / 2;
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.streamer.alert.test"),
							b -> de.loothub.client.jobs.FishAlert.test()).bounds(fDescX, fY + 34, fHalf, 16).build()));
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.fish_alert.move"),
							b -> minecraft.setScreen(HudLayoutScreen.select(this, "loothub.hud.fish")))
							.bounds(fDescX + fHalf + 4, fY + 34, fHalf, 16).build()));
					List<String> rar = de.loothub.client.jobs.FishAlert.rarities();
					int fCols = 4;
					int fColW = (cr - 8 - fDescX) / fCols;
					for (int i = 0; i < rar.size(); i++) {
						String k = rar.get(i);
						String label = k.isEmpty() ? k : Character.toUpperCase(k.charAt(0)) + k.substring(1);
						checks.add(new Check(fDescX + (i % fCols) * fColW, fY + 56 + (i / fCols) * 14, fColW - 4, label,
								() -> de.loothub.client.jobs.FishAlert.shows(k),
								v -> de.loothub.client.jobs.FishAlert.setShows(k, v)));
					}
					int fRows = (rar.size() + fCols - 1) / fCols;
					y = card(fY, 60 + fRows * 14, "loothub.settings.fish_alert", "loothub.settings.fish_alert.tooltip",
							() -> c.fishAlert, v -> c.fishAlert = v);
				}
				{
					// OPSUCHT-Fangton stumm: gelernte Töne als Kästchen (angehakt = stumm)
					int mY = y;
					int mDescX = cl + labelW + 12;
					List<String> learned = de.loothub.client.jobs.FishSounds.learnedSounds();
					for (int i = 0; i < learned.size(); i++) {
						String id = learned.get(i);
						String label = id.substring(id.indexOf(':') + 1);
						checks.add(new Check(mDescX + (i % 2) * ((cr - 8 - mDescX) / 2), mY + 40 + (i / 2) * 14,
								(cr - 8 - mDescX) / 2 - 4, label,
								() -> !WidgetConfig.get().fishSoundAllowed.contains(id),
								v -> {
									WidgetConfig.get().fishSoundAllowed.remove(id);
									if (!v) {
										WidgetConfig.get().fishSoundAllowed.add(id);
									}
								}));
					}
					buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.fish_sound.reset"),
							b -> {
								de.loothub.client.jobs.FishSounds.reset();
								rebuildWidgets();
							}).bounds(cr - 8 - 90, mY + 22, 90, 14).build()));
					int mRows = learned.isEmpty() ? 1 : (learned.size() + 1) / 2;
					y = card(mY, 46 + mRows * 14, learned.isEmpty() ? "loothub.settings.fish_sound" : "loothub.settings.fish_sound",
							learned.isEmpty() ? "loothub.settings.fish_sound.learning" : "loothub.settings.fish_sound.tooltip",
							() -> c.fishSoundMute, v -> c.fishSoundMute = v);
				}
				y = card(y, 40, "loothub.settings.bait_warning", "loothub.settings.bait_warning.tooltip",
						() -> c.baitWarning, v -> c.baitWarning = v);
			}
			case SOUNDS -> {
				int descX = cl + labelW + 12;
				int sW = Math.min(320, cr - 8 - descX);
				int halfW = (sW - 4) / 2;
				de.loothub.client.Sounds.Group[] groups = de.loothub.client.Sounds.Group.values();
				int volY = y;
				for (int i = 0; i < groups.length; i++) {
					de.loothub.client.Sounds.Group grp = groups[i];
					int sx = descX + (i % 2) * (halfW + 4);
					int sy = volY + 30 + (i / 2) * 20;
					buttons.add(addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
							sx, sy, halfW, 16, Component.empty(), de.loothub.client.Sounds.volume(grp)) {
						{
							updateMessage();
						}

						@Override
						protected void updateMessage() {
							setMessage(Component.translatable(grp.key()).append(": " + (int) Math.round(value * 100) + " %"));
						}

						@Override
						protected void applyValue() {
							de.loothub.client.Sounds.setVolume(grp, (int) Math.round(value * 100));
						}
					}));
				}
				y = cardNoToggle(volY, 34 + ((groups.length + 1) / 2) * 20, "loothub.settings.sound_volume",
						"loothub.settings.sound_volume.tooltip");
				// Timer-Ton: Auswahl (Dropdown) + Anhören
				int tY = y;
				Button pick = addRenderableWidget(Button.builder(timerSoundLabel(), b -> {
					openPicker(b, de.loothub.client.Sounds.TIMER_SOUNDS, "loothub.sound.timer.", v -> {
						WidgetConfig.get().timerSoundType = v;
						WidgetConfig.get().save();
						b.setMessage(timerSoundLabel());
						de.loothub.client.Sounds.timer(v);
					});
				}).bounds(descX, tY + 30, halfW, 16).build());
				buttons.add(pick);
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.sound.listen"),
						b -> de.loothub.client.Sounds.timer(WidgetConfig.get().timerSoundType))
						.bounds(descX + halfW + 4, tY + 30, halfW, 16).build()));
				y = card(tY, 52, "loothub.settings.timer_sound", "loothub.settings.timer_sound.tooltip",
						() -> c.timerSound, v -> c.timerSound = v);
			}
			case EXTRAS -> {
				int descX = cl + labelW + 12;
				y = card(y, 70, "loothub.settings.music", "loothub.settings.music.tooltip",
						() -> c.musicEnabled, v -> {
							c.musicEnabled = v;
							de.loothub.client.music.YtMusic.ensure(v);
						});
				musicCardY = y - 70 - GAP;
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
								b -> minecraft.setScreen(new de.loothub.client.music.MusicEditScreen(this)))
						.bounds(cl + labelW + 12, musicCardY + 48, Math.min(160, cr - 20 - cl - labelW), 16).build()));
				// Pokémon Community Game (Twitch): Schalter, Kanal, Twitch-Name, Token-Datei
				int pcgY = y;
				y = card(pcgY, 120, "loothub.settings.pcg", "loothub.settings.pcg.tooltip",
						() -> c.pcgEnabled, v -> {
							c.pcgEnabled = v;
							de.loothub.client.pcg.TwitchChat.ensure(v, c.pcgChannel, c.pcgUser);
						});
				int fieldW = (cr - 8 - descX - 4) / 2;
				pcgLabelY = pcgY + 36;
				net.minecraft.client.gui.components.EditBox channelBox = new net.minecraft.client.gui.components.EditBox(
						font, descX, pcgY + 46, fieldW, 16, Component.translatable("loothub.settings.pcg.channel"));
				channelBox.setMaxLength(40);
				channelBox.setValue(c.pcgChannel == null ? "" : c.pcgChannel);
				channelBox.setResponder(t -> c.pcgChannel = t.strip());
				buttons.add(addRenderableWidget(channelBox));
				net.minecraft.client.gui.components.EditBox userBox = new net.minecraft.client.gui.components.EditBox(
						font, descX + fieldW + 4, pcgY + 46, fieldW, 16, Component.translatable("loothub.settings.pcg.user"));
				userBox.setMaxLength(40);
				userBox.setValue(c.pcgUser == null ? "" : c.pcgUser);
				userBox.setResponder(t -> c.pcgUser = t.strip());
				buttons.add(addRenderableWidget(userBox));
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.pcg.token"), b -> {
							java.nio.file.Path file = de.loothub.client.pcg.TwitchChat.tokenFile();
							try {
								if (!java.nio.file.Files.exists(file)) {
									java.nio.file.Files.writeString(file, "");
								}
							} catch (java.io.IOException ignored) {
								// Ordner wird trotzdem geöffnet
							}
							openFolder(file.getParent());
						})
						.bounds(descX, pcgY + 70, fieldW, 16).build()));
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.pcg.connect"), b -> {
							WidgetConfig.get().save();
							de.loothub.client.pcg.TwitchChat.stop();
							de.loothub.client.pcg.TwitchChat.ensure(c.pcgEnabled, c.pcgChannel, c.pcgUser);
						})
						.bounds(descX + fieldW + 4, pcgY + 70, fieldW, 16).build()));
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.pcg.move"),
								b -> minecraft.setScreen(new de.loothub.client.pcg.PcgOverlayEditScreen(this)))
						.bounds(descX, pcgY + 92, fieldW, 16).build()));
				pcgFieldX = descX;
				y = card(y, 40, "loothub.settings.pcg_sound", "loothub.settings.pcg_sound.tooltip",
						() -> c.pcgSound, v -> c.pcgSound = v);
				pcgFieldW = fieldW;
				y = card(y, 40, "loothub.settings.pokedex_page", "loothub.settings.pokedex_page.tooltip",
						() -> c.pokedexPage, v -> c.pokedexPage = v);
				y = card(y, 40, "loothub.settings.streamer", "loothub.settings.streamer.tooltip",
						() -> c.streamerMode, v -> {
							c.streamerMode = v;
							de.loothub.client.streamer.Streamer.apply();
						});

			}
				}

		if (searching()) {
			y -= hideUnmatched();
		}
		// Scrollen: Inhalt höher als der sichtbare Bereich?
		contentH = y - GAP - contentStart;
		int max = maxScroll();
		if (scroll > max) {
			scroll = max;
			needsRebuild = true;
		}
		for (net.minecraft.client.gui.components.AbstractWidget b : buttons) {
			b.visible = b.getY() >= top && b.getY() + b.getHeight() <= cb;
		}
		if (cards.isEmpty() && searching()) {
			versionY = -10000;
		}
	}

	/** Ordner im Datei-Explorer öffnen (Windows, macOS, Linux). */
	private static void openFolder(java.nio.file.Path dir) {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		String path = dir.toAbsolutePath().toString();
		String[] cmd = os.contains("win") ? new String[]{"explorer", path}
				: os.contains("mac") ? new String[]{"open", path}
				: new String[]{"xdg-open", path};
		try {
			new ProcessBuilder(cmd).start();
		} catch (java.io.IOException e) {
			de.loothub.Loothub.LOGGER.warn("Ordner konnte nicht geöffnet werden: {}", path, e);
		}
	}

	private int maxScroll() {
		return Math.max(0, contentH - (cb - top));
	}

	private static String rowLabel(String id) {
		String s = Component.translatable("loothub.job.row." + id).getString();
		return s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
	}

	/** Gehört dieser Teil auf die gerade gewählte Unterseite? (Beim Suchen und für den Suchindex: alle.) */
	private boolean invSub(int i) {
		return indexing || searching() || invSub == i;
	}

	private static boolean searching() {
		return !query.isBlank();
	}

	private static boolean matches(String titleKey, String descKey, Tab t) {
		String hay = (Component.translatable(titleKey).getString() + " " + Component.translatable(descKey).getString()
				+ " " + Component.translatable(t.key).getString()).toLowerCase(java.util.Locale.ROOT);
		for (String word : query.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
			if (!hay.contains(word)) {
				return false;
			}
		}
		return true;
	}

	private int matchCount(Tab t) {
		int n = 0;
		if (index != null) {
			for (Result r : index) {
				if (r.tab() == t && matches(r.titleKey(), r.descKey(), t)) {
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * Suche: Karten ohne Treffer samt ihrer Knöpfe ausblenden und den Rest nach oben rücken.
	 * @return wie viele Pixel weggefallen sind
	 */
	private int hideUnmatched() {
		// Bereiche, die wegfallen: nicht passende Karten und der Versionsblock
		List<int[]> gone = new ArrayList<>();
		if (tab == Tab.GENERAL) {
			gone.add(new int[]{versionY, versionY + 50});
		}
		List<Card> keep = new ArrayList<>();
		for (Card cd : cards) {
			if (matches(cd.titleKey(), cd.descKey(), tab)) {
				keep.add(cd);
			} else {
				gone.add(new int[]{cd.y(), cd.y() + cd.h() + GAP});
			}
		}
		java.util.function.IntUnaryOperator shift = yy -> {
			int d = 0;
			for (int[] g : gone) {
				if (yy >= g[1]) {
					d += g[1] - g[0];
				} else if (yy >= g[0]) {
					return -100000; // liegt in einem weggefallenen Bereich
				}
			}
			return yy - d;
		};
		cards.clear();
		for (Card cd : keep) {
			Toggle t = cd.toggle();
			int ny = shift.applyAsInt(cd.y());
			cards.add(new Card(ny, cd.h(), cd.titleKey(), cd.descKey(),
					t == null ? null : new Toggle(t.x(), t.y() - cd.y() + ny, t.get(), t.set())));
		}
		List<Check> ch = new ArrayList<>(checks);
		checks.clear();
		for (Check c : ch) {
			int ny = shift.applyAsInt(c.y());
			if (ny > -50000) {
				checks.add(new Check(c.x(), ny, c.w(), c.label(), c.get(), c.set()));
			}
		}
		for (net.minecraft.client.gui.components.AbstractWidget b : buttons) {
			b.setY(shift.applyAsInt(b.getY()));
		}
		for (int i = 0; i < colorSwatches.length; i++) {
			if (colorSwatches[i] != null) {
				colorSwatches[i][1] = shift.applyAsInt(colorSwatches[i][1]);
			}
		}
		if (invHudSwatch != null) {
			invHudSwatch[1] = shift.applyAsInt(invHudSwatch[1]);
		}
		rowsLabelY = shift.applyAsInt(rowsLabelY);
		musicCardY = shift.applyAsInt(musicCardY);
		pcgLabelY = shift.applyAsInt(pcgLabelY);
		versionY = -100000;
		int total = 0;
		for (int[] g : gone) {
			total += g[1] - g[0];
		}
		return total;
	}

	private Button add(Button b) {
		buttons.add(addRenderableWidget(b));
		return b;
	}

	/** Karte ohne eigenen Schalter (Schalter liegen als Kästchen in der Karte). */
	private int cardNoToggle(int y, int h, String titleKey, String descKey) {
		cards.add(new Card(y, h, titleKey, descKey, null));
		return y + h + GAP;
	}

	// ------------------------------------------------------------------ Auswahlliste (Dropdown)

	private static final int PICK_ROW = 14;
	private Button pickButton;
	private String[] pickOptions;
	private String pickKeyPrefix;
	private Consumer<String> pickSet;

	private static Component timerSoundLabel() {
		return Component.translatable("loothub.settings.timer_sound.pick")
				.append(Component.translatable("loothub.sound.timer." + WidgetConfig.get().timerSoundType)).append(" ▾");
	}

	private void openPicker(Button b, String[] options, String keyPrefix, Consumer<String> set) {
		if (pickButton == b) {
			pickButton = null;
			return;
		}
		pickButton = b;
		pickOptions = options;
		pickKeyPrefix = keyPrefix;
		pickSet = set;
	}

	private void renderPicker(GuiGraphics g, int mx, int my) {
		if (pickButton == null || !pickButton.visible) {
			return;
		}
		g.nextStratum();
		int x = pickButton.getX(), y = pickButton.getY() + pickButton.getHeight(), w = pickButton.getWidth();
		g.fill(x, y, x + w, y + pickOptions.length * PICK_ROW + 2, 0xF0120A0C);
		g.fill(x, y, x + w, y + 1, 0xFFE23B3B);
		for (int i = 0; i < pickOptions.length; i++) {
			int ry = y + 1 + i * PICK_ROW;
			boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + PICK_ROW;
			if (hover) {
				g.fill(x + 1, ry, x + w - 1, ry + PICK_ROW, 0x30FFFFFF);
			}
			g.drawString(font, Component.translatable(pickKeyPrefix + pickOptions[i]), x + 6, ry + 3, hover ? 0xFFFFFFFF : TEXT, false);
		}
	}

	/** Klick in die offene Auswahlliste: Wahl übernehmen. true = Klick verbraucht. */
	private boolean clickPicker(double mx, double my) {
		if (pickButton == null) {
			return false;
		}
		int x = pickButton.getX(), y = pickButton.getY() + pickButton.getHeight(), w = pickButton.getWidth();
		Button b = pickButton;
		pickButton = null;
		if (mx >= x && mx < x + w && my >= y + 1 && my < y + 1 + pickOptions.length * PICK_ROW) {
			pickSet.accept(pickOptions[(int) ((my - y - 1) / PICK_ROW)]);
			return true;
		}
		return mx >= b.getX() && mx < b.getX() + b.getWidth() && my >= b.getY() && my < b.getY() + b.getHeight();
	}

	/** Überschrift über einer Gruppe von Karten. */
	private int section(int y, String key) {
		if (searching()) {
			return y;
		}
		sections.add(new Object[]{y, key});
		return y + 16;
	}

	private int card(int y, int h, String titleKey, String descKey, BooleanSupplier get, Consumer<Boolean> set) {
		int tx = cl + 8 + (labelW - Toggle.W) / 2;
		Toggle t = new Toggle(tx, y + 20, get, set);
		cards.add(new Card(y, h, titleKey, descKey, t));
		return y + h + GAP;
	}

	@Override
	public void tick() {
		super.tick();
		if (needsRebuild) {
			needsRebuild = false;
			rebuildWidgets();
			return;
		}
		if (resetFeedbackUntil > 0 && System.currentTimeMillis() > resetFeedbackUntil) {
			resetFeedbackUntil = 0;
			resetButton.setMessage(Component.translatable("loothub.settings.job_session.reset"));
		}
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.renderBackground(g, mouseX, mouseY, delta);
		// Karten-Hintergründe unter den Knöpfen
		g.enableScissor(cl, top, cr, cb);
		if (tab == Tab.GENERAL) {
			box(g, cl, versionY, cw, 44);
		}
		for (Card card : cards) {
			box(g, cl, card.y(), cw, card.h());
			g.fill(cl + labelW + 6, card.y() + 5, cl + labelW + 7, card.y() + card.h() - 5, de.loothub.client.Theme.bg(CARD_EDGE));
		}
		g.disableScissor();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.enableScissor(cl, top, cr, cb);
		if (tab == Tab.GENERAL) {
			renderVersion(g);
		}
		for (Object[] sec : sections) {
			int sy = (int) sec[0];
			Component t = Component.translatable((String) sec[1]);
			g.drawString(font, t, cl + 2, sy + 4, 0xFFE2A13B, false);
			g.fill(cl + 6 + font.width(t), sy + 8, cr, sy + 9, 0x40E2A13B);
		}
		if (searching() && cards.isEmpty()) {
			Component none = Component.translatable("loothub.settings.search.none");
			g.drawString(font, none, cl + (cw - font.width(none)) / 2, top + 20, FAINT, false);
		}
		for (Card card : cards) {
			Component title = Component.translatable(card.titleKey());
			// Lange Titel umbrechen (höchstens zwei Zeilen), damit nichts in die Beschreibung ragt
			List<FormattedCharSequence> titleLines = font.split(title, labelW - 6);
			if (titleLines.size() <= 1) {
				g.drawString(font, title, cl + 8 + (labelW - font.width(title)) / 2, card.y() + 6, TEXT, false);
			} else {
				for (int li = 0; li < Math.min(2, titleLines.size()); li++) {
					FormattedCharSequence line = titleLines.get(li);
					g.drawString(font, line, cl + 8 + (labelW - font.width(line)) / 2, card.y() + 2 + li * 9, TEXT, false);
				}
			}
			if (card.toggle() != null) {
				drawToggle(g, card.toggle(), mouseX, mouseY);
			}

			int descX = cl + labelW + 12;
			int y = card.y() + 6;
			for (FormattedCharSequence line : font.split(Component.translatable(card.descKey()), cr - 8 - descX)) {
				g.drawString(font, line, descX, y, MUTED, false);
				y += 10;
			}
		}

		// Zeilen des Job-Widgets
		if (tab == Tab.JOBS) {
			Component rowsTitle = Component.translatable("loothub.settings.job_widget.rows");
			g.drawString(font, rowsTitle, cl + 8 + (labelW - font.width(rowsTitle)) / 2, rowsLabelY + 7, TEXT, false);
		}
		for (Check ch : checks) {
			boolean on = ch.get().getAsBoolean();
			boolean hover = ch.contains(mouseX, mouseY) && mouseY >= top && mouseY < cb;
			g.fill(ch.x(), ch.y(), ch.x() + 9, ch.y() + 9, hover ? 0xFFFFFFFF : 0xFF000000);
			g.fill(ch.x() + 1, ch.y() + 1, ch.x() + 8, ch.y() + 8, on ? RED : de.loothub.client.Theme.bg(TRACK_OFF));
			if (on) {
				g.fill(ch.x() + 3, ch.y() + 3, ch.x() + 6, ch.y() + 6, 0xFFE8E0E0);
			}
			g.drawString(font, ch.label(), ch.x() + 12, ch.y() + 1, on ? TEXT : FAINT, false);
		}
		// Farbfelder auf den Farbknöpfen
		int[] cols = {Theme.inner(), Theme.outer(), Theme.pulse()};
		for (int i = 0; i < 3; i++) {
			if (colorSwatches[i] != null) {
				int sx = colorSwatches[i][0];
				int sy = colorSwatches[i][1];
				g.fill(sx - 1, sy - 1, sx + 9, sy + 9, 0xFF000000);
				g.fill(sx, sy, sx + 8, sy + 8, 0xFF000000 | cols[i]);
			}
		}

		if (invHudSwatch != null) {
			g.fill(invHudSwatch[0] - 1, invHudSwatch[1] - 1, invHudSwatch[0] + 9, invHudSwatch[1] + 9, 0xFF000000);
			g.fill(invHudSwatch[0], invHudSwatch[1], invHudSwatch[0] + 8, invHudSwatch[1] + 8, 0xFF000000 | WidgetConfig.get().invHudColor);
		}

		if (tab == Tab.EXTRAS) {
		// YouTube Music: Status unter dem Schalter
		Component musicStatus;
		if (de.loothub.client.music.YtMusic.song() != null || de.loothub.client.music.YtMusic.status() == de.loothub.client.music.YtMusic.Status.OFF) {
			musicStatus = Component.translatable("loothub.settings.music.status."
					+ de.loothub.client.music.YtMusic.status().name().toLowerCase(java.util.Locale.ROOT));
		} else if (de.loothub.client.music.WindowsMedia.song() != null) {
			musicStatus = Component.translatable("loothub.settings.music.status.windows");
		} else if (de.loothub.client.music.WindowsMedia.active()) {
			musicStatus = Component.translatable("loothub.settings.music.status.windows_idle");
		} else {
			musicStatus = Component.translatable("loothub.settings.music.status."
					+ de.loothub.client.music.YtMusic.status().name().toLowerCase(java.util.Locale.ROOT));
		}
		g.drawString(font, musicStatus, cl + 8 + (labelW - font.width(musicStatus)) / 2, musicCardY + 35, FAINT, false);

		// Pokémon Community Game: Feldbeschriftungen und Status
		g.drawString(font, Component.translatable("loothub.settings.pcg.channel"), pcgFieldX, pcgLabelY, MUTED, false);
		g.drawString(font, Component.translatable("loothub.settings.pcg.user"), pcgFieldX + pcgFieldW + 4, pcgLabelY, MUTED, false);
		Component pcgStatus = Component.translatable("loothub.settings.pcg.status."
				+ de.loothub.client.pcg.TwitchChat.status().name().toLowerCase(java.util.Locale.ROOT));
		g.drawString(font, pcgStatus, cl + 8 + (labelW - font.width(pcgStatus)) / 2, pcgLabelY + 14, FAINT, false);
		}
		g.disableScissor();

		renderPicker(g, mouseX, mouseY);

		// Scrollleiste
		int max = maxScroll();
		if (max > 0) {
			int trackH = cb - top;
			int barH = Math.max(16, trackH * trackH / (trackH + max));
			int barY = top + (trackH - barH) * scroll / max;
			g.fill(cr - 3, top, cr - 1, cb, 0x40FFFFFF);
			g.fill(cr - 3, barY, cr - 1, barY + barH, RED);
		}
	}

	private void renderVersion(GuiGraphics g) {
		Component name = Component.literal("Loothub+ ").withStyle(ChatFormatting.BOLD)
				.append(Component.literal(Updater.currentVersion()).withStyle(ChatFormatting.BOLD))
				.withStyle(s -> s.withColor(RED & 0xFFFFFF));
		g.drawString(font, name, cl + 10, versionY + 8, TEXT, true);
		g.drawString(font, Component.translatable("loothub.settings.version.sub"), cl + 10, versionY + 22, FAINT, false);

		// Status-Kästchen oben rechts
		Component status;
		int color;
		switch (Updater.state()) {
			case CHECKING -> {
				status = Component.translatable("loothub.settings.status.checking");
				color = MUTED;
			}
			case UP_TO_DATE -> {
				status = Component.translatable("loothub.settings.status.up_to_date");
				color = GREEN;
			}
			case AVAILABLE, DOWNLOADING -> {
				status = Component.translatable("loothub.settings.status.available", Updater.latestVersion());
				color = GOLD;
			}
			case INSTALLED -> {
				status = Component.translatable("loothub.update.restart.tooltip");
				color = GREEN;
			}
			case ERROR -> {
				status = Updater.errorReason();
				color = RED;
			}
			default -> {
				status = Component.translatable("loothub.settings.status.idle");
				color = MUTED;
			}
		}
		int sw = font.width(status) + 10;
		int sx = cr - 8 - Math.max(sw, 100);
		int sy = versionY + 5;
		int w = Math.max(sw, 100);
		g.fill(sx, sy, sx + w, sy + 15, color);
		g.fill(sx + 1, sy + 1, sx + w - 1, sy + 14, 0xFF0F0A0B);
		g.drawString(font, status, sx + (w - font.width(status)) / 2, sy + 4, color, false);
	}

	private void drawToggle(GuiGraphics g, Toggle t, int mx, int my) {
		boolean on = t.get().getAsBoolean();
		boolean hover = t.contains(mx, my);
		int track = on ? RED : TRACK_OFF;
		g.fill(t.x(), t.y(), t.x() + Toggle.W, t.y() + Toggle.H, hover ? 0xFFFFFFFF : 0xFF000000);
		g.fill(t.x() + 1, t.y() + 1, t.x() + Toggle.W - 1, t.y() + Toggle.H - 1, track);
		int knobX = on ? t.x() + Toggle.W - 11 : t.x() + 1;
		g.fill(knobX, t.y() + 1, knobX + 10, t.y() + Toggle.H - 1, 0xFFE8E0E0);
		Component state = Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off");
		g.drawString(font, state, t.x() + Toggle.W + 5, t.y() + 2, on ? TEXT : FAINT, false);
	}

	private static void box(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, de.loothub.client.Theme.bg(CARD));
		g.fill(x, y + h - 1, x + w, y + h, de.loothub.client.Theme.bg(CARD_EDGE));
		g.fill(x, y, x + w, y + 1, 0xFF2A171A);
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickPicker(event.x(), event.y())) {
			return true;
		}
		searchFocused = searchBox != null && searchBox.isMouseOver(event.x(), event.y());
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && event.y() >= top && event.y() < cb) {
			for (Check ch : checks) {
				if (ch.contains(event.x(), event.y())) {
					ch.set().accept(!ch.get().getAsBoolean());
					WidgetConfig.get().save();
					return true;
				}
			}
			for (Card card : cards) {
				Toggle t = card.toggle();
				if (t != null && t.contains(event.x(), event.y())) {
					t.set().accept(!t.get().getAsBoolean());
					WidgetConfig.get().save();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (maxScroll() > 0 && mx >= cl && mx < cr && my >= top && my < cb) {
			int next = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(vertical * 20)));
			if (next != scroll) {
				scroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		WidgetConfig c = WidgetConfig.get();
		de.loothub.client.pcg.TwitchChat.ensure(c.pcgEnabled, c.pcgChannel, c.pcgUser);
		super.onClose();
	}

	private static Component lookLabel(String part, boolean on) {
		return Component.translatable("loothub.settings.look_info." + part,
				Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off")
						.withStyle(on ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY));
	}
}
