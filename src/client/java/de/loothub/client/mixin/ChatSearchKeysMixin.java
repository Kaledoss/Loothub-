package de.loothub.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.CustomChat;

/** Tippen ins Suchfeld des eigenen Chats – vor dem Chat-Bildschirm (auch dem von LabyMod). */
@Mixin(KeyboardHandler.class)
public abstract class ChatSearchKeysMixin {
	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void loothub$char(long window, CharacterEvent event, CallbackInfo ci) {
		if (CustomChat.active() && CustomChat.typeChar(event.codepoint())) {
			ci.cancel();
		}
	}

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void loothub$key(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (action != 0 && CustomChat.active() && CustomChat.typeKey(event.key(), event.hasControlDown())) {
			ci.cancel();
		}
	}
}
