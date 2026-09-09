# Eine eigene Discovery-Domäne in 60 Minuten

Dies ist eine angeleitete Übung, keine gemessene Usability-Zusage. Nach dem
[Quickstart](java-sdk-quickstart.md) öffne
`examples/external-consumers/finite-difference-domain-java25` aus dem SDK-ZIP.
Die vollständig eigenständige Domäne befindet sich in `FiniteDifferenceDomain.java`.

## 0–15 Minuten: mathematische Objekte

Zustand und Kandidat sind ein Grad von 0 bis 2. Gesucht wird eine Darstellung der
endlichen Quadratzahlenfolge in der Newton-Basis. Die TRAIN-Werte `1,4,9,16`
besitzen Anfangsdifferenzen `1,3,2`. Grad 0 verwendet nur `1`, Grad 1 zusätzlich
`3(n-1)`, Grad 2 zusätzlich `(n-1)(n-2)`. Eine Invariante begrenzt den Grad.
Der Operator erhöht ihn um eins. Die Zielfunktion bevorzugt kleine Grade.

## 15–30 Minuten: Widerlegung und unabhängige Prüfung

Der Gegenbeispielgenerator prüft TRAIN-Positionen 1 bis 4 und rechnet seine
Arbeit gegen das verfügbare Budget ab. Grad 0 wird an Position 2, Grad 1 an
Position 3 widerlegt. Sind weniger als vier Prüfungen erlaubt, bleibt die Suche
unentschieden. Der Evaluator prüft zusätzlich HOLDOUT-Positionen 5 bis 16 gegen
die unabhängige direkte Referenz `n*n`. Nur er stellt ein Zertifikat aus.

Dieses Zertifikat gilt für die geprüften Positionen 1 bis 16. Es ist kein
allgemeiner Satz über jede Folge und kein Nachweis einer kürzesten Darstellung
unter beliebigen Umformungen. Der generische Runner liefert den ersten
bestätigten Kandidaten seiner budgetierten Suche, keine Pareto-Front.

## 30–45 Minuten: Lauf und Evidence

Den Consumer mit denselben Repository-/Versionsparametern wie im Quickstart
bauen. `run` wählt die registrierte Domäne über den ServiceLoader und
`RegelsucheDiscovery.forRegistration(...)`; die kanonische Ausgabe enthält
beide Widerlegungen, Grad 2, das endliche Zertifikat und die beobachteten
Provider-Artefaktbytes. Wiederholungen mit denselben Bytes, Parametern und
deterministischen Callbacks erzeugen dieselbe Evidence.

```java
var run = RegelsucheDiscovery.forRegistration(registration)
    .campaign("mein-lauf")
    .seed("grad-null", "0", "tutorial")
    .budget(DiscoveryBudgets.small())
    .run();
run.writeEvidence(Path.of("mein-lauf.json"));
run.replay(); // Gleicher Seed, gleiche Budgets: kanonische Evidence muss übereinstimmen.
```

Die Datei darf vorher nicht existieren. `evidenceBytes()`, `assumptions()`,
`counterexamples()`, `executedWork()` und `outcome()` sind getrennte Sichten.
Bei absichtlich direktem `forDomain(...)` gibt es keine erfundene
Providerregistrierung; für die Provenienz registrierter Erweiterungen verwende
`forRegistration(...)` oder `registration.domain()`.

## 45–60 Minuten: negative Fälle und eigenes Experiment

Setze den Startgrad auf `3`: `INVALID_SEED`. Verwende `DiscoveryBudgets.tiny()`:
`BUDGET_EXHAUSTED`. Die Tests kontrollieren beide Pfade. Das separate Beispiel
`solver-adapter-java25` prüft zwei fremde Faktorisierungsvorschläge: `15:5`
endet `CONFIRMED`, `15:4` endet `REFUTED`, obwohl die vorgelagerte
Gegenbeispielsuche in beiden Fällen `NONE_FOUND` meldet.

Für eigene Folgen müssen TRAIN, HOLDOUT, Berechnung und unabhängige Referenz
bewusst angepasst werden. Belasse weder die Quadratzahlenreferenz noch die
Zertifikatsbehauptung unverändert, wenn du andere Objekte prüfst.

## Auswahl über die CLI

Mit SDK und Provider-JAR auf dem Java-Klassenpfad:

```bash
java -cp "repository-jars/*:mein-provider.jar" \
  de.regelsuche.sdk.discovery.cli.DiscoveryCli list
java -cp "repository-jars/*:mein-provider.jar" \
  de.regelsuche.sdk.discovery.cli.DiscoveryCli run \
  example.FiniteDifferenceDomain squares-degree@v1 tutorial seed.txt evidence.json small
```

`repository-jars` steht hier für die vom Build aufgelösten Laufzeit-JARs, nicht
für das verschachtelte Maven-Repository. Unter Windows trennt `;` den
Klassenpfad. In der Anwendung lautet derselbe Unterbefehl `domains`.
Nur die angegebene Providerklasse wird aktiviert. Ein unbekannter Provider,
eine falsche Revision oder eine inkompatible API wird abgewiesen. Exitcodes:
0 bestätigt, 2 widerlegt, 3 Budget erschöpft, 4 unentschieden, 5 nicht unterstützt,
6 ungültiger Seed, 1 Eingabe-/Ladefehler. Nicht bestätigte Läufe exportieren
weiterhin ihre Evidence.

## Welcher Erweiterungspunkt passt?

| Aufgabe | Schnittstelle | Verantwortung des Autors |
|---|---|---|
| Eine genaue Umformung | `RegelsuchePlugin.registerRules` | Gültigkeit und Anwendungsbedingungen der Regel |
| Typisierter Operator mit Zertifikat | `DiscoveryDomainBuilder` | Invariante, zulässige Übergänge und unabhängiger Evaluator |
| Eigenständige Suchdomäne | `DiscoveryDomainProvider` | Stabile Identitäten, deterministische Callbacks, TRAIN/HOLDOUT und Kosten |
| Fremder Solver | Kandidatenbildung + unabhängiger Evaluator | Vorschläge bleiben unvertraut, bis eine konkrete Prüfung trägt |
| Proof-/Produktionsfreigabe | vorhandener nachgelagerter Lifecycle | Keine automatische Promotion durch SPI, Hash oder `CONFIRMED` |

## Fehler eingrenzen

- **Provider fehlt:** JAR und `META-INF/services/de.regelsuche.sdk.discovery.DiscoveryDomainProvider` prüfen; Klassenname muss exakt stimmen.
- **Inkompatible API:** `apiVersion()` mit `sdk-compatibility.json` vergleichen. Produktversion und SPI-Revision sind verschiedene Achsen.
- **Keine Herkunftsbytes:** Provider aus einem lokalen JAR oder Klassenverzeichnis laden. Ein CodeSource-loser dynamischer Loader wird abgewiesen.
- **Budget/INCONCLUSIVE:** Evidence-Ressourcen und Gegenbeispielversuche lesen. Ein größeres Budget kann helfen, garantiert aber keine Lösung.
- **Doppelte ID:** Provider-ID und Kombination aus Domain-ID und Revision müssen eindeutig sein.
- **Evidence existiert:** Einen neuen Dateinamen wählen; Export überschreibt keine vorherige Evidence.

## Auswahl in der Workbench

Unter „Eigene Discovery-Domänen“ zeigt die Workbench ausschließlich die vom
Host aktivierten Provider. Der Betreiber startet die Anwendung mit deren JARs
auf dem Klassenpfad und `-Dregelsuche.discovery.providers=example.FiniteDifferenceDomain`
(mehrere Klassen durch Kommas trennen). Ohne diese Einstellung ist die Liste
leer. Ein HTTP-Aufruf kann keine weitere Klasse aktivieren. Die Ansicht bietet
Domäne/Revision, Startdaten, kleine feste Budgets, Status, Gegenbeispiele und
Download der unveränderten kanonischen Evidence. Die normale Workbench-
Authentifizierung und die strenge, auf 1 MiB begrenzte JSON-Decodierung gelten
auch für diese Routen. Aktivierte Java-Callbacks müssen vom Betreiber akzeptiert
sein; Suchbudgets begrenzen keine beliebigen Callback-Wallzeiten.
