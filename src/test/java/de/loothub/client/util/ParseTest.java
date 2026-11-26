package de.loothub.client.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Geldbeträge und Meldungen so, wie OPSUCHT sie schickt – hier sind schon echte Fehler passiert. */
class ParseTest {
	@Test
	void germanThousands() {
		assertEquals(94750, Parse.money("94.750"), 0.001); // war als 94,75 gelesen worden
		assertEquals(1234567, Parse.money("1.234.567"), 0.001);
		assertEquals(1234.56, Parse.money("1.234,56"), 0.001);
		assertEquals(0.5, Parse.money("0,5"), 0.001);
		assertEquals(12, Parse.money("12"), 0.001);
		assertEquals(1.5, Parse.money("1.5"), 0.001); // kein Tausenderpunkt (nur eine Nachkommastelle)
		assertEquals(0, Parse.money("abc"), 0.001);
		assertEquals(0, Parse.money(null), 0.001);
	}

	@Test
	void marketMessages() {
		assertEquals(1250, Parse.marketAmount("OPSUCHT » Du hast 1.250 $ erhalten."), 0.001);
		assertEquals(94750, Parse.marketAmount("Du hast 64x Kürbis für 94.750 $ verkauft"), 0.001);
		assertEquals(99.5, Parse.marketAmount("Du hast 99,50 $ erhalten"), 0.001);
		assertNull(Parse.marketAmount("Du hast 3 Diamanten erhalten"));
		assertNull(Parse.marketAmount("Spieler » Du hast 0 $ erhalten"));
	}

	@Test
	void fishCatches() {
		assertArrayEquals(new String[]{"Aal", "Mittel", "Episch"},
				Parse.fishCatch("OPSUCHT » Du hast Aal in der Größe Mittel und der Seltenheit Episch gefangen!"));
		assertArrayEquals(new String[]{"Logbuch", "", "Schatz"},
				Parse.fishCatch("OPSUCHT » Du hast Logbuch mit der Seltenheit Schatz gefangen!"));
		assertArrayEquals(new String[]{"Roher Lachs", "", "Vanilla"},
				Parse.fishCatch("Du hast Roher Lachs in der Seltenheit Vanilla gefangen"));
		assertNull(Parse.fishCatch("Du hast einen Erfolg freigeschaltet"));
	}
}
