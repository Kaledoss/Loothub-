package de.loothub.client.cosmetics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Rüstung ausblenden, damit der Skin zu sehen ist – nur in der Darstellung: Die Teile werden vor dem Zeichnen aus dem
 * Darstellungs-Zustand genommen. Angezogen bleibt alles, es schützt weiter, und der Server merkt davon nichts.
 * Köpfe und Blöcke auf dem Kopf (Kürbis, Spielerköpfe) zeichnet Minecraft getrennt; die bleiben sichtbar.
 */
public final class ArmorHide {
	private ArmorHide() {
	}

	public static void apply(Avatar entity, AvatarRenderState state) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.cosmetics || !(c.hideHelmet || c.hideChest || c.hideLegs || c.hideBoots || c.hideElytra)) {
			return;
		}
		if (entity != Minecraft.getInstance().player && !c.hideArmorOthers) {
			return;
		}
		if (c.hideHelmet) {
			state.headEquipment = ItemStack.EMPTY;
		}
		ItemStack chest = state.chestEquipment;
		boolean elytra = chest != null && chest.has(DataComponents.GLIDER);
		if (elytra ? c.hideElytra : c.hideChest) {
			state.chestEquipment = ItemStack.EMPTY;
		}
		if (c.hideLegs) {
			state.legsEquipment = ItemStack.EMPTY;
		}
		if (c.hideBoots) {
			state.feetEquipment = ItemStack.EMPTY;
		}
	}
}
