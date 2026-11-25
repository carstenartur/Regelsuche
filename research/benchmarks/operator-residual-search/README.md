# Automatische Operatorreparatur: Erwerb und wiederholte Anwendung

Stand: 25. November 2026. Lokale Beobachtung mit Java 25 auf x86_64/Linux.
Quellenbasis und SHA-256-Abdrücke der ausgeführten Quellen stehen in
[manifest.json](manifest.json). Die Zahlen gelten für diesen Versuch.

## Fragestellung und Kontrollen

Kann eine aus der Eingabematrix entdeckte Reparatur ihren Erwerb bei
wiederholter Anwendung bezahlen? `OperatorPlanSdk.discover` erhält nur die
rationale Matrix und die feste Policy aus Null-, Einheits-, Diagonal- und
Tridiagonalprädiktoren. Es erhält weder eine Eigenbasis noch die für eine
Familie bekannte Spezialformel. Die Suche prüft alle Kandidaten und ihre
Restfaktorisierung unabhängig gegen die Originalmatrix.

Die vier Familien sind Zentrierung in acht beziehungsweise 16 Dimensionen,
die Diagonale `(1,...,8)` und eine feste dichte ganzzahlige 8×8-Matrix.
Zentrierung bedeutet `y_i = x_i − Mittelwert(x)`. Jede Familie verwendet
64 verschiedene Eingabevektoren mit ganzzahligen Komponenten in
`[-1000,1000]` und Seed 291843. Alle nachfolgenden Rechnungen sind rational
exakt. Matrix und Eingabevektoren werden vor den gemessenen Blöcken angelegt.

| Arm | Gemessene Rechnung |
| --- | --- |
| `acquire` | Vollständige Quellsuche einschließlich erfolgloser Prädiktoren, unabhängiger Prüfungen, Auswahl und ausführbarer Vorbereitung |
| `selected` | Wiederholte Anwendung des geprüften ausgewählten Plans mit SDK-Ressourcenprüfungen |
| `directChecked` | Vorbereitete Direktmatrix mit denselben SDK-Ressourcenprüfungen und denselben Null-/Einsoptimierungen |
| `directArithmetic` | Separate dünn besetzte rationale Matrix-Vektor-Rechnung ohne SDK-Prüfungen |
| `specializedArithmetic` | Bei Zentrierung die handgeschriebene Mittelwertformel; bei den anderen Familien dieselbe rohe Direktrechnung |

Vor jedem Prozess werden alle 64 Ausgaben des ausgewählten und direkten
Plans gegen die separate Rechnung und die Spezialformel geprüft. Ein
abweichendes Resultat bricht den Prozess ab. Ein `volatile`-Ergebnisfeld
verhindert, dass die JVM die gemessenen Resultate als unbenutzt entfernt.

## Protokoll

Jede Kombination aus Familie und Arm läuft in drei frischen JVM-Prozessen
mit `-Xms128m -Xmx256m`, insgesamt 60 Prozesse. Jeder Prozess führt fünf
Aufwärmblöcke und sieben Messblöcke aus. Ein Erwerbsblock umfasst 20,
ein Anwendungsblock 2.000 Aufrufe. `System.nanoTime` misst die verstrichene
Zeit; der JVM-Threadzähler misst die zugewiesenen Bytes des ausführenden
Threads. GC-Pausen können die Zeit beeinflussen. Die Prozesse sind
nacheinander ausgeführt; andere Arbeit auf dem Host ist nicht ausgeschlossen.

Die Einrichtung führt bereits eine Suche aus, und auch der Erwerbsarm wird
aufgewärmt. Daher messen die Zahlen **keinen JVM-Kaltstart**. Es handelt sich
um einen kleinen selbstständigen Beobachtungslauf, keinen JMH-Versuch. Die
drei Prozesse erlauben keine belastbare Konfidenzaussage über andere Rechner
oder Eingabefamilien.

Für jede Spalte wird zunächst der Median der sieben Blöcke eines Prozesses
und dann der Median der drei Prozessmediane gebildet. Die Grenzen in
[summary.tsv](summary.tsv) sind die kleinsten und größten Prozessmediane,
keine Konfidenzintervalle. [observations.tsv](observations.tsv) enthält alle
420 gemessenen Blöcke; Aufwärmblöcke werden nicht als Messwerte ausgegeben.

## Ergebnisse

| Familie | Erwerb, µs | Auswahl, µs/Vektor | Geprüft direkt, µs/Vektor | Roh direkt, µs/Vektor | Spezialformel, µs/Vektor |
| --- | ---: | ---: | ---: | ---: | ---: |
| Zentrierung 8 | 1.663,647 | 12,360 | 49,803 | 22,405 | 3,883 |
| Zentrierung 16 | 9.361,059 | 20,988 | 207,219 | 89,692 | 7,737 |
| Diagonale 8 | 573,169 | 4,510 | 4,714 | 2,404 | 2,439 |
| Dicht 8 | 2.188,682 | 40,544 | 42,247 | 21,778 | 21,672 |

Bei den letzten beiden Familien wählt die Suche die direkte Matrix. Die
getrennt beobachteten Zeiten von `selected` und `directChecked` messen dort
denselben Algorithmus; kleine Unterschiede sind kein entdeckter Vorteil.
Die Suche hat in diesen Fällen zusätzliche Kosten ohne mathematischen
Anwendungsgewinn. Dass auch die beiden identischen rohen Arme etwas
verschiedene Zeiten liefern, zeigt die Streuung des Protokolls.

| Familie | Erwerb, Bytes | Auswahl, Bytes/Vektor | Geprüft direkt, Bytes/Vektor | Roh direkt, Bytes/Vektor | Spezialformel, Bytes/Vektor |
| --- | ---: | ---: | ---: | ---: | ---: |
| Zentrierung 8 | 3.101.544 | 30.696 | 153.096 | 143.867 | 23.075 |
| Zentrierung 16 | 20.565.000 | 53.596 | 536.982 | 501.984 | 45.995 |
| Diagonale 8 | 538.320 | 8.557 | 8.629 | 7.546 | 7.546 |
| Dicht 8 | 3.979.570 | 135.753 | 135.729 | 129.072 | 129.072 |

Bytes sind auf ganze Werte gerundete Mediane. Es handelt sich um
Allokationsvolumen, nicht um gleichzeitig lebenden Speicher oder einen
Heap-Höchststand. Die exakte Arithmetik erzeugt auch im vorbereiteten Plan
neue rationale Zwischenwerte.

Eine vorsichtige rein rechnerische Amortisation belastet die Auswahl mit
dem **gesamten** gemessenen Erwerb und setzt für die bereits vorhandene
Direktrechnung keine Vorbereitungskosten an. Aus
`Erwerb / (direkte Anwendung − ausgewählte Anwendung)` folgen für die
Zentrierung in acht beziehungsweise 16 Dimensionen etwa 45 beziehungsweise
51 Anwendungen gegenüber `directChecked`. Gegenüber der stärkeren rohen
Direktrechnung sind es etwa 166 beziehungsweise 137. Dies sind aus Medianen
berechnete Schwellen, keine gesondert gemessenen vollständigen Sitzungen.
Gegenüber der handgeschriebenen Mittelwertformel gibt es hier keinen
Amortisationspunkt: Sie bleibt schneller als der entdeckte generische Plan.

### Deterministische Zähler

[selection.tsv](selection.tsv) dokumentiert außerdem die Auswahl und die
Arbeitseinheiten. Für Zentrierung 8/16 sind es 23/47 rationale
Anwendungsoperationen gegenüber 120/496 direkt. Die gesamte Suche kostet
21.746/128.811 Ledger-Einheiten; eine getrennte frische Planprüfung
1.523/5.851. Der Erwerbsarm enthält schon die zur Suche gehörenden
Prüfungen. Die frische Prüfung ist eine zusätzliche Replay-Kontrolle und
hat hier keinen eigenen Zeit- oder Allokationsarm. Ledger-Einheiten,+rationale Operationen und Nanosekunden sind verschiedene Größen.

## Reproduktion

Im Repository-Stamm zunächst das SDK mit Java 25 bauen und lokal installieren:

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am -DskipTests install
bash research/benchmarks/operator-residual-search/run.sh /tmp/operator-observations
python3 research/benchmarks/operator-residual-search/summarize.py
```

Der letzte Befehl reproduziert die veröffentlichte Zusammenfassung aus den
eingecheckten Rohdaten. `run.sh` legt die frischen Prozessdateien im angegebenen
Ausgabeverzeichnis ab. Es kompiliert die Studie mit `-Xlint:all -Werror` und
bestimmt den Klassenpfad über das kleine Begleit-POM. Alternativ kann
`OPERATOR_BENCH_CLASSPATH` auf einen bereits gebauten SDK-Klassenpfad zeigen;
das ändert die gemessenen Rechnungen nicht. Der Quelltext des Versuchs ist
[OperatorResidualStudy.java](OperatorResidualStudy.java).

Der belegte Nutzen ist begrenzt: Der Kern kann Struktur aus einer gegebenen
Matrix gewinnen, sie unabhängig prüfen und an neuen Vektoren wiederverwenden.
Eine einzige Anwendung und unstrukturierte oder bereits diagonal vorliegende
Quellen rechtfertigen den Erwerb in diesem Versuch nicht. Für eine bekannte
Zentrierungsaufgabe bleibt die direkte fachliche Mittelwertformel die stärkere
Lösung.
