# Automatische Wahl eines bewiesenen Rekurrenzgesetzes

## Ausführung und Freigabe

`CumulativeTaskKnowledge.solve` versucht vorhandene exakte Gesetze und
symbolisch bewiesene Parameterfamilien. Anschließend prüft es die schon
bewiesenen Anker im Katalog auf einen passenden Übergang. Bei gleicher
Matrix $A$ bestimmt die exakte Zeilenreduktion eine Verschiebung $c$ aus
$(I-A)c=b_{\mathrm{neu}}-b_{\mathrm{alt}}$. Eine unabhängige Rechnung an
den Übergängen prüft die erhaltene Lösung. Das neue Gesetz ist $P(x-c)$.
Für zwei homogene Übergänge mit entgegengesetzten Matrizen lässt sich ein
Polynom mit einheitlicher Parität direkt übertragen. Bei ungerader
Parität wechselt das Vorzeichen des Gesetzes.

Der Prüfer übernimmt den bereits bewiesenen Schritt, liest neu
formatierte Polynome mit dem regulären Parser zurück und berechnet den
neuen Anfangswert exakt. Bei einem inkompatiblen Kandidaten setzt er
die Suche mit dem nächsten Anker oder mit der direkten Herleitung fort.
Ankerwahl, Fehlversuche, Umformung, Anfangswert und
gegebenenfalls direkte Suche teilen sich das Anfragebudget.

Eine Wissensgeneration wird erst aktiviert, wenn jedes neue Gesetz
tatsächlich auf einer separaten Validierungsanfrage ausgewählt wurde und
die bestehende Erfolgs- und Nutzenprüfung bestanden ist. Beim Laden
prüft der Verifier die im Snapshot gespeicherten Vorschläge neu. Der
Inhalt des Snapshots bestimmt weiterhin seine Identität. Verifizierte
Polynome dienen als vorbereitete Beweise im Speicher.

Das reguläre Ziel einer Wissensanfrage ist ein überprüftes
nichtkonstantes Gesetz. Der direkte Vergleich nutzt dieselbe
typisierte Koeffizientenbildung und stoppt beim ersten bewiesenen
Gesetz. Für vollständige Basen steht `RecurrenceInvariantSynthesis.solve`
zur Verfügung.

## Gekoppelte Aufgaben

Das [Protokoll](../../config/benchmarks/recurrence-proof-selection-v1.json)
legt Ankeraufgaben, Validierung, Anfragen, Reihenfolge und Budgets im Voraus
fest. Der Katalog erhält drei Anker. Eine verschobene Oszillatorabbildung,
die negierte Matrix einer Arnold-Abbildung und eine diskrete
Integratorkette prüfen die automatische Zuordnung für die beiden
implementierten Übergangsbeziehungen. Die Anfragen verwenden eigene
Anfangszustände oder Übergänge. Die Matrix der Arnold-Abbildung wird
über den rationalen Zahlen angewandt. In dieser Aufgabenfamilie
bleibt die Zielmatrix gleich und der Anfangszustand variiert.
[Symplektischer Euler, Gleichung (42) bei Parameter null](https://www.cfca.nao.ac.jp/~tito/ndyn/pdfproc/proc_CeleMechNbodyDyn2001Kusatsu.pdf) ·
[Arnold-Abbildung](https://www.scholarpedia.org/article/Cat_map)

Zwei Kontrollaufgaben bleiben in jeder Runde enthalten. Eine axiale
Drift in der Integratorkette führt trotz gleicher Matrix zu einem
widersprüchlichen Verschiebungssystem. Die zweite Kontrollmatrix hat
keinen passenden Anker. Bei Erfolg werden fünf Werte abgefragt. Die
Polynome beider Verfahren stimmen bis auf einen von null verschiedenen
Skalierungsfaktor und, bei festen Gesetzen, eine additive Konstante
überein. Die Testauswertung prüft jedes übertragene Gesetz mit dem
vollständigen unabhängigen Prüfer und vergleicht alle Werte in dieser
Normierung.

## Verrechnete Arbeit

| Anfragen | Geprüfte Gesetze je Verfahren | Direkt | Automatisch nach dem Laden | Einschließlich Erwerb und Laden |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 3 | 4.290 | 1.831 | 10.622 |
| 20 | 12 | 17.311 | 7.626 | 16.417 |
| 40 | 24 | 34.954 | 15.416 | 24.207 |
| 80 | 48 | 70.336 | 30.983 | 39.774 |

Alle 48 positiven Anfragen greifen auf einen automatisch gewählten
Anker zu. Bei 80 Anfragen spart der vollständige Lebenszyklus **43,5
Prozent** der verrechneten Arbeit. Enthalten sind 7.006 Einheiten für
Erwerb und Validierung sowie 1.785 Einheiten für das Laden und die
erneute Prüfung der Anker. Beide Kontrollgruppen brauchen mit
Ankerwahl mehr Arbeit. Die abgelehnten Kandidaten sind im Gesamtwert
enthalten. Die Zähler messen logische Arbeit unabhängig von der
Bitlänge der Koeffizienten. [Summen](recurrence-proof-selection-v1/work.tsv) ·
[Anfragen und Fehlversuche](recurrence-proof-selection-v1/requests.tsv)

## Reproduktion

Der Test erzeugt beide Tabellen bytegenau mit Java 25.

```bash
mvn -B -pl regelsuche-learning -am \
  -Dtest=RecurrenceProofSelectionComparisonTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Mit `-DrecurrenceProofSelectionOutput=/tmp/recurrence-proof-selection`
schreibt der Test die Tabellen in ein separates Verzeichnis.
