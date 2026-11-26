package de.loothub.client.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.Test;

/** Alle Texte gibt es auf Deutsch und Englisch, mit gleichen Platzhaltern, und jeder im Code benutzte Schlüssel existiert. */
class LangTest {
	private static final Path LANG = Path.of("src/main/resources/assets/loothub/lang");

	private static Map<String, String> load(String file) throws IOException {
		return new Gson().fromJson(Files.readString(LANG.resolve(file)), new TypeToken<Map<String, String>>() { }.getType());
	}

	@Test
	void sameKeysInBothLanguages() throws IOException {
		Map<String, String> de = load("de_de.json"), en = load("en_us.json");
		TreeSet<String> onlyDe = new TreeSet<>(de.keySet());
		onlyDe.removeAll(en.keySet());
		TreeSet<String> onlyEn = new TreeSet<>(en.keySet());
		onlyEn.removeAll(de.keySet());
		assertTrue(onlyDe.isEmpty(), "nur auf Deutsch: " + onlyDe);
		assertTrue(onlyEn.isEmpty(), "nur auf Englisch: " + onlyEn);
	}

	@Test
	void samePlaceholders() throws IOException {
		Map<String, String> de = load("de_de.json"), en = load("en_us.json");
		List<String> bad = new ArrayList<>();
		for (var e : de.entrySet()) {
			String other = en.get(e.getKey());
			if (other != null && count(e.getValue()) != count(other)) {
				bad.add(e.getKey());
			}
		}
		assertTrue(bad.isEmpty(), "unterschiedlich viele Platzhalter (%s): " + bad);
	}

	private static int count(String s) {
		Matcher m = Pattern.compile("%(\\d+\\$)?[sd]").matcher(s);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	@Test
	void usedKeysExist() throws IOException {
		Map<String, String> de = load("de_de.json");
		Pattern p = Pattern.compile("translatable\\(\\s*\"(loothub\\.[A-Za-z0-9_.]+)\"\\s*([+)\\],])");
		TreeSet<String> missing = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Path.of("src/client/java"))) {
			for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
				Matcher m = p.matcher(Files.readString(f));
				while (m.find()) {
					String key = m.group(1);
					if (!m.group(2).equals("+") && !key.endsWith(".") && !de.containsKey(key)) {
						missing.add(key);
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "im Code benutzt, aber ohne Text: " + missing);
	}
}
