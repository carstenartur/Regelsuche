# Native bedingte Gleichheitssättigung

Die Prüflinie `exact-rational-field/ac-add-mul/v1` behandelt Addition und Multiplikation als n-stellige Multimengen mit Multiplizität. Subtraktion, Division, Potenzen und Funktionsargumente behalten ihre Rollen. Ein Lauf erhält eine feste Quellrevision, ein unverändertes Inventar und einen unveränderlichen Annahmekontext. Das Manifest nennt für jede angebotene Regel ihre Inhaltssumme und den nativen Status oder Ausschlussgrund.

`scalar-saturation run` schreibt den Sättigungsbericht und inhaltsadressierte Binärbelege. Das Beispiel führt zuerst die Anwendung aus und öffnet anschließend den Beleg mit denselben unabhängigen Eingaben. `REVISION` bezeichnet den tatsächlichen Commit, aus dem die Anwendung gebaut wurde.

```sh
REVISION=$(git rev-parse HEAD)
REPORT=$(regelsuche scalar-saturation run scalar-result "$REVISION" ROUND_ROBIN '((b+0)*a)/a' a)
regelsuche scalar-saturation verify scalar-result "$REVISION" ROUND_ROBIN '((b+0)*a)/a' "$REPORT" a
```

`scalar-saturation check <Belegdatei> <SHA-256> <Revision> <Quelle> <Ergebnis> [Nichtnullvariable ...]` führt nur den kleinen empfangenden Prüfer aus. Der Bericht enthält den Hash für jede Extraktion. Quelle, Ziel, Regelversionen und Annahmen werden unabhängig vom Beleg vorgegeben. Die lokale Datei `<Hash-ohne-Präfix>.rsproof` enthält Axiome, Instanzen, Regel-/Guardbezüge und Kongruenzschritte. Ein gültiger älterer geordneter v1-Beleg behält seine ursprüngliche Darstellung.

Beide Planungsvarianten besitzen feste Rundenzahl, Knotengrenze und Brennstoff pro Regel. `ROUND_ROBIN` führt die freigegebenen Regeln nach ID aus. `PRODUCTIVITY` bevorzugt in der nächsten Runde die Regeln mit mehr bereits erfolgreichen Vereinigungen und löst Gleichstände nach ID auf. Die Klasse eines vorher geprüften Wurzelkandidaten wird nach Änderungen an ihr oder ihren abhängigen Kindern erneut untersucht. Zwei feste Kostenfunktionen extrahieren aus demselben Graphen. Der Bericht bewahrt vorgeschlagenen und geprüften Ausdruck, rohe Syntaxgröße und Beweisgröße separat.

Das native Fragment enthält neutrale Elemente, geordnete Division durch eins/Subtraktion von null, doppelte Summanden, zweigliedrige Faktorisierung und rationale Faktorkürzung unter bestätigter Nichtnullbedingung. Andere Regeln erscheinen als ausgeschlossen. Eine unentschiedene oder widersprüchliche Voraussetzung kann keine Kürzung auslösen. Die Analyse hält beobachtete Variablen, begrenzte exakte Konstanten, Nennerform, Polynomgradobergrenze, kleinste beobachtete Syntaxkosten, mögliche Vorzeichen und Ganzzahligkeit fest. Eine unbekannte oder zu große Berechnung bleibt offen. Matrizen und allgemeine Funktionsaxiome liegen außerhalb des Skalarprofils. Der optionale Polynomprüfer erzeugt eine eigene Solver-IR-Verpflichtung und bewahrt die zugehörigen Ausführungs- und Ergebnisbytes. Der Empfänger reproduziert diese Ausführung.

## Öffentlicher Vergleich

Das feste Panel enthält die beiden historischen #620-Fälle `factor-common-left` und `factor-common-right`, einen Rollentausch, eine gemischte bedingte Kürzung und eine Negativkontrolle. Alle drei Ausführungen erhalten Quelle, eigene unterstützte Regeln und erklärte Voraussetzungen. Die Auswertung prüft den Zielterm danach modulo der beiden Skalar-AC-Gesetze. SymPy 1.14.0 und mpmath 1.3.0 werden tatsächlich als externer Prozess aufgerufen. Der externe Pfad bewahrt den Operationsnamen, Eingabe, Ausgabe und `count_ops`. Er bestätigt keinen fremden Beweis.

| Fall | BestFirst | Beide nativen Zeitpläne | SymPy |
|---|---|---|---|
| Links/rechts gruppierter #620-Faktor | Erreicht, je 2 besuchte Zustände | Erreicht, je 3 DAG-Schritte | Erreicht |
| Gemeinsamer Faktor in entgegengesetzten Rollen | Verfehlt, 1 besuchter Zustand | Erreicht, je 3 DAG-Schritte | Erreicht |
| Kürzung nach Addition von null bei deklariertem a ≠ 0 | Erreicht, 4 besuchte Zustände | Erreicht, je 11 DAG-Schritte | Erreicht |
| Dieselbe Kürzungsfrage ohne Nichtnullbeleg | Verfehlt | Verfehlt, reflexiver Beleg für den Ausgangsterm | Nichtnullbedingung offen |

Die Beobachtung betrifft diese fünf Quellen. Matching-Fuel, Nutzerzustände der Pfadsuche, Größe eines Beweis-DAGs und SymPys AST-Operationsanzahl sind unterschiedliche Messgrößen. Ein allgemeiner Geschwindigkeits-, Arbeits- oder Vollständigkeitsvorteil folgt daraus nicht.

`scripts/reproduce-native-scalar-saturation.sh <neues-Verzeichnis> --host-only` extrahiert zwei unabhängige Git-Archive des aktuellen Commits. Jeder Archivlauf baut seinen Benchmark-Launcher im **Maven-Reaktor**, ermittelt die Laufzeitabhängigkeiten aus dem jeweiligen Maven-Build und vergleicht anschließend sämtliche Bericht-, SymPy- und Zertifikatbytes. Für diesen manuellen Modus muss der Aufrufende eine passende SymPy-1.14.0-/mpmath-1.3.0-Referenzumgebung über `REGELSUCHE_SYMPY_PYTHON` bereitstellen.

`--with-container` baut zuerst das bestehende Vergleichsimage und verwendet für **jede** externe SymPy-Anfrage ausschließlich dessen tatsächliche OCI-SHA-256-Image-ID. Der externe Interpreter läuft in einem schreibgeschützten Container ohne Netzwerk und ohne Python-/pip-Installation auf dem Host; der zweite und dritte Vergleichslauf bleiben unabhängig quellgebunden. Auch das Image führt das Panel beim Build aus. Zusätzliche Optionen nach dem Modus werden an Maven übergeben, nicht an Gradle. Ein erfolgreicher lokaler Vergleich ersetzt weder die vollständige CI-Abnahme noch einen unabhängig verdeckten Forschungsdatensatz.

## Arbeits- und Laufzeitdiagnostik

Die beiden nativen Zeitpläne laufen auf denselben fünf Fällen mit höchstens
8 Runden, 256 E-Knoten und 4.000 Matching-Einheiten je freigegebener Regel. Die
20 Zeilen des separaten Berichts `diagnostics/*.json` umfassen beide Zeitpläne
unter gleichem Arbeitsbudget und je einen weiteren Durchlauf mit einer
50-ms-Frist für die Zielerreichung. Ein geprüfter Zielausdruck zählt
innerhalb dieser Frist. Ein späterer Abschluss erhält
`TIME_BUDGET_INCONCLUSIVE` und einen leeren Trefferwert. Die schon
arbeitsbegrenzte Berechnung läuft für Diagnosezwecke bis zu ihrem Abschluss.
Die Frist misst die Sättigung einschließlich der Erstellung und Prüfung
des Belegs sowie die Zählung im fertigen Graphen. Ein Aufwärmlauf geht jeder
Kombination aus Fall und Zeitplan voraus. Unabhängige Wiederholungen mit
Rundengrenzen von eins bis zur Abschlussrunde bestimmen die erste Runde
mit geprüftem Ziel (`firstCheckedRound`). Im Zeitvergleich muss die jeweilige
Wiederholung selbst innerhalb der Frist liegen. Die anschließende
Auswertung vergleicht den geprüften Ausdruck mit dem Ziel. Die Präfixmessung
bleibt außerhalb der Laufzeit und Arbeitsbilanz der vollständigen Zeile.

Der Bericht zählt E-Knoten nach Operatorart. Eine E-Klasse zählt einmal pro
enthaltener Art. Die AC-Kompression der Eingabe ist der Quotient aus binären
Additions- und Multiplikationsvorkommen und maximalen gleichartigen
AC-Teilbäumen nach deren Abflachung. Der Quotient erfasst ausschließlich
die Eingabesyntax. Mehrfach vorkommende Operanden bleiben erhalten.

Das Regelbuch zählt Kandidatenwurzeln, Scans, Tupel, Treffer, Duplikate,
abgelehnte Guards, Cache-Wiederverwendungen, direkte Regelvereinigungen
und verbrauchten Brennstoff. Für beide Extraktionsziele führt der Bericht
Termknoten und -kanten, Beweisschritte und Prämissenkanten sowie die
Arbeitszählung der abgelösten DAG-Prüfung. Die Beweistiefe zählt die Schritte
auf dem längsten Prämissenpfad zur Schlussfolgerung einschließlich seiner Enden.
Das Strukturmodell `totalMechanicalUnits` addiert Matching-Brennstoff
und Klassenanalysen zu den sieben Zählern in `producerStages`:

| Zähler | Einheit |
|---|---|
| `sourcePreparation` | Ein Quellprojektionsvorgang, gebundene Inventar-/Prämisseneinträge und die gemessene Vorprüfung des Quellausdrucks. |
| `scheduler` | Runden, Regelaufrufe einschließlich übersprungener Regeln, ausgewählte Klassen und tatsächlich ausgeführte Produktivitätsvergleiche. |
| `graphStructure` | Graphzugriffe und die dabei betrachteten Klassen, Knoten und Operanden beim Aufbau, bei der Indexpflege, beim Umbau und beim Nachziehen betroffener Vorgänger. |
| `representativeForest` | Anlagen, Vereinigungen und Repräsentantensuchen, einschließlich der tatsächlich durchlaufenen Elternverweise. |
| `extraction` | Kostentabellen- und Knotenauswertungen sowie Ausgabeaufbau mit ihren Operanden. |
| `guardChecks` | Jeder ausgeführte Guard-Test, auch bei Ablehnung oder anschließender Budgeterschöpfung. |
| `proofProduction` | Prüfung der Regelzeugen, strukturelle Vorprüfung, Aufbau und interne abgelöste Prüfung der erzeugten DAGs, einschließlich der verbrauchten Prüfarbeit bei einem abgebrochenen Versuch. |

Die Einheiten sind strukturelle Vorgänge im nativen Produzenten. Ein exakt
ausgewertetes Lemma zählt als ein Prüfvorgang zuzüglich der gemessenen
strukturellen Vorprüfung. Einzelne JVM-Operationen, Bitkosten exakter
Arithmetik und Bibliotheksinterna werden in Laufzeit und Allokation sichtbar.
Der beobachtete Produzent beginnt mit dem geparsten Ausdruck und dem
aufgebauten Annahmekontext, und endet mit den geprüften Extraktionen.
`proofReplayWork` zählt die anschließende unabhängige Diagnosewiederholung.
Sie bleibt neben der Summe sichtbar. Gleiches gilt für die Zeilenauswertung,
die Präfixmessungen und den separaten SymPy- und Solver-IR-Vergleich.
Matchversuche, direkte Regelvereinigungen und Guard-Ausgänge bleiben
als primitive Ergebnisse im Regelbuch verfügbar.

Jede Umgebung hält ihre eigenen Laufzeiten und die vom JVM-Zähler
verfügbaren Allokationsbytes des ausführenden Threads fest, zusammen mit
JVM-Version, Betriebssystem, Architektur, CPU-Anzahl, Heap-Grenze und
gegebenenfalls der OCI-Image-ID. Die Einzelmessungen dienen der Diagnose.
Die CI aktiviert die Erhebung für beide Quellarchive und das OCI-Image.
Lokal aktiviert `REGELSUCHE_RUN_SCALAR_DIAGNOSTICS=1` dieselbe Erhebung
beim Aufruf des Reproduktionsskripts.
Die kanonischen Vergleichsdateien und Zertifikate werden weiterhin separat
auf Bytegleichheit geprüft.

Der SymPy-Arm erhält den aufgabenspezifischen Operationsnamen und dieselbe
deklarierte Voraussetzung. Der Adapter überträgt `^` nach `**` und bewertet
das Ergebnis in der gemeinsamen Teilmenge aus exakten Skalartermen und
Addition/Multiplikation modulo AC. Geordnete AST-Positionen, das native
Regelinventar und native Beweisobjekte werden dabei nicht übertragen.
Ohne belegte Nichtnullbedingung meldet der SymPy-Adapter einen offenen
Guard. SymPy gibt den Ergebnistext zurück. BestFirst verwendet gerichtete
primitive Übergänge, deren Erreichbarkeit und Zustandszahl der Bericht
erfasst. Die native Sättigung hält einen empfängerprüfbaren DAG fest.
Der [Track E2](https://github.com/carstenartur/Regelsuche/issues/235)
dokumentiert die komplementäre Übersetzungs- und Beweisobjektprüfung im
gemeinsamen unbedingten Polynomfragment.
