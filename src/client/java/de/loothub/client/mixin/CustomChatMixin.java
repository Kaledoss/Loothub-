package de.loothub.client.mixin;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.CustomChat;

/** Eigener Chat: Nachrichten mitlesen und – wenn eingeschaltet – statt des normalen Chats die eigenen Fenster zeichnen. */
@Mixin(ChatComponent.class)
public abstract class CustomChatMixin {
	/** Fertige Nachricht (nach Uhrzeit/Kopieren/Erwähnung, gefilterte kommen hier nicht an). */
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			at = @At("TAIL"))
	private void loothub$collect(Component message, @Nullable MessageSignature signature, @Nullable GuiMessageTag tag, CallbackInfo ci) {
		CustomChat.add(message);
	}

	/** Zeichnen, Hover und Klick-Erkennung laufen alle hier durch. */
	@Inject(method = "render(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IIZ)V",
			at = @At("HEAD"), cancellable = true)
	private void loothub$render(ChatComponent.ChatGraphicsAccess access, int height, int ticks, boolean focused, CallbackInfo ci) {
		CustomChat.noteRender(focused);
		// HUD ausgeblendet (F1): Chat auch beim Tippen (T) nicht zeigen
		if (net.minecraft.client.Minecraft.getInstance().options.hideGui) {
			ci.cancel();
			return;
		}
		if (CustomChat.active()) {
			CustomChat.render(access, focused);
			ci.cancel();
		}
	}

	@Inject(method = "scrollChat", at = @At("HEAD"), cancellable = true)
	private void loothub$scroll(int amount, CallbackInfo ci) {
		if (CustomChat.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "resetChatScroll", at = @At("HEAD"))
	private void loothub$reset(CallbackInfo ci) {
		CustomChat.resetScroll();
	}
}
