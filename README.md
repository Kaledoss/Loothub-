# Loothub+ – Fabric-Mod für Minecraft 1.21.11 (OPSUCHT)

Zeigt im Item-Tooltip die aktuellen Preise des OPSUCHT-Marktplatzes (`/markt`) an:
Maus über ein Item in Inventar, Kiste oder Menü → unten im Tooltip stehen Kauf- und Verkaufspreis,
bei Stapeln zusätzlich der Wert des ganzen Stapels.

```
Marktplatz
  Kaufen: 14 $ (64× = 896 $) · 5 aktiv
  Verkaufen: 12,5 $ (64× = 800 $) · 3 aktiv
  Stand: vor 2 Min.
```

## Loothub+-Menü (Taste L)

**L** öffnet das Loothub+-Fenster (schwarz-rot): oben die Titelleiste, links die **Kategorien**
**Verlauf**, **Markt**, **Statistik** und **Einstellungen**, rechts der Inhalt. Bei schmalem Fenster zeigt die
Kategorie-Leiste nur Icons. Ist ein Update verfügbar, erscheint rechts in der Titelleiste ein Installieren-Knopf.

> **Wichtig:** In Minecraft liegt L standardmäßig auf „Fortschritte“. Diese Taste bitte unter
> Optionen → Steuerung → Tastenbelegung auf eine andere Taste legen, sonst öffnen sich die Fortschritte statt Loothub+.

### Seite „Markt“

- **Raster über die ganze Breite:** jedes Item als Feld mit Icon, Name, **Kaufen** (gold, was Sie zahlen)
  und **Verkaufen** (grün, was Sie z. B. per `/sell` bekommen). Darüber Suchfeld und Kategorie in einer Zeile.
- **Klick auf ein Item** öffnet seine Preise und das Diagramm für Stunden, Tage, Wochen oder Monate:
  - **Goldene Linie:** Durchschnittspreis
  - **Blaue Balken:** Spanne vom Tiefst- bis zum Höchstpreis je Zeitabschnitt
  - **Grüner/roter Punkt:** Hoch und Tief im gewählten Zeitraum, oben zusätzlich die Veränderung in Prozent
  - Maus über das Diagramm → Details (Ø, Hoch, Tief, gehandelte Stück, Transaktionen)
- **„← Zurück“** oder **Esc** führt zurück zum Raster.

### Reiter „Verlauf“ (Job-Dashboard)

Jede Job-Sitzung wird gespeichert (`config/loothub-jobs.json`, bis zu 1000 Sitzungen) – auch beim Verlassen
des Servers und beim Beenden des Spiels. Das Dashboard zeigt einen **Zeitraum**:

- **Stündlich · Täglich · Wöchentlich · Jährlich · Alltime** wählen, mit **◀ ▶** blättern, **Jetzt** springt zurück.
- **Kennzahlen:** Verdient (+XP), pro Stunde, aktive Zeit und Anzahl Sitzungen, bester Job mit Anteil.
- **Diagramm:** Verlauf bis zum gewählten Zeitraum (Jährlich: die 12 Monate des Jahres). **Geld/Job-XP**
  umschaltbar; Klick auf einen Balken springt in diesen Zeitraum.
- **Job-Karten** für alle 7 Jobs: letztes Level mit Fortschritt, Wert im Zeitraum, $/h und Anteil.
  **Klick auf eine Karte filtert** Kennzahlen, Diagramm und Liste auf diesen Job (nochmal klicken = alle).
- **Sitzungsliste** des Zeitraums, laufende Sitzung grün; Maus drüber zeigt alle Jobs mit Level-Fortschritt.
- **Verlauf löschen** (zweimal klicken zur Bestätigung).

### Reiter „Statistik“

Eigene Statistik wie bei Vanilla (auf OPSUCHT ist die Vanilla-Statistik deaktiviert). Loothub+ zählt selbst mit,
was auf Ihrem PC passiert – nur auf Servern, ab der Installation – und speichert es pro Tag in
`config/loothub-stats.json`. Zeitraum wählbar: **Heute**, **7 Tage**, **Gesamt**.

- **Allgemein:** Spielzeit, Strecken (gelaufen, gesprintet, geschlichen, geschwommen, geklettert, Elytren,
  geflogen, geritten), Sprünge, Blöcke abgebaut/platziert, Items aufgesammelt, Nahrung gegessen, Mobs getötet,
  Schaden ausgeteilt/erlitten, Tode.
- **Blöcke:** je Blockart abgebaut und platziert (ohne Gras, Farn & Co.) · **Items:** aufgesammelt und gegessen · **Mobs:** getötet.
  Mit Suche; Klick auf eine Spaltenüberschrift sortiert (nochmal klicken = umgekehrt).

Grenzen: Getötete Mobs sind geschätzt (Tod innerhalb von 5 s nach Ihrem Treffer). Blöcke, die der Server
zusätzlich abbaut (z. B. Timber-Axt), und Items, die direkt ins Inventar gelegt werden, sieht der Client nicht.

## Inventar in Schwarz-Rot

Loothub+ bringt ein eingebautes Ressourcenpaket **„Loothub+ Inventar (Schwarz-Rot)“** mit, das das
Spieler-Inventar (Taste E) passend zum Loothub+-Fenster färbt. Statt „Handwerk“ steht oben „Loothub+“. Rechts neben dem Zweithand-Slot sitzen zwei feste
Knöpfe: **Werkbank** (`/werkbank`) und **Amboss** (`/anvil`). Es ist automatisch aktiv und lässt sich unter
**Optionen → Ressourcenpakete** ausschalten. Rein optisch (Regelwerk §5.10.3).

## Befehls-Leiste im Inventar

Unter dem Inventar (Taste **E**) erscheint ein Feld mit Knöpfen für wichtige Befehle:
Markt (`/markt`), Verkaufen (`/sell`), Auktionshaus (`/ah`), Jobs (`/jobs`), Fischerei (`/fischerei`),
Jäger (`/hunter`), Farmer (`/farmer`), Clan (`/clan`), Quests (`/quest`), Erfolge (`/achievement`).

- **Klick** schickt genau einen Befehl – wie selbst eingetippt, keine Automatisierung.
- **Knöpfe ziehen** sortiert sie um; **Kopfleiste ziehen** („⠿ Loothub+“) verschiebt das ganze Feld.
- **Häkchen „Sperren“** oben rechts im Feld: dann lässt sich nichts mehr verschieben.
- Reihenfolge, Position und Sperre werden in `config/loothub.json` gespeichert (`quickCommands` usw.);
  dort lassen sich auch Beschriftungen und Befehle ändern. In den Einstellungen ein-/ausschaltbar.

## Einstellungen (/loothub)

**/loothub** im Chat (oder die Kategorie **Einstellungen**) öffnet die Einstellungen – oben Version und
Update-Stand mit „Erneut prüfen“, darunter Karten mit Schalter links und Beschreibung rechts:

- **Job-Widget:** an/aus, Position ändern, Job-Sitzung zurücksetzen
- **Marktpreise:** Preise im Item-Tooltip an/aus
- **Statistik:** Zählen an/aus
- **Updates:** automatisch beim Start prüfen an/aus, „Jetzt prüfen“, installierte und neueste Version

Gespeichert in `config/loothub.json`.

### Job-Widget (in den Einstellungen)

Zeigt im Spiel ein frei verschiebbares Widget mit dem aktuellen Job:

```
Holzfäller · Level 58
[██████░░░░░░░░░░░░░░] 13,85 %
Sitzung: +1.240 XP · +6.320,5 $
Pro Stunde: 4.100 XP · 21k $
Level-Up in: ~38 Min.
```

- **Voraussetzung:** Die Job-Fortschrittsanzeige von OPSUCHT muss an sein (`/job toggle`). Die Mod liest diese
  Actionbar-Zeile mit (z. B. `+2,5 XP • +12,73$ • Holzfäller Level 58 • […] 13,85%`), es gibt dafür keine API.
- In den Einstellungen (**/loothub**): Widget ein-/ausschalten, **Position ändern** (mit der Maus ziehen),
  Job-Sitzung zurücksetzen.
  Die Position wird in `config/loothub.json` gespeichert.
- Nur aktive Zeit zählt: mehr als 60 s ohne Job-Aktion gilt als Pause und verfälscht die Stundenwerte nicht.
  Nach 10 Minuten ohne Aktion oder beim Verlassen des Servers beginnt eine neue Sitzung.
- Das Widget erscheint erst nach der ersten erkannten Job-Aktion.

Inoffiziell, nicht mit OPSUCHT / Interwebmedia GmbH verbunden.

## Datenquelle

Öffentliche OPSUCHT-API: `GET https://api.opsucht.net/market/prices` (aktuelle Preise) und
`GET /market/history/{ITEM}` (Preisverlauf, wird nur für das im Menü ausgewählte Item geladen).
Die Mod fragt höchstens alle 5 Minuten (nach einem Fehler: 1 Minute) neu an – und nur, wenn gerade ein
Tooltip angezeigt wird. Umbenannte Items und Sonder-Items mit eigenem Modell bekommen keinen Preis.

## Regelwerk

Die Mod zeigt nur Daten an: öffentliche Marktpreise und die Job-Anzeige, die der Server ohnehin sendet. Sie klickt, kauft und verkauft nichts selbst
(Bots/Makros sind laut §5.11.1 verboten). Da sie nicht auf der Liste erlaubter Mods steht,
vor der Nutzung auf dem Server beim OPSUCHT-Support nachfragen (§5.10.4).

## Voraussetzungen

- **JDK 25** zum Bauen (z. B. Eclipse Temurin 25) – die fertige Mod läuft mit dem Java 21 von Minecraft
- Empfohlen: IntelliJ IDEA mit dem Plugin „Minecraft Development“

## Testen

```bash
./gradlew runClient        # Windows: gradlew.bat runClient
```

## Mod-Datei bauen

```bash
./gradlew build
```

Ergebnis: `build/libs/loothub-1.0.0.jar` (die `-sources`-Datei brauchen Sie nicht).
Diese Datei zusammen mit der Fabric API für 1.21.11 in den `mods`-Ordner legen.

## Sprache

Alle Texte von Loothub+ sind deutsch – unabhängig von der Spielsprache.

## Updates im Spiel

Loothub+ prüft beim Start, ob auf GitHub (Release „latest“ von Kaledoss/Loothub-) eine neuere Version liegt.
Falls ja, erscheint ein Hinweis im Chat und im Loothub+-Menü (Taste L) der Knopf **„Update … installieren“**.
Die neue Datei wird in denselben Mods-Ordner geladen, die alte nach dem Beenden entfernt –
nach einem **Neustart** ist die neue Version aktiv. Funktioniert nur, solange das Repository öffentlich ist.

## Mit LabyMod nutzen

LabyMod lädt Fabric-Mods nur über ein **Modpack im LabyMod Launcher** – der normale `.minecraft/mods`-Ordner wird ignoriert.

1. `./gradlew build` ausführen → `build/libs/loothub-1.0.0.jar` (nicht die `-sources`-Datei, nicht die Zip).
2. LabyMod Launcher → unten links **neues Modpack** erstellen → Version **1.21.11** → Modloader **Fabric**.
3. Im Modpack-Manager **„Open Fabric Directory“** klicken.
4. `loothub-1.0.0.jar` und die **Fabric API für 1.21.11** in diesen Ordner kopieren.
5. Das Modpack auswählen und starten.

Fabric-Unterstützung in LabyMod ist laut LabyMod experimentell; manche Mods sind nicht kompatibel.

## Projektaufbau

| Datei | Zweck |
|---|---|
| `src/client/java/de/loothub/client/MarketPrices.java` | Lädt und speichert die Preise von der API |
| `src/client/java/de/loothub/client/PriceTooltip.java` | Schreibt die Preise in den Tooltip |
| `src/client/java/de/loothub/client/MarketHistory.java` | Lädt den Preisverlauf |
| `src/client/java/de/loothub/client/MarketScreen.java` | Reiter „Markt“ mit Liste und Diagramm |
| `src/client/java/de/loothub/client/LoothubScreen.java` | Gemeinsames Fenster: Titelleiste, Kategorien, Farben |
| `src/client/java/de/loothub/client/Tabs.java` | Update-Knopf in der Titelleiste |
| `src/client/java/de/loothub/client/jobs/JobParser.java` | Erkennt die Job-Zeile in der Actionbar |
| `src/client/java/de/loothub/client/jobs/JobTracker.java` | Sitzungswerte, XP/h, $/h, Level-Up-Zeit |
| `src/client/java/de/loothub/client/jobs/JobWidget.java` | Zeichnet das Widget |
| `src/client/java/de/loothub/client/jobs/JobHistory.java` | Speichert die Sitzungen |
| `src/client/java/de/loothub/client/jobs/JobHistoryScreen.java` | Reiter „Verlauf“ |
| `src/client/java/de/loothub/client/jobs/JobWidgetEditScreen.java` | Job-Widget verschieben |
| `src/client/java/de/loothub/client/SettingsScreen.java` | Einstellungen (/loothub) |
| `src/client/java/de/loothub/client/mixin/GuiMixin.java` | Liest Actionbar-Nachrichten mit |
| `src/client/java/de/loothub/client/stats/*` | Eigene Statistik: Zählung, Speicher, Reiter |
| `src/client/java/de/loothub/client/mixin/BlockItemMixin.java` | Zählt platzierte Blöcke |
| `src/client/java/de/loothub/client/mixin/ClientPacketListenerMixin.java` | Zählt aufgesammelte Items |
| `src/main/resources/assets/loothub/lang/*.json` | Texte (Deutsch/Englisch) |
| `gradle.properties` | Versionen (Minecraft 1.21.11, Loader 0.20.1, Fabric API 0.141.6) |
