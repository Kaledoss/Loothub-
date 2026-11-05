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
		INVENTORY("loothub.settings.tab.inventory"),
		JOBS("loothub.settings.tab.jobs"),
		EXTRAS("loothub.settings.tab.extras");

		final String key;

		Tab(String key) {
			this.key = key;
		}
	}

	private static final int TAB_H = 22;
	private static Tab tab = Tab.GENERAL;
	/** Oberkante des scrollbaren Bereichs (unter den Reitern). */
	private int top;

	public SettingsScreen() {
		super(Page.SETTINGS);
	}

	@Override
	protected void initPage() {
		cards.clear();
		checks.clear();
		buttons.clear();
		WidgetConfig c = WidgetConfig.get();
		labelW = Math.min(130, cw / 3);
		colorSwatches[0] = colorSwatches[1] = colorSwatches[2] = null;
		invHudSwatch = null;

		// Reiter oben (fest, scrollt nicht mit)
		int tabX = cl;
		int tabW = (cw - (Tab.values().length - 1) * 3) / Tab.values().length;
		for (Tab t : Tab.values()) {
			Button tb = Button.builder(Component.translatable(t.key), b -> {
				tab = t;
				scroll = 0;
				rebuildWidgets();
			}).bounds(tabX, ct, tabW, 16).build();
			tb.active = t != tab;
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
				y = card(y, 40, "loothub.settings.tooltip_prices", "loothub.settings.tooltip_prices.tooltip",
						() -> c.tooltipPrices, v -> c.tooltipPrices = v);
				y = card(y, 40, "loothub.settings.stats", "loothub.settings.stats.tooltip",
						() -> c.statsEnabled, v -> c.statsEnabled = v);
				y = card(y, 40, "loothub.settings.amount_shortcut", "loothub.settings.amount_shortcut.tooltip",
						() -> c.amountShortcut, v -> c.amountShortcut = v);
				y = card(y, 40, "loothub.settings.rename_window", "loothub.settings.rename_window.tooltip",
						() -> c.renameWindow, v -> c.renameWindow = v);
				y = card(y, 40, "loothub.settings.itemdex", "loothub.settings.itemdex.tooltip",
						() -> c.itemdexEnabled, v -> c.itemdexEnabled = v);
			}
			case LOOK -> {
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
				y = card(y, 40, "loothub.settings.inventory_design", "loothub.settings.inventory_design.tooltip",
						() -> c.inventoryDesign, v -> {
							c.inventoryDesign = v;
							Theme.setPackActive(v);
						});
				y = card(y, 40, "loothub.settings.pulse", "loothub.settings.pulse.tooltip",
						() -> c.pulseBorders, v -> c.pulseBorders = v);
				y = card(y, 40, "loothub.settings.hide_head", "loothub.settings.hide_head.tooltip",
						() -> c.hideOwnHeadItem, v -> c.hideOwnHeadItem = v);
				y = card(y, 40, "loothub.settings.hide_offhand", "loothub.settings.hide_offhand.tooltip",
						() -> c.hideOffhandBackpack, v -> c.hideOffhandBackpack = v);
			}
			case INVENTORY -> {
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
			}
			case JOBS -> {
				// Job-Widget: Schalter + zwei Knöpfe unter der Beschreibung + Kästchen für jede Zeile
				int jobY = y;
				y = card(jobY, 64 + ((JobWidget.ROW_IDS.size() + 3) / 4) * 14, "loothub.settings.section.job_widget", "loothub.settings.job_widget.tooltip",
						() -> c.jobWidgetEnabled, v -> c.jobWidgetEnabled = v);
				int descX = cl + labelW + 12;
				int half = (cr - 8 - descX - 4) / 2;
				buttons.add(addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_widget.move"),
								b -> minecraft.setScreen(new JobWidgetEditScreen(this)))
						.bounds(descX, jobY + 36, half, 16).build()));
				resetButton = addRenderableWidget(Button.builder(Component.translatable("loothub.settings.job_session.reset"), b -> {
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

			}
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

	/** Karte ohne eigenen Schalter (Schalter liegen als Kästchen in der Karte). */
	private int cardNoToggle(int y, int h, String titleKey, String descKey) {
		cards.add(new Card(y, h, titleKey, descKey, null));
		return y + h + GAP;
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
		for (Card card : cards) {
			Component title = Component.translatable(card.titleKey());
			g.drawString(font, title, cl + 8 + (labelW - font.width(title)) / 2, card.y() + 6, TEXT, false);
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
}
