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
