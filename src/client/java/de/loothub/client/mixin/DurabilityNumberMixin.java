package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.jobs.WidgetConfig;

/** Rest-Haltbarkeit als kleine Zahl oben links im Slot (nur Anzeige). */
@Mixin(GuiGraphics.class)
public abstract class DurabilityNumberMixin {
	@Inject(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
			at = @At("TAIL"))
	private void loothub$durability(Font font, ItemStack stack, int x, int y, @Nullable String text, CallbackInfo ci) {
		if (stack.isEmpty() || !stack.isDamageableItem() || stack.getDamageValue() <= 0
				|| !WidgetConfig.get().durabilityNumbers) {
			return;
		}
		int left = stack.getMaxDamage() - stack.getDamageValue();
		String s = left >= 10000 ? (left / 1000) + "k" : left >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fk", left / 1000.0) : Integer.toString(left);
		GuiGraphics g = (GuiGraphics) (Object) this;
		g.pose().pushMatrix();
		g.pose().translate(x + 1, y + 1);
		g.pose().scale(0.5F, 0.5F);
		g.drawString(Minecraft.getInstance().font, s, 0, 0, 0xFF000000 | stack.getBarColor(), true);
		g.pose().popMatrix();
	}
}
