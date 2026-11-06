package de.loothub.client.quick;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Chat-Komfort (nur eigene Anzeige): Uhrzeit vor jeder Nachricht, Klick auf eine Nachricht kopiert sie, und wird man
 * selbst erwähnt, bekommt die Zeile eine goldene Markierung und der eigene Name leuchtet – optional mit leisem Ton.
 */
public final class ChatExtras {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private ChatExtras() {
	}

	/** Aufgerufen für jede Nachricht, bevor sie im Chat landet. */
	public static Component modify(Component message) {
		WidgetConfig c = WidgetConfig.get();
		if (message == null || !(c.chatTimestamps || c.chatCopy || c.chatMention)) {
			return message;
		}
		String plain = message.getString();
		if (plain.isBlank()) {
			return message;
		}
		MutableComponent out = message.copy();
		if (c.chatCopy) {
			// Klick kopiert den Text; eigene Klick-Aktionen (Links, Befehle) einzelner Teile bleiben erhalten
			out = out.withStyle(s -> s.getClickEvent() != null ? s
					: s.withClickEvent(new ClickEvent.CopyToClipboard(plain))
							.withHoverEvent(s.getHoverEvent() != null ? s.getHoverEvent()
									: new HoverEvent.ShowText(Component.translatable("loothub.chat.copy_hint"))));
		}
		boolean mention = c.chatMention && mentionsMe(plain);
		MutableComponent prefix = Component.empty();
		if (c.chatTimestamps) {
			prefix.append(Component.literal("[" + LocalTime.now().format(TIME) + "] ").withStyle(ChatFormatting.DARK_GRAY));
		}
		if (mention) {
			prefix.append(Component.literal("▍").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			out = highlightName(out);
			if (c.chatMentionSound) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.35F, 1.6F);
				}
			}
		}
		return prefix.getSiblings().isEmpty() ? out : prefix.append(out);
	}

	private static String myName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getGameProfile().name() : mc.getUser().getName();
	}

	/** Eigener Name kommt vor – aber nicht nur als Absender der eigenen Nachricht (vor dem ersten „»“). */
	private static boolean mentionsMe(String plain) {
		String name = myName();
		if (name == null || name.length() < 3) {
			return false;
		}
		String low = plain.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
		int arrow = low.indexOf('»');
		if (arrow < 0) {
			arrow = low.indexOf(':');
		}
		String body = arrow >= 0 ? low.substring(arrow + 1) : low;
		if (arrow >= 0 && low.substring(0, arrow).contains(n)) {
			return false; // eigene Nachricht
		}
		return body.contains(n);
	}

	/** Eigenen Namen in der Nachricht golden und fett hervorheben. */
	private static MutableComponent highlightName(MutableComponent msg) {
		String name = myName();
		MutableComponent out = Component.empty();
		msg.visit((style, part) -> {
			String low = part.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
			int from = 0, i;
			while ((i = low.indexOf(n, from)) >= 0) {
				if (i > from) {
					out.append(Component.literal(part.substring(from, i)).withStyle(style));
				}
				out.append(Component.literal(part.substring(i, i + n.length()))
						.withStyle(style.withColor(ChatFormatting.GOLD).withBold(true)));
				from = i + n.length();
			}
			if (from < part.length()) {
				out.append(Component.literal(part.substring(from)).withStyle(style));
			}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return out;
	}
}
