# Nachprüfung von Rohdatenbindung und Quellvariablen

Ein separates Code-Review hat zwei Lücken in den Prüfgrenzen des Piloten
nachgewiesen. Beide sind korrigiert. Die eingefrorenen Quellen, das
Originalergebnis und sämtliche bisherigen Rohbelege bleiben unverändert;
die zwei Gegenproben betreffen keine Quelle der zehnteiligen Messung.
Dies ist eine zusätzliche projektinterne Prüfung, keine externe unabhängige
Anwendungsstudie.

## Korrekturen

**Rohdatenbindung.** Bisher prüfte der Auditor die im Manifest aufgeführten
Dateihashes und verglich die eingebetteten Ausgabewerte aus `results.json`
mit dem Quellorakel. Ein separat verändertes C-stdout mit neu berechnetem
Manifesthash konnte dennoch unbemerkt der Ergebniskopie widersprechen.
Dasselbe galt für die erhaltene native Antwort.

Der Replay liest jetzt die zwölf C-stdout-Dateien in der festgelegten
Längen-/Seed-Reihenfolge erneut ein. Er vergleicht diese Werte sowohl mit der
Ergebnismatrix als auch mit der unabhängigen Auswertung des Quellausdrucks.
Jede eingebettete native Antwort wird vollständig mit ihrer Datei
`native.json` verglichen. Diese Dateien müssen außerdem im Manifest
aufgeführt sein; die vorhandene Hashprüfung bleibt bestehen.

**Quellvariablen.** Die bisherige textuelle Übersetzung von `X[j]` nach `x0`
unterschied nicht, ob der interne Name erst durch die Übersetzung entstand
oder bereits undeclariert in der Alpha-Quelle stand. Der Import prüft jetzt
alle ursprünglichen Bezeichner-/Zugriffstoken vor dieser Umbenennung gegen
die tatsächlich deklarierten Eingabezugriffe. Bare Namen wie `x0` und
Mischungen wie `X[j]+x0` werden abgewiesen. Ein korrekt deklarierter
Reduktionsindex namens `x0`, verwendet als `X[x0]`, bleibt gültig.

## Tatsächlich ausgeführte Regressionstests

Vor der Audit-Korrektur scheiterten drei neue Tests mit den erwarteten
Assertion-Fehlern, ohne Testausführungsfehler:

- Ein C-Rohlog wurde von `-9, -1` nach `999, -1` geändert und sein Manifesthash
  neu berechnet. Die alte Prüfung akzeptierte es.
- Der erste Wert in `native.json` wurde auf `999` geändert und der Hash
  aktualisiert. Auch dies akzeptierte die alte Prüfung.
- Der erforderliche Manifesteintrag eines Rohoutputs wurde entfernt. Die alte
  Prüfung akzeptierte das unvollständige Manifest.

Nach der Audit-Korrektur bestanden diese Tests. Danach scheiterte der neue
Quellbindungstest auf dem noch unkorrigierten Import: Beide illegalen
Ausdrücke `x0` und `X[j]+x0` wurden akzeptiert. Die anschließende Tokenprüfung
behebt beide Fälle.

Der abschließende Maven-Lauf führte **34 Tests erfolgreich aus**: 20 Tests der
geordneten Suche und Auswertung, drei SDK-Tests und elf Experimenttests.
Ein weiterer konditionaler Test, der den vollständigen Originalcompilerlauf
starten würde, war bewusst nicht aktiviert. Es gab keine Fehler. Die
bestehenden Java-Deprecation-Hinweise im Compilerlauf bleiben im Log sichtbar.

Der neue Replay lief auf einer frisch entpackten Kopie des versionierten
`evidence.tar.gz`. Er akzeptiert alle unveränderten Originalbelege und weist
die neuen Manipulationen ab. Die bereits gemessenen Quellen durchlaufen
denselben zulässigen Übersetzungspfad; es wurden keine Messquelle, kein
nativer Plan und keine Originalcompiler-Ausgabe ersetzt. Deshalb wurde der
volle Originalbenchmark nicht erneut ausgeführt, und es werden keine neuen
Synthese- oder Laufzeitmessungen behauptet.

## Reproduktion

Im Checkout mit Java 25 und Maven:

```sh
audit_dir=$(mktemp -d)
tar -xzf docs/research/external-polyhedral-v1/evidence.tar.gz -C "$audit_dir"
mvn -pl regelsuche-experiments -am \
  -Dtest=ExternalPolyhedralComparisonTest,OrderedReductionPrefixTest,OrderedReductionSearchTest,OrderedBlockReductionTest,OrderedBlockReductionComparisonTest,OrderedReductionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexternalPolyhedralAudit="$audit_dir/regelsuche-polyhedral-v1-final" test
```

Der tatsächliche Lauf verwendete den lokalen Java-25-/Maven-Wrapper und
`/tmp/peer-review-fix.XJyp4s/regelsuche-polyhedral-v1-final`. Für das Produkt
und den obigen Reproduktionsweg ist kein Python erforderlich.

| Neuer Prüfbeleg | Inhalt |
| --- | --- |
| [review-audit-red.log.gz](review-audit-red.log.gz) | Drei erwartete rote Audit-Regressionen vor der Korrektur |
| [review-source-red.log.gz](review-source-red.log.gz) | Roter Quellbindungstest mit zwei fehlenden Zurückweisungen |
| [review-fixes-green.log.gz](review-fixes-green.log.gz) | Erfolgreicher gemeinsamer Lauf mit 34 ausgeführten Tests |

Die geprüften Java-Dateien sind zusätzlich durch ihren SHA-256 gebunden:

| Datei | SHA-256 |
| --- | --- |
| `regelsuche-experiments/src/main/java/de/regelsuche/benchmark/ExternalPolyhedralComparison.java` | `9bf9be018c098766f10a844752e715fdf737014a62e68b5c75e43ceecc3c4cc9` |
| `regelsuche-experiments/src/test/java/de/regelsuche/benchmark/ExternalPolyhedralComparisonTest.java` | `73d3cea703c0c0dafcab0351be7071b1868503eea4412eeabafa02f9dbfde9ad` |

Die im Hauptdokument angegebenen SHA-256-Werte für Freeze, Ergebnismatrix,
Originalarchiv und Messquellstand-Bundle sind erneut geprüft und unverändert.
Das Bundle bewahrt weiterhin den ursprünglichen Messquellstand; die spätere
Auditor-/Importkorrektur wird nicht rückwirkend als damals verwendeter Code
ausgegeben.

## CI-Kontrolle der Methodenkomplexität

Die reguläre CI des Piloten wies die erweiterte `audit`-Methode als neuen
Komplexitätsschwerpunkt zurück (kognitiv 61, zyklomatisch 30). Die Prüfung ist
jetzt in Manifestbindung, Fallinventar, einzelne Ergebniszeilen und die beiden
Rohoutputpfade gegliedert. Alle bisherigen Prüfschritte bleiben erhalten.
Weder Hotspot-Policy noch Baseline oder Schwellenwerte wurden geändert.

Die unveränderte AST-Messung des gepinnten Extractors v0.1.10 reproduziert die
ursprünglichen 61/30 exakt. Nach der Zerlegung liegen sämtliche Methoden
dieser Klasse höchstens bei 26/20; die neue Zeilenprüfung liegt bei 12/11.
Das ist eine gezielte Methodenmessung, kein Ersatz für die vollständige CI.

Der erneut aktivierte Archiv-Replay und seine Manipulationskontrollen bestehen:
elf ausgeführte Tests, null Fehler, ein bewusst nicht gestarteter vollständiger
Originalcompilerlauf. Die historischen Messquellen, Ergebnisdateien und
Rohbelege bleiben bytegleich. Die Hashangaben des vorherigen Abschnitts
bezeichnen weiterhin den damaligen Reviewstand vor dieser reinen Zerlegung.

[Methodenmessung](audit-decomposition-metrics.json) ·
[Erneut ausgeführte Archivkontrollen](review-audit-decomposition-green.log.gz)
