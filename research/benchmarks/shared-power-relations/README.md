# Gemeinsame Potenzen: Struktur- und Transfervergleich

Stand: 26. November 2026; Ausgangsstand
`3a0fb43fd1fe5b8f849ce215e2db7c6316e6c2b7` plus der gemeinsame Änderungsstand
dieser Studie und der vier `SharedPower`-Kernklassen.

[`SharedPowerStudy.java`](SharedPowerStudy.java) gibt die unveränderten sechs
Quellanforderungen ein. Es prüft die zurückgegebenen Werte zusätzlich direkt
mit `BigInteger.modPow`, einschließlich negativer Quellexponenten. Die
177 erfolgreichen Basisanwendungen umfassen auch kongruente Repräsentanten.
Dies ist ein Strukturvergleich, keine JMH- oder Laufzeitstudie.

| Fall | Exakter gemeinsamer Vergleich | Gewählter Plan | Aussage |
| --- | ---: | ---: | --- |
| Exakte benachbarte Potenzen | 19 | 19 | vorhandene gemeinsame Potenz bleibt erhalten |
| Quadratbeobachtung, p=1019 | 22 | 14 | eine Potenz und ein Produkt, keine Inverse |
| Quadratbeobachtung, p=31; Inversionsgewicht 1 | 4 | 4 | exakter Vergleich gewinnt |
| Kubikbeobachtung, p=103 | 7 | 2 | Transfer auf einen anderen Beobachtungsvertrag |
| Quadrate modulo 15 | 1 | 0 | Gleichheit der Quadrate bedeutet hier keine Vorzeichenäquivalenz |
| Quadratbeobachtung, 381-Bit-Primzahl | 614 | 606 | gemeinsame Exponenten ohne Primzahlkonstante im Algorithmus |

Einheiten sind die deklarierten gewichteten Binärkettenkosten. Außer beim
ausgewiesenen p31-Fall sind die Gewichte Multiplikation=1, Quadrierung=1,
Inverse=8. Ein unterschiedliches Gewicht kann die Auswahl ändern.
Der exakte Vergleich verwendet dieselben Wiederverwendungsregeln und
Reihenfolgen wie die Suche mit gelockerten Beobachtungen.

[`results.csv`](results.csv) enthält zusätzlich die Kosten aller
Kandidatenkonstruktionen und -prüfungen sowie der geprüften Auswertungen.
`evaluation_work` enthält die bei jeder Anwendung erneut geprüfte
Gruppenannahme. Keine dieser Arbeitsmetriken ist eine Zeit in Nanosekunden.
Die vollständigen Verträge und die zusätzliche Fp²-Ableitung stehen in
[der Fachseite](../../../docs/research/shared-power-relations.md).

Nach dem dort beschriebenen Maven-Build, vom Repository-Stamm:

```sh
mkdir -p regelsuche-math-algorithms/target/shared-power-study
javac -Xlint:all -Werror \
  -cp regelsuche-math-algorithms/target/classes:regelsuche-core/target/classes \
  -d regelsuche-math-algorithms/target/shared-power-study \
  research/benchmarks/shared-power-relations/SharedPowerStudy.java
java -cp regelsuche-math-algorithms/target/shared-power-study:regelsuche-math-algorithms/target/classes:regelsuche-core/target/classes \
  SharedPowerStudy
```

Die Ausgabe muss der CSV entsprechen. Die Studie behauptet weder
mathematische Neuheit der Exponentenidentität noch eine allgemeine
Algorithmusentdeckung aus unannotiertem Java-Quelltext.
