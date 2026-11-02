# Loothub+ – Fabric-Mod für Minecraft 1.21.11 (OPSUCHT)

Zeigt im Item-Tooltip die aktuellen Preise des OPSUCHT-Marktplatzes (`/markt`) an:
Maus über ein Item in Inventar, Kiste oder Menü → unten im Tooltip stehen Kaufaufträge und Angebote,
bei Stapeln zusätzlich der Wert des ganzen Stapels.

```
Marktplatz
  Kaufaufträge: 12,5 $ (64× = 800 $) · 3 aktiv
  Angebote: 14 $ (64× = 896 $) · 5 aktiv
  Stand: vor 2 Min.
```

## Loothub+-Menü (Taste L)

**L** öffnet das Loothub+-Menü mit drei Reitern oben in der Mitte: **Verlauf** (öffnet zuerst), **Markt** und **Job-Widget**
(Taste änderbar unter Optionen → Steuerung → „Loothub+“).

> **Wichtig:** In Minecraft liegt L standardmäßig auf „Fortschritte“. Diese Taste bitte unter
> Optionen → Steuerung → Tastenbelegung auf eine andere Taste legen, sonst öffnen sich die Fortschritte statt Loothub+.

### Reiter „Markt“

- **Links:** alle Items vom Marktplatz mit aktuellem Angebotspreis, Suchfeld und Kategorie-Filter.
- **Rechts:** aktuelle Kaufaufträge/Angebote und ein Preisdiagramm für Stunden, Tage, Wochen oder Monate.
  - **Goldene Linie:** Durchschnittspreis
  - **Blaue Balken:** Spanne vom Tiefst- bis zum Höchstpreis je Zeitabschnitt
  - **Grüner/roter Punkt:** Hoch und Tief im gewählten Zeitraum, oben zusätzlich die Veränderung in Prozent
  - Maus über das Diagramm → Details (Ø, Hoch, Tief, gehandelte Stück, Transaktionen)

### Reiter „Job-Widget“

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
- Im Reiter **Job-Widget** (Taste L): Widget mit der Maus verschieben, ein-/ausschalten, Sitzung zurücksetzen.
  Die Position wird in `config/loothub.json` gespeichert.
- Nur aktive Zeit zählt: mehr als 15 s ohne Job-Aktion gilt als Pause und verfälscht die Stundenwerte nicht.
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

### Reiter „Verlauf“

Jede Job-Sitzung wird gespeichert (`config/loothub-jobs.json`, bis zu 1000 Sitzungen) – auch beim Verlassen
des Servers und beim Beenden des Spiels:

- **Summen** für heute, die letzten 7 Tage und gesamt: Geld, XP, aktive Zeit und $/h.
- **Balkendiagramm** mit dem Verdienst pro Tag der letzten 14 Tage (Maus drüber für Details).
- **Liste aller Sitzungen**, neueste oben, die laufende Sitzung grün: Beginn, Job, aktive Zeit, XP, $, $/h
  und (bei breitem Fenster) Level. Maus über eine Zeile zeigt alle Jobs der Sitzung mit Level-Fortschritt.
- **Job-Filter** (z. B. nur Holzfäller) und **Verlauf löschen** (zweimal klicken zur Bestätigung).

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
| `src/client/java/de/loothub/client/Tabs.java` | Die Reiter oben links |
| `src/client/java/de/loothub/client/jobs/JobParser.java` | Erkennt die Job-Zeile in der Actionbar |
| `src/client/java/de/loothub/client/jobs/JobTracker.java` | Sitzungswerte, XP/h, $/h, Level-Up-Zeit |
| `src/client/java/de/loothub/client/jobs/JobWidget.java` | Zeichnet das Widget |
| `src/client/java/de/loothub/client/jobs/JobHistory.java` | Speichert die Sitzungen |
| `src/client/java/de/loothub/client/jobs/JobHistoryScreen.java` | Reiter „Verlauf“ |
| `src/client/java/de/loothub/client/jobs/JobWidgetEditScreen.java` | Reiter „Job-Widget“: Editor zum Verschieben |
| `src/client/java/de/loothub/client/mixin/GuiMixin.java` | Liest Actionbar-Nachrichten mit |
| `src/main/resources/assets/loothub/lang/*.json` | Texte (Deutsch/Englisch) |
| `gradle.properties` | Versionen (Minecraft 1.21.11, Loader 0.20.1, Fabric API 0.141.6) |
