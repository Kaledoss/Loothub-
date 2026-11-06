package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.quick.SignEditorScreen;

/**
 * Schild beschriften im Loothub-Fenster: Statt des normalen Editors öffnet sich {@link SignEditorScreen}. Der
 * Original-Editor wartet im Hintergrund und schickt erst beim „Fertig“ des Loothub-Fensters wie gewohnt genau
 * eine Aktualisierung an den Server (dieselbe, die Minecraft auch schickt).
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignEditMixin implements SignEditorScreen.Access {
	@Shadow
	@Final
	private String[] messages;
	@Shadow
	@Final
	protected SignBlockEntity sign;
	@Unique
	private boolean loothub$handed;
	@Unique
	private boolean loothub$finishing;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void loothub$openEditor(CallbackInfo ci) {
		if (!loothub$handed && WidgetConfig.get().signWindow) {
			loothub$handed = true;
			Minecraft.getInstance().setScreen(new SignEditorScreen(this));
			ci.cancel();
		}
	}

	@Inject(method = "removed", at = @At("HEAD"), cancellable = true)
	private void loothub$holdUpdate(CallbackInfo ci) {
		if (loothub$handed && !loothub$finishing) {
			ci.cancel(); // erst senden, wenn das Loothub-Fenster fertig ist
		}
	}

	@Override
	public String[] loothub$lines() {
		return messages.clone();
	}

	@Override
	public SignBlockEntity loothub$sign() {
		return sign;
	}

	@Override
	public void loothub$finish(String[] lines) {
		for (int i = 0; i < 4 && i < lines.length; i++) {
			messages[i] = lines[i] == null ? "" : lines[i];
		}
		loothub$finishing = true;
		((AbstractSignEditScreen) (Object) this).removed();
	}
}
