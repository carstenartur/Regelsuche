# Direkter Vergleich mit dem originalen Alpha-Compiler

**Ein unverändertes fremdes Programm ist jetzt durch beide Verfahren tatsächlich
ausgeführt und gemeinsam geprüft:** `prefix_sum` aus dem öffentlichen Artefakt
zu *Maximal Simplification of Polyhedral Reductions*. Beide Verfahren liefern
auf zwölf festgelegten ganzzahligen Eingabefolgen dieselben 99 Präfixwerte.
Regelsuche weist die übrigen sechs fremden Programme an der gegenwärtigen
Importschnittstelle als nicht unterstützt aus. Dieser enge Befund ist kein
Nachweis allgemeiner Überlegenheit, Skalierung oder neuer Präfixalgorithmen.

## Quellen und vorherige Festlegung

Der Gegner ist der unveränderte [Originalcompiler von Narmour, Yuki und
Rajopadhye](https://github.com/lnarmour/max-simplification-artifact), Commit
`d91815edbfb0463c1b195173dbff46a4a2e52f39`. Die beiden verteilten JARs und
alle sieben Dateien in `inputs/` sind mit SHA-256 im
[Protokoll](../../../config/benchmarks/external-polyhedral-v1.json) gebunden.
Das ist das POPL-2025-Nachfolgeartefakt; es wird nicht als identische
Implementierung des zuvor diskutierten
[Preprints von 2024](https://arxiv.org/html/2411.17498v1) ausgegeben.

Der Fallplan wurde vor Implementierung und Vergleich eingefroren. Sein SHA-256
ist `d87c7ada9ccf9e5c2b1b270ba6a4c1f987feb45c8572b5540ca30d00e24cd46c`.
Der vorangegangene Installationslauf auf `prefix_sum` ist ausdrücklich
offengelegt. Es handelt sich um einen öffentlichen Entwicklungspiloten,
nicht um einen geschützten Test. Alle drei zusätzlich konstruierten Kontrollen
sind als `PROJECT_AUTHORED_CONTROL` gekennzeichnet und erhöhen die Zahl fremder
Fälle nicht. [Vertrag, Grenzen und nachträgliche Semantikkorrektur](protocol.md)

Die Herkunft der sieben Programme ist außerhalb von Regelsuche. Die Auswahl
und Bewertung dieses Piloten erfolgte dagegen im Projekt. Weder unabhängige
externe Begutachtung noch eine industrielle Anwendung werden daraus abgeleitet.

## Vollständiges Ergebnis

| Unverändertes fremdes Programm | Regelsuche | Originalcompiler | Gemeinsame Ergebnisprüfung |
| --- | --- | --- | --- |
| `example_3d_max` | Import nicht unterstützt | Code erzeugt | Außerhalb des gemeinsamen Fragments |
| `example_3d_sum` | Import nicht unterstützt | Code erzeugt | Außerhalb des gemeinsamen Fragments |
| `example_4d` | Import nicht unterstützt | Zeitgrenze erreicht | Kein qualifizierter gemeinsamer Ausgang |
| `increasing_max_filter` | Import nicht unterstützt | Code erzeugt | Außerhalb des gemeinsamen Fragments |
| `prefix_max` | Import nicht unterstützt | Code erzeugt | Außerhalb des gemeinsamen Fragments |
| `prefix_sum` | Geprüfter Präfixplan | Vereinfachter C-Code | Beide: 99/99 gleiche exakte Werte |
| `working_example` | Import nicht unterstützt | Code erzeugt | Außerhalb des gemeinsamen Fragments |

„Code erzeugt“ bedeutet hier nicht, dass diese fünf zusätzlichen Programme
durch den gemeinsamen Auswerter validiert wurden. Der Zeitabbruch widerlegt
keine mögliche Vereinfachung. „Import nicht unterstützt“ beschreibt diese
Schnittstelle und ist keine Unmöglichkeitsaussage über alle nativen Verfahren.

| Projektkontrolle | Beobachtung und Folgerung |
| --- | --- |
| `prefix_polynomial` | Native 99 Werte geprüft. Der Originalcompiler endet im Frontend mit einem ISL-Kontextfehler und einer NullPointerException, bevor ein C-Kandidat vorliegt. Kein direkter Leistungsvergleich. |
| `pair_prefix_product` | Die ursprüngliche Alpha-Quelle lässt bei `i=0` eine leere Reduktion zu, die der Originalloader als undefiniert zurückweist. Der korrigierte Import weist sie ebenfalls ab. Die mathematische native API darf daraus weiterhin eine leere Summe mit Wert null bilden, aber der Alpha-Adapter ergänzt diesen Wert nicht ungefragt. |
| `variable_denominator` | Außerhalb des gemeinsamen ganzzahligen Polynomfragments; der Originallauf endet ebenfalls ohne qualifizierten Kandidaten. |

Die beiden zunächst als Erweiterung gedachten Kontrollen liefern somit keinen
zusätzlichen erfolgreichen direkten Vergleich. Die vollständige erste Fassung
und eine zusätzliche Gruppierungsdiagnose bleiben erhalten. Insbesondere wird
kein Original-Frontendfehler als bessere Regelsuche-Suchleistung gezählt.

Insgesamt stehen **20 Ergebniszeilen für zehn Quellen und zwei Verfahren** in
den Rohdaten. Es gibt eine Messwiederholung; weder Zeilen noch die 99 Werte sind
99 unabhängig ausgewählte Aufgaben. [Maschinenlesbares Endergebnis](results.json)

## Was sich im Produktkern geändert hat

`OrderedReduction` akzeptiert jetzt eine bis acht geordnete Zeilenbindungen.
Eine einzelne Bindung durchläuft dieselbe exakte Polynomnormalisierung,
Planableitung und unabhängige Koeffizientenprüfung wie die vorhandenen
Mehrfachbindungen. `Verified.evaluatePrefixes` gibt den bereits berechneten
laufenden Tupelgesamtwert nach jeder Zeile zurück. Die dünne öffentliche
SDK-Methode `OrderedReductionSdk.evaluatePrefixes` delegiert diesen Pfad.

Die Prüfung bindet den Plan weiterhin an den ursprünglichen Ausdruck und
dessen Variablen. Die bestehende Auswertung für eine Gesamtsumme und für
Fenster bleibt erhalten. Einbindige Punktänderungspläne werden ausdrücklich
abgewiesen, weil dieser vorhandene Blockpfad mehrbindige Quellen voraussetzt.

Der Alpha-Adapter liest die gesamte deklarierte Programmform einschließlich
Parameter-, Eingabe- und Ausgabebereich. Er übersetzt den Summanden und die
Zeilenbindung; eine fertige Präfixantwort oder ein gewünschter Zielausdruck
geht nicht an die Suche. Die originale `prefix_sum`-Quelle wird unverändert
weitergereicht. Die Methode selbst ist die bekannte Präfixaggregation und
wird nicht als neuer Algorithmus bezeichnet.

```java
var scope = new SymbolScope();
var x = scope.declare("x");
var source = new OrderedReduction(List.of(List.of(x)), List.of(),
    SymbolicExpression.parse("x*x", scope).expression());
var budget = OptimizationBudget.DEFAULT;
var checked = OrderedReductionSdk.search(source, budget, CancellationToken.NONE)
    .verified().orElseThrow();
var rows = List.of(List.of(BigInteger.valueOf(2)),
    List.of(BigInteger.valueOf(-3)), List.of(BigInteger.valueOf(4)));
var result = OrderedReductionSdk.evaluatePrefixes(checked, rows,
    budget, CancellationToken.NONE);
// result.sums(): [4, 13, 29]
```

## Prüfung und Kosten

Die gemeinsame Referenz wertet den ursprünglichen Ausdruck mit `BigInteger`
aus und enumeriert für jedes Präfix die zulässigen Tupel. Sie verwendet weder
den nativen Plan noch den erzeugten C-Code. Die Werte sind
`((index*17 + seed*7) % 19) - 9`, mit Längen 2, 3, 8 und 20 sowie drei Seeds.
Der Alpha-Parameter ist jeweils `N = Länge - 1`, sodass `N > 0` erhalten bleibt.
Konservative Schranken sichern die Quellarithmetik und direkten Summen ab.
Die C-Ausführungen prüfen zusätzlich alle ausgeführten ganzzahligen
Zwischenschritte mit `-fsanitize=undefined -fno-sanitize-recover=all` und
verlangen 64-Bit-`long`. Der Befund ist keine IEEE-Gleitkommafreigabe und kein
allgemeiner Beweis jedes erzeugten C-Programms.

Die Umgebung war Java `25.0.2+10-LTS`, GCC `14.2.0`, Linux amd64 `6.18.57`.
Die Protokollgrenzen betragen 30 Sekunden für Synthese, 15 für Kompilierung
und fünf pro C-Ausführung. Jeder Java-Unterprozess erhält 512 MiB Heap.
Das begrenzt nicht den gesamten nativen/JNI-Speicher. Die ursprünglichen
Werkzeugdownloads sind ungemessen und werden nicht als kostenloser Erwerb
ausgegeben. Die gemeinsame Quellenprüfung dauerte rund 0,962 Sekunden.

Für `prefix_sum` verbraucht der native Pfad 42 gezählte Einheiten für Suche
einschließlich ihrer Prüfungen, elf für den zusätzlichen Plan-Replay und 729
für die zwölf Auswertungen: insgesamt 782. Die Originalcompilerarbeit ist
nicht in vergleichbaren Struktureinheiten instrumentiert.

Die getrennten Zeitdiagnosen betragen rund 0,393 Sekunden für den nativen
Unterprozess, 7,542 Sekunden für die originale Codeerzeugung, 0,155 Sekunden
für die C-Kompilierung und zusammen 0,049 Sekunden für die zwölf separaten
C-Ausführungen. Dies sind verschiedene Phasen und Ausgabemodelle. Es gab
frische Unterprozesse, aber keinen Reset der Betriebssystemcaches; zudem liefen
andere Entwicklungsarbeiten. Daraus wird kein relatives Laufzeitverhältnis
oder Skalierungsvorteil abgeleitet. Das gemeinsame Referenzergebnis wurde pro
Quelle einmal berechnet und für beide Ergebnisprüfungen wiederverwendet.

Der Prozesswächter beendet bei Abbruch die Prozessgruppe. Ein nach Fristende
beendeter Prozess qualifiziert auch bei Exitcode null nicht. Rohzeiten über
der nominellen Grenze bleiben sichtbar. Die Archivprüfung kontrolliert
zusätzlich die vollständige Matrix, Quellenhashes, gespeicherte Werte,
Fristen und native Arbeitsgrenzen. Die Integration prüft, dass eine geänderte
Quelle und ein gefälschter Ergebniswert auch dann verworfen werden, wenn der
Angreifer den Ergebnis-Dateihash im Manifest neu berechnet.

## Reproduktion

Voraussetzungen sind der unterstützte Java-25-/Maven-Aufbau, Linux, Git, GCC,
Make und GNU `timeout`. Der Pilot führt kein Python-Programm aus.

```sh
git clone https://github.com/lnarmour/max-simplification-artifact.git /tmp/alpha-peer
git -C /tmp/alpha-peer checkout --detach d91815edbfb0463c1b195173dbff46a4a2e52f39
mvn -pl regelsuche-experiments -am \
  -Dtest=ExternalPolyhedralComparisonTest,OrderedReductionPrefixTest,OrderedReductionSearchTest,OrderedBlockReductionTest,OrderedBlockReductionComparisonTest,OrderedReductionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexternalPolyhedralHome=/tmp/alpha-peer \
  -DexternalPolyhedralOutput=/tmp/new-polyhedral-run test
```

Das Ausgabeverzeichnis muss neu sein. Ein vorhandener Lauf wird nicht
überschrieben. Beim aufgezeichneten Lauf wurden 30 Tests ausgeführt, ohne
Fehler; der ausschließlich über eine andere Property aktivierte Archivtest
war dabei übersprungen. Die Quellen- und Ergebnisfälschungen werden innerhalb
des tatsächlich ausgeführten Originalcompiler-Integrationstests kontrolliert.
[Vollständiges, unverändertes Maven-Protokoll (gzip)](maven-run.log.gz)

Der Archiv-Replay benötigt keinen Originalcompiler:

```sh
tar -xzf docs/research/external-polyhedral-v1/evidence.tar.gz -C /tmp
mvn -pl regelsuche-experiments -am \
  -Dtest=ExternalPolyhedralComparisonTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexternalPolyhedralAudit=/tmp/regelsuche-polyhedral-v1-final test
```

Dieser getrennte Replay wurde ebenfalls ausgeführt: sieben Tests bestanden,
der nur durch die Originalcompiler-Property aktivierte Integrationstest blieb
übersprungen. [Unverändertes Maven-Protokoll des Archiv-Replays (gzip)](maven-replay.log.gz)

Die ursprünglichen lokalen Messquellstände können bei einer Veröffentlichung
über eine Git-Daten-API andere öffentliche Commit-IDs erhalten. Deshalb sind
die Nachweise an Dateiidentitäten gebunden. Das zusätzlich erhaltene
[Git-Bundle](source-history.bundle) rekonstruiert den tatsächlichen lokalen
Messstand `fbd93e57752fecf13256fffc1724738966f70aab` und seine Freeze-Vorfahren,
ausgehend von `f183aedd1ff2ab061ed903559b96a11d28974e7f`. Die Bundle-Prüfung
ist im Entwicklungscheckout erfolgreich ausgeführt worden:

```sh
git bundle verify docs/research/external-polyhedral-v1/source-history.bundle
git fetch docs/research/external-polyhedral-v1/source-history.bundle \
  refs/heads/codex/remediation-peer-20261124:refs/heads/reproduce-polyhedral-v1
```

## Erhaltene Artefakte

| Datei | Inhalt | SHA-256 |
| --- | --- | --- |
| [results.json](results.json) | Vollständige korrigierte Ergebnismatrix und Phasenmessungen | `67900ce209bf050c39d030041bdb4d3bf4cccefc0bebc47a547f64e57f07f0d9` |
| [evidence.tar.gz](evidence.tar.gz) | Original-Logs, Quellen, erzeugter C-Code, ausführbare Kandidaten und Dateimanifest | `e96d6ba9965f41742bf7c8b72076052fd78e22cd4f696c960e9b2ced85051281` |
| [source-history.bundle](source-history.bundle) | Lokale Freeze-, Implementierungs- und Korrekturcommits; keine externen JARs | `36c5070fcf1d9c629c8652985bac8d1c8be596ce0c185ec8a3762e7b0734c8fe` |

Die überholte Fassung bleibt als [Entwicklungsmatrix](development-results.json)
und [vollständiger Entwicklungslauf](development-run.tar.gz) erhalten. Sie
darf wegen der beschriebenen Alpha-Leerheitssemantik nicht als abschließender
Vergleich verwendet werden. Die [zusätzliche Gruppierungsdiagnose](grouping-diagnostic.tar.gz)
belegt, dass bloße Klammern den beobachteten polynomialen Frontendfehler nicht
beseitigten. Ruler/Enumo und AutoLifter sind keine Vergleichsarme dieses Piloten.

## Nachprüfung der Prüfgrenzen

Ein separates Code-Review fand zwei Lücken im Auditor und im Quellimport,
während die archivierte Ergebnismatrix selbst konsistent war. Der Replay
bindet nun die erhaltenen C-stdout-Dateien und `native.json` an die eingebetteten
Werte und verlangt ihre Manifesteinträge. Der Import prüft ursprüngliche
Variablenzugriffe vor der Einführung interner Symbolnamen.
[Korrekturen, rote Regressionstests und erneuter Archiv-Replay](review-addendum.md)
