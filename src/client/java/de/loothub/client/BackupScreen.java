package de.loothub.client;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Datensicherung & Umzug: alle Loothub-Daten als Zip exportieren (z. B. für einen neuen PC) und eine exportierte Zip
 * oder eine der täglichen Sicherungen wieder einspielen. Eingespielt wird beim nächsten Spielstart, damit nichts, was
 * gerade geladen ist, die Daten gleich wieder überschreibt. Zugangsdaten (Twitch, Musik) sind nie dabei.
 */
public class BackupScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
	private static final int ROW = 22;
	private final Screen parent;
	private List<DataBackup.Source> sources = List.of();
	private int scroll;
	private Component status = Component.empty();

	public BackupScreen(Screen parent) {
		super(Component.translatable("loothub.backup.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		sources = DataBackup.sources();
		int w = Math.min(420, width - 20), x = (width - w) / 2;
		int bw = (w - 8) / 3;
		addRenderableWidget(Button.builder(Component.translatable("loothub.backup.export"), b -> export())
				.bounds(x, 40, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.backup.open_folder"), b -> openFolder())
				.bounds(x + bw + 4, 40, bw, 20).build());
		if (DataBackup.restorePending()) {
			addRenderableWidget(Button.builder(Component.translatable("loothub.backup.cancel_restore"), b -> {
				DataBackup.cancelRestore();
				status = Component.translatable("loothub.backup.cancelled");
				rebuildWidgets();
			}).bounds(x + 2 * (bw + 4), 40, bw, 20).build());
		}
		int top = listTop(), rows = visibleRows();
		scroll = Math.max(0, Math.min(scroll, sources.size() - rows));
		for (int i = 0; i < rows && scroll + i < sources.size(); i++) {
			DataBackup.Source s = sources.get(scroll + i);
			addRenderableWidget(Button.builder(Component.translatable("loothub.backup.restore"), b -> askRestore(s))
					.bounds(x + w - 90, top + i * ROW, 90, 18).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 50, height - 26, 100, 20).build());
	}

	private int listTop() {
		return 96;
	}

	private int visibleRows() {
		return Math.max(1, (height - 34 - listTop()) / ROW);
	}

	private void export() {
		try {
			Path zip = DataBackup.exportZip();
			status = Component.translatable("loothub.backup.exported", zip.getFileName().toString()).withStyle(ChatFormatting.GREEN);
			openFolder();
		} catch (Exception e) {
			status = Component.translatable("loothub.backup.export_failed").withStyle(ChatFormatting.RED);
		}
		rebuildWidgets();
	}

	private void openFolder() {
		try {
			java.nio.file.Files.createDirectories(DataBackup.exportDir());
			net.minecraft.util.Util.getPlatform().openPath(DataBackup.exportDir());
		} catch (Exception e) {
			minecraft.keyboardHandler.setClipboard(DataBackup.exportDir().toString());
			status = Component.translatable("loothub.backup.path_copied");
		}
	}

	private void askRestore(DataBackup.Source s) {
		minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.backup.restore"),
				Component.translatable("loothub.backup.restore.confirm", s.label()), () -> {
					try {
						DataBackup.scheduleRestore(s.path());
						status = Component.translatable("loothub.backup.scheduled").withStyle(ChatFormatting.GOLD);
					} catch (Exception e) {
						status = Component.translatable("loothub.backup.export_failed").withStyle(ChatFormatting.RED);
					}
				}));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		int max = Math.max(0, sources.size() - visibleRows());
		int ns = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
		if (ns != scroll) {
			scroll = ns;
			rebuildWidgets();
		}
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		int w = Math.min(420, width - 20), x = (width - w) / 2;
		g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
		g.drawCenteredString(font, Component.translatable("loothub.backup.info"), width / 2, 27, 0xFFA0A0A0);
		if (DataBackup.restorePending()) {
			g.drawCenteredString(font, Component.translatable("loothub.backup.pending").withStyle(ChatFormatting.GOLD), width / 2, 66, 0xFFFFFFFF);
		} else {
			g.drawCenteredString(font, status, width / 2, 66, 0xFFFFFFFF);
		}
		g.drawString(font, Component.translatable("loothub.backup.list").withStyle(ChatFormatting.BOLD), x, listTop() - 12, 0xFFFFFFFF);
		if (sources.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.backup.none"), x, listTop() + 4, 0xFFA0A0A0);
		}
		int rows = visibleRows();
		for (int i = 0; i < rows && scroll + i < sources.size(); i++) {
			DataBackup.Source s = sources.get(scroll + i);
			int y = listTop() + i * ROW;
			Component kind = Component.translatable(s.zip() ? "loothub.backup.kind_zip" : "loothub.backup.kind_daily")
					.withStyle(s.zip() ? ChatFormatting.AQUA : ChatFormatting.GRAY);
			g.drawString(font, kind, x, y + 5, 0xFFFFFFFF);
			String when = s.modified() > 0 ? DATE.format(Instant.ofEpochMilli(s.modified())) : s.label();
			g.drawString(font, when, x + 70, y + 5, 0xFFFFFFFF);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
