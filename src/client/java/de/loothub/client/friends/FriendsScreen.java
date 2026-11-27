package de.loothub.client.friends;

import java.util.ArrayList;
import java.util.List;

import de.loothub.client.ConfirmScreen;
import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Seite „Freunde“: Freunde per Name hinzufügen (der andere muss zustimmen), Anfragen annehmen/ablehnen, sehen wer
 * gerade online ist und – wenn der Freund es teilt – was er macht. Alles ist aus, bis man es selbst einschaltet.
 */
public class FriendsScreen extends LoothubScreen {
	private static final int ROW_H = 22;
	private static final int GREEN = 0xFF55E06A;

	private EditBox nameBox;
	private Component status = Component.empty();
	private int scroll;
	private int listTop;

	/** Eine Zeile der Liste: Anfrage, ausstehende eigene Anfrage oder Freund. */
	private record Row(int kind, Friends.Person person, Friends.Friend friend) {
	}

	private List<Row> rows = List.of();

	public FriendsScreen() {
		super(Page.FRIENDS);
	}

	@Override
	protected void initPage() {
		WidgetConfig c = WidgetConfig.get();
		int y = ct;
		addRenderableWidget(Button.builder(onOff("loothub.friends.on", c.friendsOn), b -> {
			c.friendsOn = !c.friendsOn;
			c.save();
			if (c.friendsOn) {
				reload();
			}
			rebuildWidgets();
		}).bounds(cl, y, 130, 16).tooltip(Tooltip.create(Component.translatable("loothub.friends.on.tip"))).build());
		addRenderableWidget(Button.builder(onOff("loothub.friends.share", c.friendsShare), b -> {
			c.friendsShare = !c.friendsShare;
			c.save();
			b.setMessage(onOff("loothub.friends.share", c.friendsShare));
			reload();
		}).bounds(cl + 134, y, 150, 16).tooltip(Tooltip.create(Component.translatable("loothub.friends.share.tip"))).build())
				.active = c.friendsOn;
		addRenderableWidget(Button.builder(onOff("loothub.friends.notify", c.friendsNotify), b -> {
			c.friendsNotify = !c.friendsNotify;
			c.save();
			b.setMessage(onOff("loothub.friends.notify", c.friendsNotify));
		}).bounds(cl + 288, y, Math.max(80, Math.min(140, cr - cl - 288)), 16).build()).active = c.friendsOn;

		listTop = y + 46;
		if (!c.friendsOn) {
			rows = List.of();
			return;
		}
		y += 22;
		String old = nameBox != null ? nameBox.getValue() : "";
		nameBox = addRenderableWidget(new EditBox(font, cl, y, 140, 16, Component.translatable("loothub.friends.name")));
		nameBox.setMaxLength(16);
		nameBox.setHint(Component.translatable("loothub.friends.name"));
		nameBox.setValue(old);
		addRenderableWidget(Button.builder(Component.translatable("loothub.friends.add"), b -> addFriend()).bounds(cl + 144, y, 90, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.friends.reload"), b -> reload()).bounds(cl + 238, y, 80, 16).build());

		// Zeilen: erst eingehende Anfragen, dann Freunde (online zuerst), dann eigene offene Anfragen
		List<Row> r = new ArrayList<>();
		for (Friends.Person p : Friends.incoming()) {
			r.add(new Row(0, p, null));
		}
		for (Friends.Friend f : Friends.friends()) {
			r.add(new Row(1, null, f));
		}
		for (Friends.Person p : Friends.outgoing()) {
			r.add(new Row(2, p, null));
		}
		rows = r;
		int visible = visibleRows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
		for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
			Row row = rows.get(i + scroll);
			int ry = listTop + i * ROW_H + 2;
			switch (row.kind()) {
				case 0 -> {
					addRenderableWidget(Button.builder(Component.translatable("loothub.friends.accept").withStyle(ChatFormatting.GREEN),
							b -> act("friend_accept", row.person().uuid())).bounds(cr - 154, ry, 74, 16).build());
					addRenderableWidget(Button.builder(Component.translatable("loothub.friends.decline"),
							b -> act("friend_decline", row.person().uuid())).bounds(cr - 76, ry, 74, 16).build());
				}
				case 1 -> {
					if (row.friend().online()) {
						addRenderableWidget(Button.builder(Component.translatable("loothub.friends.msg"), b -> {
							minecraft.setScreen(null);
							minecraft.execute(() -> minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen(
									"/msg " + row.friend().name() + " ", false)));
						}).bounds(cr - 154, ry, 74, 16).build());
					}
					addRenderableWidget(Button.builder(Component.translatable("loothub.friends.remove"),
							b -> minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.friends.remove"),
									Component.translatable("loothub.friends.remove.confirm", row.friend().name()),
									() -> act("friend_remove", row.friend().uuid())))).bounds(cr - 76, ry, 74, 16).build());
				}
				default -> addRenderableWidget(Button.builder(Component.translatable("loothub.friends.withdraw"),
						b -> act("friend_remove", row.person().uuid())).bounds(cr - 76, ry, 74, 16).build());
			}
		}
		if (!Friends.loaded()) {
			reload();
		}
	}

	private int visibleRows() {
		return Math.max(1, (cb - listTop) / ROW_H);
	}

	private static Component onOff(String key, boolean on) {
		return Component.translatable(key).append(": ").append(Component.translatable(on ? "options.on" : "options.off"));
	}

	private void addFriend() {
		String n = nameBox.getValue().trim();
		if (!n.matches("[A-Za-z0-9_]{1,16}")) {
			status = Component.translatable("loothub.friends.err.name").withStyle(ChatFormatting.RED);
			return;
		}
		status = Component.translatable("loothub.friends.wait");
		Friends.add(n, err -> {
			if (err == null || err.startsWith("msg_")) {
				nameBox.setValue("");
			}
			result(err, n);
		});
	}

	private void act(String op, String uuid) {
		status = Component.translatable("loothub.friends.wait");
		Friends.act(op, uuid, err -> result(err, ""));
	}

	private void reload() {
		Friends.refresh(err -> {
			if (err != null && !err.startsWith("msg_")) {
				result(err, "");
			} else if (minecraft.screen == this) {
				rebuildWidgets();
			}
		});
	}

	private void result(String err, String name) {
		if (err == null) {
			status = Component.empty();
		} else if (err.startsWith("msg_")) {
			status = Component.translatable("loothub.friends." + err, name).withStyle(ChatFormatting.GREEN);
		} else {
			String key = switch (err) {
				case "no player", "self", "already", "pending", "full", "too_many", "offline" -> err.replace(' ', '_');
				default -> "error";
			};
			status = Component.translatable("loothub.friends.err." + key, name).withStyle(ChatFormatting.RED);
		}
		if (minecraft.screen == this) {
			rebuildWidgets();
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		int max = Math.max(0, rows.size() - visibleRows());
		int ns = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
		if (ns != scroll) {
			scroll = ns;
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		WidgetConfig c = WidgetConfig.get();
		if (!c.friendsOn) {
			int y = ct + 30;
			for (var line : font.split(Component.translatable("loothub.friends.off_info"), cw)) {
				g.drawString(font, line, cl, y, MUTED);
				y += 11;
			}
			return;
		}
		g.drawString(font, status, cl + 324, ct + 26, TEXT);
		if (rows.isEmpty()) {
			Component m = Component.translatable(Friends.loaded() ? "loothub.friends.empty" : "loothub.friends.loading");
			g.drawString(font, m, cl, listTop + 6, MUTED);
			return;
		}
		int visible = visibleRows();
		for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
			Row row = rows.get(i + scroll);
			int ry = listTop + i * ROW_H;
			if (i % 2 == 0) {
				g.fill(cl, ry, cr, ry + ROW_H - 2, 0x22FFFFFF);
			}
			int ty = ry + 6;
			switch (row.kind()) {
				case 0 -> {
					g.drawString(font, "✉", cl + 6, ty, 0xFFF2C14E);
					g.drawString(font, row.person().name(), cl + 20, ty, TEXT);
					g.drawString(font, Component.translatable("loothub.friends.incoming"), cl + 140, ty, 0xFFF2C14E);
				}
				case 1 -> {
					Friends.Friend f = row.friend();
					g.fill(cl + 6, ty + 1, cl + 13, ty + 8, f.online() ? GREEN : 0xFF6A6A6A);
					g.drawString(font, f.name(), cl + 20, ty, f.online() ? TEXT : MUTED);
					String info;
					if (f.online()) {
						info = f.act().isEmpty() ? Component.translatable("loothub.friends.online").getString() : f.act();
					} else if (f.seen() > 0) {
						info = Component.translatable("loothub.friends.seen",
								Fmt.longDuration(System.currentTimeMillis() - f.seen())).getString();
					} else {
						info = Component.translatable("loothub.friends.never").getString();
					}
					int maxW = cr - 160 - (cl + 140);
					g.drawString(font, font.plainSubstrByWidth(info, Math.max(20, maxW)), cl + 140, ty, f.online() ? GREEN : FAINT);
				}
				default -> {
					g.drawString(font, "…", cl + 6, ty, FAINT);
					g.drawString(font, row.person().name(), cl + 20, ty, MUTED);
					g.drawString(font, Component.translatable("loothub.friends.outgoing"), cl + 140, ty, FAINT);
				}
			}
		}
	}
}
