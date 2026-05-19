# Regelsuche

[![CI/CD](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/coverage/badge.json)](https://carstenartur.github.io/Regelsuche/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/tests/badge.json)](https://carstenartur.github.io/Regelsuche/tests/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Regelsuche/dependency-graph/sbom)
[![GitHub release](https://img.shields.io/github/v/release/carstenartur/Regelsuche?style=flat-square)](https://github.com/carstenartur/Regelsuche/releases)

Gradle-basiertes Java-Projekt für regelbasierte Ausdrucksumformungen mit:

- Eingabe von Termen, Gleichungen und Gleichungssystemen
- Parsing in einen abstrakten Syntaxbaum (AST)
- AST-Rewrite-Engine mit lokalen, strukturellen Umformungsregeln
- Optionale SymPy-Integration über GraalVM Polyglot als zusätzliche Transformations- und Äquivalenzquelle
- Neo4j-Graphmodell (Knoten: Ausdrücke, Kanten: Umformungen)
- Heuristischer Suchbegrenzung (Suchtiefe, besuchte Ausdrücke)
- Hintergrundausführung der Umformungssuche
- Benachrichtigung bei deutlich besseren Vereinfachungen
- Abfrage des aktuellen Graphzustands und der besten gefundenen Lösung
- Generator für konkrete algebraische Beispiele mit kleinen Integer-Parametern
- Mining von Regel-Kandidaten per AST-Normalisierung, Anti-Unification und Parameter-Relationen
- Referenzbestand bekannter Regeln zum Einordnen gefundener Kandidaten

## Starten

```bash
./gradlew :app:run --args='term "x + 0"'
```

## Tests

```bash
./gradlew test
```

Die Tests dokumentieren bewusst die Stärken des AST-Ansatzes:

- `AstRewriteTransformationEngineTest` zeigt lokale Rewrites an beliebigen und verschachtelten Teilbäumen.
- `ExpressionParserTest` schützt Operatorpräzedenz und Formatierung, insbesondere Unary-Minus und verschachtelte Potenzen.
- `RuleCandidateMinerTest` prüft, dass Kandidaten aus validierten Pfaden und nicht aus bekannten Regelnamen entstehen.
- `AppTest` deckt robuste CLI-Fehlerbehandlung ab.

Optionaler Neo4j-Store per Umgebungsvariablen:

- `NEO4J_URI`
- `NEO4J_USER`
- `NEO4J_PASSWORD`

## Eingabe von Ausdrücken

Die Anwendung akzeptiert Terme, Gleichungen und Gleichungssysteme als Strings.
Terme nutzen explizite Multiplikation, z. B. `x^2 + 2*x + 1`.
Gleichungen werden mit `=` getrennt, Gleichungssysteme mit `;` oder Zeilenumbrüchen.

## AST-Rewrite-Engine

Die Klasse `AstRewriteTransformationEngine` implementiert `TransformationEngine`
und wendet `RewriteRule`-Instanzen rekursiv auf jeden Teilbaum eines Ausdrucks
an. Dadurch entstehen Suchgraph-Kanten aus allgemeinen lokalen Regeln, statt aus
hart codierten quadratischen Sonderfällen.

Das Pattern-System bindet strukturelle Platzhalter an beliebige AST-Teilbäume:

- `PatternExpr.var("A")` bindet einen Teilbaum und erzwingt bei Wiederholung dieselbe Struktur.
- `PatternExpr.op(ADD, A, B)` beschreibt Operator-Muster.
- `PatternRewriteRule` instanziiert Zielmuster aus den gefundenen Bindings.

Aktuell enthaltene Basisregeln sind absichtlich atomar. Es gibt keine direkte
Schulbuchregel für binomische Formeln, Differenz von Quadraten oder quadratische
Ergänzung. Erlaubte Spielzüge sind z. B.:

- `A^2 -> A*A`
- `A*(B + C) -> A*B + A*C`
- `(B + C)*A -> B*A + C*A`
- `A*B + A*C -> A*(B + C)`
- `A + A -> 2*A`
- `A*A -> A^2`
- `(A^m)*(A^n) -> A^(m+n)`
- `(A^m)^n -> A^(m*n)`
- neutrale und absorbierende Elemente wie `A + 0`, `A * 1`, `A * 0`

Diese Regeln gelten für beliebige AST-Teilbäume, nicht nur für einzelne Variablen
oder Integer. Bekannte Formeln dürfen nicht als Spielzüge eingebaut sein; sie
müssen als Muster aus vielen kleinen allgemeinen Spielzügen entstehen. Beispiel:

```text
(x+a)^2
-> (x+a)*(x+a)
-> x*(x+a) + a*(x+a)
-> x*x + x*a + a*x + a*a
-> x^2 + 2*a*x + a^2
```

`RewriteRule` trägt Metadaten (`RewriteKind`, Komplexitätsrisiko,
geschätztes Kosten-Delta und Äquivalenzstatus), damit die Suche expandierende
oder potenziell teure Regeln gezielt begrenzen kann. Quadratische Analyzer dürfen
weiterhin für Scoring, Äquivalenz-Fallbacks, Tests und bekannte Baselines
existieren. Sie werden aber nicht als direkte Transformationslogik verwendet.

## Suche und Graph

Die Transformationssuche erzeugt aus jedem Ausdruck Folgezustände primär durch
lokale AST-Rewrite-Regeln. Regeln werden nicht nur am Wurzelausdruck, sondern
rekursiv an jedem Teilbaum ausprobiert. Jeder Ausdruckszustand wird als Knoten
gespeichert, jede angewendete Umformung als gerichtete Kante mit Regelname,
Tiefe und Score-Verbesserung. Der Graph speichert konkrete Suchpfade und nicht
nur Endergebnisse.

Die Suche läuft über eine `SearchStrategy`-Schnittstelle. Implementiert sind
`BestFirstSearchStrategy` und `BeamSearchStrategy`; einfache BFS ist nicht mehr
die zentrale Suchlogik. Ein Zustand enthält Ausdruck, Tiefe, Score, Pfad,
angewendete Regel-IDs, Anzahl expandierender Schritte und kanonischen Hash.
Bewertet werden u. a. Ausdruckskomplexität, AST-Größe, Operatoranzahl,
Verschachtelung, bisherige Tiefe, expandierende Schritte und Expansion ohne
Verbesserung.

Der Suchraum wird durch Heuristiken begrenzt: maximale Suchtiefe, maximale Anzahl
besuchter Strukturen, maximale AST-Größenzunahme pro Schritt, keine Wiederholung
derselben Regel auf demselben kanonischen Teilbaum im selben Pfad, maximale
Anzahl expandierender Schritte und maximale Kandidaten pro Zustand. Das ist
notwendig, weil algebraisch äquivalente Umformungen sehr schnell zyklische oder
exponentiell wachsende Suchräume bilden.
Der Speicher kann lokal im Arbeitsspeicher oder über Neo4j erfolgen.


## Normalform und Kanonisierung

`ExpressionCanonicalizer` vereinheitlicht Ausdrücke für `visited`-Mengen,
Anti-Zyklen und Graph-Vergleiche:

- kommutative Operatoren werden stabil sortiert (`b+a == a+b`)
- verschachtelte Additionen und Multiplikationen werden geflattet
- neutrale Elemente werden entfernt (`x*1 == x`)
- Zahlen werden normalisiert
- wiederholte Faktoren werden zusammengeführt (`x*x == x^2`)
- stabile Hashes markieren bereits gesehene Strukturen

Die Kanonisierung ersetzt keine Regelentdeckung. Sie kontrolliert Suche und
Duplikate; die konkreten Graph-Kanten bleiben die kleinen Rewrite-Schritte.

## Bewertung und Äquivalenz

Ausdrücke werden anhand einer Score-Struktur bewertet:

- String-Länge
- AST-/Token-Knoten
- Operatoranzahl
- Verschachtelungstiefe
- Bonus für erkannte Strukturen wie Quadrat-, Produkt- oder Faktorform

Äquivalenz wird über SymPy geprüft (`simplify(lhs - rhs) == 0`). Die
GraalVM-Polyglot-Ausführung verwendet keinen Host-All-Access-Kontext; Eingaben
werden vor der Übergabe durch den eigenen Parser auf die unterstützte
Ausdrucksgrammatik begrenzt. Falls die Python-Laufzeit nicht verfügbar ist, gibt
es eine lokale Normalisierung für die unterstützten quadratischen Muster.

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
und Variablenstruktur.

Der aktuelle Mining-Ansatz besteht aus drei Schritten:

1. **AST-Normalisierung:** Quelle und Ziel jedes erfolgreichen Pfads werden
   geparst. Kommutative Operatoren wie `+` und `*` werden sortiert, neutrale
   Elemente entfernt, Variablennamen vereinheitlicht, Zahlenliterale
   normalisiert und daraus stabile kanonische Strings erzeugt.
2. **Anti-Unification:** `PatternGeneralizer` vergleicht mehrere konkrete
   AST-Paare desselben Strukturclusters und ersetzt abweichende Zahlen durch
   Platzhalter. So wird z. B. aus `(x + 1)^2`, `(x + 3)^2`, `(x + 5)^2`
   zunächst `(x + A)^2`.
3. **Parameter-Relation-Mining:** `ParameterRelationMiner` erkennt einfache
   numerische Beziehungen zwischen Platzhaltern, z. B. `B = 2*A`,
   `C = A^2` oder `C = -A^2`. Daraus entsteht ein Kandidat wie
   `x^2 + 2*A*x + A^2 -> (x + A)^2`.

Ein Cluster wird erst dann zu einem Regelkandidaten, wenn mindestens drei
konkrete Beispiele dieselbe abstrakte Struktur und dieselben
Parameterbeziehungen unterstützen. Danach erzeugt das System frische
Testinstanzen mit bisher nicht genutzten Zahlenwerten und prüft sie mit dem
`EquivalenceService`. Kandidaten, die diese Validierung nicht bestehen, werden
verworfen.

Anti-Duplikation erfolgt durch kanonisierte Variablennamen und einen Hash des
abstrahierten Musters. Wenn ein neuer Kandidat entdeckt wird, erzeugt die
asynchrone Suche ein `RuleCandidateDiscoveredEvent`.

Wichtig ist die klare Trennung:

- **Rewrite-Regeln** sind kleine erlaubte Spielzüge.
- **Suchstrategie** erzeugt konkrete Pfade aus diesen Spielzügen.
- **RuleCandidateMiner** abstrahiert erfolgreiche Pfade per Normalisierung,
  Anti-Unification, Parameter-Relationen und frischer Validierung.
- **KnownRuleRepository** ordnet Kandidaten nur ein; es entdeckt und löst nichts.
- **Regelkandidat** ist ein validiertes, plausibles abstrahiertes Muster.
- **Bewiesene mathematische Regel** erfordert weiterhin einen separaten Beweis;
  die Suche ist heuristisch, nicht vollständig, und garantiert keine
  mathematische Neuheit oder Vollständigkeit.

## Bekannte Regeln als Referenz

Bekannte Regeln liegen als Baseline vor, damit gefundene Kandidaten eingeordnet
werden können, z. B. als bekannte binomische Formel. Sie werden nicht als
direkter Suchschritt verwendet.

**Die bekannten Regeln dürfen als Test- und Referenzbestand existieren, aber
nicht als direkte Suchlösung verwendet werden.**

**Keine Regel darf durch ihren bekannten Namen oder durch eine Spezialmethode
erkannt werden; sie muss aus konkreten Transformationspaaren generalisiert und
an neuen Beispielen validiert werden.**

Das System kann Regel-Kandidaten plausibel rekonstruieren, garantiert aber
nicht, dass ein Kandidat mathematisch neu oder vollständig allgemein bewiesen
ist. Die Baseline dient nur dem Vergleich und der Statusmeldung.
