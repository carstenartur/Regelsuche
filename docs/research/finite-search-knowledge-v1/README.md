# Begrenztes wiederverwendbares Suchwissen

Der vor dem vollständigen Lauf festgelegte [Plan](plan.json) untersucht dieselben
vier reversiblen Faktor-/Distributionsregeln wie der bestehende Referenzvertrag.
Alle 13 historischen Syntaxziele bleiben erhalten; Sophie Germain behält seine
ursprüngliche wertbezogene Zielrelation und wird nicht als Syntaxfall umetikettiert.
Drei zusätzlich festgelegte Tupel mit drei, fünf und sieben unabhängigen
Faktorstellen untersuchen größere Tiefe. Der vollständige native Abschluss
liefert für jeden der 16 Fälle die Referenz; zehn Verfahren ergeben 160 Zeilen.
Dies sind öffentlich zielgerichtete Diagnosen, keine verborgene Entdeckungsstudie.

## Tatsächliche Ergebnisse

| Verfahren | Erreichte Fälle / 16 | Native Aufrufe insgesamt |
|---|---:|---:|
| Einseitige Breitensuche | 6 | 1.278 |
| Bidirektionale Suche | 6 | 688 |
| Ungeführte iterative Vertiefung | 5 | 5.104 |
| Geführte Vertiefung, ein Tabelleneintrag | 5 | 5.048 |
| Geführte Vertiefung, 128 Einträge | 5 | 1.214 |
| Portfolio, ein Worker | 6 | 4.339 |
| Portfolio, drei Worker | 6 | 4.339 |
| Tabelle mit erstmaligem Erwerb | 6 | 1.370 |
| Tabelle mit unabhängiger Wiederherstellung | 6 | 1.370 |
| Bereits erworbene Tabelle, Anfrage und frische Nachprüfung | 6 | 105 |

Alle Verfahren erhalten höchstens 4.096 native Aufrufe pro Fall. Das Portfolio
teilt diese Grenze in drei vorab reservierte Kontingente zu je 1.365 Aufrufen.
Auch erfolglose Mitglieder werden vollständig berechnet. Geführte Vertiefung
und Tabellenanfragen besitzen zusätzlich eine getrennte Controllergrenze.
Die unveränderten Ergebniszeilen und vollständig rekonstruierbaren Quelltabellen
stehen in [comparison.json](comparison.json); [run.json](run.json) bindet
Quellcommit, Dateibaum und Prüfsummen.

Beim Siebenstellenfall erreicht die Breitensuche das Ziel in sieben primitiven
Schritten mit 1.024 Aufrufen, die bidirektionale Suche mit 528. Die Tabelle
benötigt erstmalig 1.016 Aufrufe zur Erzeugung und anschließend 56 zur frischen
Pfadprüfung, zusammen 1.072. Ein erneuter warm ausgeführter Zugriff benötigt
wieder 56 Aufrufe und 1.024 ausgewiesene Lookup-Beobachtungen. Der Erwerb ist
kein kostenloser Bestandteil des warmen Vergleichs. Der gespeicherte Inhalt
enthält seine Quellen, Inventarbindung, exakten Entfernungen und jede Kante;
Wiederladen regeneriert ihn vollständig.

Der größere iterative Cache senkt in diesem Fall die nativen Aufrufe auf 960,
erreicht aber nach 262.144 Controlleroperationen seine ausdrücklich erklärte
Grenze. Es wird kein Pfad veröffentlicht. Der kleine Cache und die ungeführte
Variante verbrauchen ihre 4.096 Aufrufe ebenfalls ohne fertigen Beleg. Die
Controllerarbeit ist deshalb ein konkreter verbleibender Engpass. Eine
Pruning-Regel, die bloß gleichlautende Zustände aus früheren Iterationen
zusammenfallen lässt, wäre keine zulässige Lösung.

## Kosten und Prüfbarkeit

`FiniteFactorKnowledge` trennt Erwerb, Wiederherstellung, Anfrage und Replay.
Ein Fehlversuch außerhalb der Tabelle beweist keine allgemeine Unerreichbarkeit.
`GuidedFactorSearch` bewahrt vollständige bezahlte Nachfolger und ändert nur die
Reihenfolge späterer Versuche. Kontext, Controllergrenze und Iterationsbelege
bleiben sichtbar. `FactorSearchPortfolio` wartet auf alle deklarierten Mitglieder
und wählt anhand von Pfadlänge und stabiler Mitglieds-ID; serieller und paralleler
Lauf sind für kanonische Ergebnisse identisch.

`LocalNeutralClosure` führt sechs explizite neutrale Regeln durch den vorhandenen
`RewriteProgram`-Interpreter aus. Jede Regel verkleinert den rohen Syntaxbaum.
Der Pack enthält weder unbedingte Kürzung noch Multiplikation mit null oder
zielbezogene Makros. Ein Abbruch des gemeinsamen Aufrufbudgets bewahrt die
bereits bezahlten Quellaufrufe und veröffentlicht keine vollständige Komposition.

Die [Laufzeit- und Allokationsbeobachtungen](runtime.json) sind separat erhaltene
Einzelmessungen des tatsächlichen JVM-Laufs. Für Portfolios wird Thread-Allokation
als nicht beobachtet ausgewiesen. Es gibt keine JMH-/Konfidenz- oder allgemeine
Geschwindigkeitsaussage. Native Aufrufe, Controllerbeobachtungen, Java-Allokation
und Wallzeit bilden unterschiedliche Größen; die vollständige gemeinsame
mechanische Kostenwährung ist nicht nachgewiesen. **Der 1000×-Zielwert ist nicht
erreicht.**

Die Kontrollen prüfen jeden Endpunkt vollständiger kleiner Abschlüsse über alle
Tiefengrenzen, Kontexttrennung, native Nachprüfung, zu kleine Budgets, unzulässige
lokale Regeln und auch einen manipulierten Tabelleninhalt mit korrekt neu
berechnetem Hash. Ein gültiger Hash ersetzt keine mathematische Rekonstruktion.

## Reproduktion des ursprünglichen Versuchs

Das [Git-Bundle](source-history.bundle) enthält die ursprüngliche
Quellhistorie für `refs/heads/historical/finite-search-knowledge-v1`. Es setzt
den öffentlichen Commit `e115dca89bba32bad516d66f408e26f99fbb6149` voraus.
Der Lauf stammt von Commit `907286805de951cd7adff346132d9b22368b8a0c`
mit Dateibaum `85d67653d28777f6c7a0000e6d649cd327126379`. Das Bundle
hat 2.436.900 Bytes und SHA-256
`8b8c53d0cc8eeb4c84d2886fddcc12045f4b2714880a63389b320b0782ed9f4f`.

Die Befehle setzen einen vollständigen, ungefilterten Checkout und Java 25
voraus. Der ursprüngliche Maven-Reaktor führt Jackson Databind im Test-Scope.
Der Classpath schließt daher diesen Scope ein und führt die Produktionsklasse
des historischen Versuchs aus.

```bash
study="$(git rev-parse --show-toplevel)/docs/research/finite-search-knowledge-v1"
bundle="$study/source-history.bundle"
ref=refs/heads/historical/finite-search-knowledge-v1
revision=907286805de951cd7adff346132d9b22368b8a0c
git fetch origin main
git cat-file -e 'e115dca89bba32bad516d66f408e26f99fbb6149^{commit}'
printf '%s  %s\n' 8b8c53d0cc8eeb4c84d2886fddcc12045f4b2714880a63389b320b0782ed9f4f "$bundle" | sha256sum -c -
git bundle verify "$bundle"
git fetch "$bundle" "$ref:$ref"
test "$(git rev-parse "$ref")" = "$revision"
test "$(git rev-parse "$revision^{tree}")" = 85d67653d28777f6c7a0000e6d649cd327126379
workspace=$(mktemp -d)
git worktree add --detach "$workspace/source" "$revision"
cd "$workspace/source"
mvn -pl regelsuche-experiments -am -DskipTests -DincludeScope=test \
  -Dmdep.outputFile=target/finite-search-classpath.txt package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath
module=regelsuche-experiments/target
output="$module/finite-search-reproduction"
java -Xmx512m -cp "$module/classes:$(cat "$module/finite-search-classpath.txt")" \
  de.regelsuche.benchmark.FiniteSearchKnowledgeComparison \
  "$output/comparison.json" "$output/runtime.json"
cmp "$study/comparison.json" "$output/comparison.json"
```

Der Bytevergleich prüft alle 160 Ergebniszeilen und die kanonischen Tabellen.
Die Laufzeitdatei enthält Messungen des jeweiligen JVM-Laufs.
[Run-Manifest](run.json) · [Aktueller Vergleichsvertrag](../finite-search-knowledge-v2/README.md)
