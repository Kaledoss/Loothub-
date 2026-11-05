package de.loothub.client.mixin;

import java.util.Locale;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.quick.AmountShortcut;

/**
 * Betrags-Eingaben, die der Server als Amboss öffnet (z. B. Bank oder Clanbank „Geld einzahlen“): das Feld startet
 * leer statt mit „Betrag eingeben…“, und Kurzschreibweisen wie 1m oder 2,5k werden als volle Zahl übernommen.
 * Der Spieler tippt selbst; die Mod ändert nur die Schreibweise.
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilAmountMixin {
	@Shadow
	private EditBox name;

	private static boolean loothub$isAmountItem(ItemStack stack) {
		return !stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("betrag eingeben");
	}

	@Inject(method = "slotChanged", at = @At("TAIL"))
	private void loothub$clearPlaceholder(AbstractContainerMenu menu, int slot, ItemStack stack, CallbackInfo ci) {
		if (slot == 0 && WidgetConfig.get().amountShortcut && name != null && loothub$isAmountItem(stack)) {
			name.setValue("");
		}
	}

	@ModifyVariable(method = "onNameChanged", at = @At("HEAD"), argsOnly = true)
	private String loothub$amount(String text) {
		AnvilScreen self = (AnvilScreen) (Object) this;
		ItemStack input = self.getMenu().getSlot(0).getItem();
		return loothub$isAmountItem(input) ? AmountShortcut.convertAmount(text) : text;
	}
}
