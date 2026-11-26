package de.loothub.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Suche in den Einstellungen. */
class SearchTest {
	private static final String BACKPACK = "Anglerrucksack-Wert Zeigt den Wert deines Rucksacks im Job-Tracker. Angeln";
	private static final String FOOD = "Sättigungs-Anzeige Goldene Striche unter den Hungerkeulen";

	@Test
	void umlautsAndCase() {
		assertTrue(Search.matches(FOOD, "saettigung"));
		assertTrue(Search.matches(FOOD, "SÄTTIGUNG"));
		assertEquals("saettigungs anzeige", Search.fold("§6Sättigungs-Anzeige!"));
	}

	@Test
	void otherLanguage() {
		assertTrue(Search.matches(BACKPACK, "backpack"));
		assertTrue(Search.matches(FOOD, "saturation"));
		assertTrue(Search.matches(BACKPACK, "fishing"));
	}

	@Test
	void typos() {
		assertTrue(Search.matches(BACKPACK, "rucksak"));
		assertTrue(Search.oneEdit("rucksack", "rucksak"));
		assertFalse(Search.oneEdit("rucksack", "rcksak"));
	}

	@Test
	void allWordsMustMatch() {
		assertTrue(Search.matches(BACKPACK, "wert tracker"));
		assertFalse(Search.matches(BACKPACK, "wert villager"));
		assertFalse(Search.matches(FOOD, "rucksack"));
	}
}
