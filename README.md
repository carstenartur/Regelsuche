# Regelsuche

Gradle-basiertes Java-Projekt für regelbasierte Ausdrucksumformungen mit:

- Eingabe von Termen, Gleichungen und Gleichungssystemen
- Parsing in einen abstrakten Syntaxbaum (AST)
- SymPy-Integration über GraalVM Polyglot (mit lokalem Fallback)
- Neo4j-Graphmodell (Knoten: Ausdrücke, Kanten: Umformungen)
- Heuristischer Suchbegrenzung (Suchtiefe, besuchte Ausdrücke)
- Hintergrundausführung der Umformungssuche
- Benachrichtigung bei deutlich besseren Vereinfachungen
- Abfrage des aktuellen Graphzustands und der besten gefundenen Lösung
- Generator für konkrete algebraische Beispiele mit kleinen Integer-Parametern
- Mining von Regel-Kandidaten aus wiederkehrenden erfolgreichen Umformungspfaden
- Referenzbestand bekannter Regeln zum Einordnen gefundener Kandidaten

## Starten

```bash
./gradlew :app:run --args='term "x + 0"'
```

Optionaler Neo4j-Store per Umgebungsvariablen:

- `NEO4J_URI`
- `NEO4J_USER`
- `NEO4J_PASSWORD`

## Eingabe von Ausdrücken

Die Anwendung akzeptiert Terme, Gleichungen und Gleichungssysteme als Strings.
Terme nutzen explizite Multiplikation, z. B. `x^2 + 2*x + 1`.
Gleichungen werden mit `=` getrennt, Gleichungssysteme mit `;` oder Zeilenumbrüchen.

## Suche und Graph

Die Transformationssuche erzeugt aus jedem Ausdruck Folgezustände, etwa durch
Expandieren, Faktorisieren, Sortieren, Zusammenfassen oder quadratische
Ergänzung. Jeder Ausdruckszustand wird als Knoten gespeichert, jede angewendete
Umformung als gerichtete Kante mit Regelname, Tiefe und Score-Verbesserung.
Der Speicher kann lokal im Arbeitsspeicher oder über Neo4j erfolgen.

Der Suchraum wird durch Heuristiken begrenzt, insbesondere maximale Suchtiefe
und bereits besuchte Ausdrücke. Das ist notwendig, weil algebraisch äquivalente
Umformungen sehr schnell zyklische oder exponentiell wachsende Suchräume bilden.

## Bewertung und Äquivalenz

Ausdrücke werden anhand einer Score-Struktur bewertet:

- String-Länge
- AST-/Token-Knoten
- Operatoranzahl
- Verschachtelungstiefe
- Bonus für erkannte Strukturen wie Quadrat-, Produkt- oder Faktorform

Äquivalenz wird über SymPy geprüft (`simplify(lhs - rhs) == 0`). Falls die
Python-Laufzeit nicht verfügbar ist, gibt es eine lokale Normalisierung für die
unterstützten quadratischen Muster.

## Regel-Kandidaten

Der Beispielgenerator erzeugt viele konkrete quadratische Testausdrücke, z. B.:

- `(x + a)^2`
- `(x - a)^2`
- `(x + a)*(x - a)`
- `x^2 + b*x + c`
- `a*x^2 + b*x + c`
- `x^2 + 2*a*x + a^2`
- `x^2 - 2*a*x + a^2`
- `x^2 + 2*a*x`
- `x^2 - 2*a*x`

Für erfolgreiche, äquivalente und besser bewertete Umformungspfade speichert das
System Ausgangsausdruck, Zielausdruck, Pfad, Regeln, Scores, Äquivalenznachweis
und Variablenstruktur. `RuleCandidateMiner` vergleicht mehrere konkrete Treffer
und abstrahiert daraus parametrisierte Muster, z. B.
`x^2 + 2*a*x + a^2 -> (x + a)^2`.

Anti-Duplikation erfolgt durch kanonisierte Variablennamen und einen Hash des
abstrahierten Musters. Wenn ein neuer Kandidat entdeckt wird, erzeugt die
asynchrone Suche ein `RuleCandidateDiscoveredEvent`.

## Bekannte Regeln als Referenz

Bekannte Regeln liegen als Baseline vor, damit gefundene Kandidaten eingeordnet
werden können, z. B. als bekannte binomische Formel. Sie werden nicht als
direkter Suchschritt verwendet.

**Die bekannten Regeln dürfen als Test- und Referenzbestand existieren, aber
nicht als direkte Suchlösung verwendet werden.**

Das System kann Regel-Kandidaten plausibel rekonstruieren, garantiert aber
nicht, dass ein Kandidat mathematisch neu oder vollständig allgemein bewiesen
ist. Die Baseline dient nur dem Vergleich und der Statusmeldung.
