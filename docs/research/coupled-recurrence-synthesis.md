# Vollständige Rekurrenzsuche für gekoppelte Systeme

## Aufgaben und Vergleich

Das [Aufgabenpanel](../../config/benchmarks/coupled-recurrence-v1.json)
enthält sechs Aufgaben aus drei unabhängig veröffentlichten
Übergangsformen. Die Matrizen, Anfangswerte, Gradgrenze zwei und
Arbeitsbudgets standen vor dem Vergleich im Repository. Die erwarteten
Anzahlen der Gesetze fließen nur in die Auswertung ein.
[Festgeschriebener Stand](https://github.com/carstenartur/Regelsuche/blob/41dcd05cd650ada4e3211ed43588817f61520d25/config/benchmarks/coupled-recurrence-v1.json)

- Der harmonische Oszillator nutzt symplektischen Euler mit
  Schrittweite $1/2$ und rationalen Koeffizienten. Die zweite Aufgabe
  verschiebt sein Zentrum nach $(2,-1)$.
  [Yoshida, Gleichung (42) mit Parameter null](https://www.cfca.nao.ac.jp/~tito/ndyn/pdfproc/proc_CeleMechNbodyDyn2001Kusatsu.pdf)
- Die rationale Cayley-Rotation hat Achse $(1,2,3)$. Die
  Rotationsspiegelung verwendet die negierte Matrix. Der dritte
  Übergang addiert bei jedem Schritt den Achsenvektor. Diese
  Verschiebung entlang der Achse lässt sich durch einen Wechsel des
  Ursprungs nicht aufheben.
  [Gallier, Cayley-Darstellung](https://www.cis.upenn.edu/~jean/invers.pdf) ·
  [Lynch und Park, Schraubenbewegung](https://hades.mech.northwestern.edu/images/9/90/MR-tablet-temporary.pdf)
- Die Tribonacci-Matrix hat im geprüften Grad- und Vorzeichenraum
  keine nichtkonstante Richtung. Sie ist die unabhängige
  Negativkontrolle. [Publizierte Übergangsmatrix zu Gleichung (28)](https://doi.org/10.3390/math7010074)

Beide Verfahren berechnen die vollständige Basis für feste und
alternierende Gesetze. Für jeden Übergang bleiben bis zu 54 Einheiten
für Wertabfragen reserviert. Verrechnet werden die tatsächlichen
Abfragen. Die Tests vergleichen jede Ausgabe zusätzlich mit einer
direkt berechneten rationalen Trajektorie bis zum Index 63.

## Gradaufteilung und direkte Matrixbildung

Lineare Übergänge ohne Verschiebung erhalten den Polynomgrad. Der
reguläre Aufruf löst die linearen und quadratischen Richtungen
getrennt. Er liefert feste Gesetze vor alternierenden und innerhalb
eines Vorzeichens die niedrigeren Grade zuerst. Bei affinen
Verschiebungen können bei der Substitution Terme niedrigeren Grades
entstehen. Diese Aufgaben behalten den gemeinsamen Ansatzraum.

Die zweite Änderung konstruiert aus der Übergangsmatrix einmal die
rationalen Polynomkoeffizienten aller Monombilder. Jede Spalte des
Gleichungssystems enthält das Bild eines Monoms. Für ein Gesetz
mit Vorzeichen $s$ wird $s$ von der Diagonale abgezogen.
Damit geht das Koeffizientensystem direkt an die
vorhandene exakte Zeilenreduktion. Diese liefert Rang und Nullraum
in einem Lauf mit Prüfung der Zeilenoperationen. Alle ausgegebenen
Gesetze bestehen weiterhin die unabhängige symbolische Prüfung
des ursprünglichen Übergangs und des Anfangswerts.
[Müller-Olm und Seidl, lineare Algebra für beschränkte Polynomgrade](https://www.uni-muenster.de/imperia/md/content/informatik/agmueller-olm/publications/affinetrtrier.pdf)

| Aufgabe | Gradaufteilung mit symbolischer Koeffizientenbildung | Direkte rationale Matrixbildung | Ersparnis |
| --- | ---: | ---: | ---: |
| Symplektischer Euler | 3.447 | 1.192 | 65,4 % |
| Verschobener Oszillator | 5.857 | 2.522 | 56,9 % |
| Cayley-Rotation | 12.212 | 5.190 | 57,5 % |
| Rotationsspiegelung | 12.440 | 5.258 | 57,7 % |
| Drehung mit axialer Drift | 16.761 | 7.044 | 58,0 % |
| Tribonacci | 6.992 | 1.862 | 73,4 % |
| **Gesamt** | **57.709** | **23.068** | **60,0 %** |

Die neun Gesetze stimmen einschließlich Reihenfolge, Vorzeichen,
Normierung und 54 abgefragter Werte überein. Die symbolische
Prüfung kostet in beiden Läufen 10.256 Einheiten. Die Messgröße
ist algorithmisch verrechnete Arbeit. Die Zähler bewerten die
Rechenschritte unabhängig von der Bitlänge der Koeffizienten.
[Gradaufteilung](coupled-recurrence-v1/degree-blocks.tsv) ·
[Direkte Matrixbildung](coupled-recurrence-v1/typed-coefficients.tsv)

Der wiederholte Lauf über das historische Cassini-Panel liefert
dieselben Gesetze und Anfangswerte. Der ursprüngliche
Fibonacci-Fall sinkt von 4.350 auf 999 Arbeitseinheiten.
[Aktueller Lauf des Cassini-Panels](coupled-recurrence-v1/cassini-current.json) ·
[Ursprünglicher Versuch](cassini-development-v1/result.md)

## Wiederverwendung geprüfter Gesetze

Der Versuch beginnt mit den beiden regulär gelösten Ankeraufgaben.
Für einen neuen Übergang mit gleicher Matrix löst er
$(I-A)c=b_{\mathrm{neu}}-b_{\mathrm{alt}}$ exakt und prüft die
Lösung. Wenn eine solche Verschiebung existiert, überträgt sich
jedes Quellgesetz $P$ durch $P(x-c)$. Bei zwei homogenen
Übergängen mit vollständig negierter Matrix gewinnt ein Gesetz
vom Grad $d$ den zusätzlichen Vorzeichenfaktor $(-1)^d$.
Beide Transformationen sind umkehrbar und erhalten daher auch
die Vollständigkeit des beschränkten Ansatzraums.

Bei kompatiblen Übergängen misst der Versuch zwei Anwendungen.
Die erste führt für jedes übertragene Gesetz die vollständige
symbolische Prüfung durch. Die zweite prüft die
Übergangsbeziehung, übernimmt den bewiesenen Schritt und
berechnet den neuen Anfangswert exakt. Beide Ausgaben wurden im
Test zusätzlich mit dem regulären Prüfer und der vollständig
direkt bestimmten Basis verglichen. Bei axialer Drift und
Tribonacci wird der erfolglose Zuordnungsversuch vor dem
direkten Rückfall verrechnet.

| Rechnung über alle sechs Aufgaben | Arbeitseinheiten |
| --- | ---: |
| Stärkste direkte Variante | 23.068 |
| Übertragen und jedes Gesetz vollständig neu prüfen | 20.449 |
| Übertragen, geprüften Beweis nutzen und neuen Anfangswert prüfen | 16.225 |

Die letzte Zeile spart **29,7 Prozent**, einschließlich der
Aufbereitung der beiden Anker und der beiden erfolglosen
Versuche. Das Panel bindet die Ankerzuordnung für jede Aufgabe
im Voraus. Zwei passende Übergangsbeziehungen stehen dem
Versuchsprogramm zur Verfügung. Die Ergebnisse messen damit
die Kosten eines prüfbaren Transfers bei bekannter Zuordnung.
[Einzelbelege des Transferversuchs](coupled-recurrence-v1/transfer.tsv)

## Nächste Versuche

1. Die günstige Zuordnung zu einem passenden Beweis aus dem
   Programmtext ableiten. Zunächst affine Verschiebung und
   Vorzeichenänderung prüfen, dann dünn besetzte Änderungen
   der Basis. Jede übernommene Beziehung braucht eine exakte
   Prüfung am neuen Übergang. Ein getrennt festgelegter Vergleich
   soll die Ankersuche, Fehltreffer und Beweisübernahme über
   mehrere unabhängige Familien mitverrechnen.
2. Den für eine konkrete Beobachtung relevanten Zustand
   bestimmen. Für das angefragte Polynom $P$ bilden die
   sukzessiven Koeffizientenvektoren von $P, P\circ T,
   P\circ T^2, \ldots$ einen Krylow-Raum. Die erste exakte
   Abhängigkeit liefert eine Rekurrenz der Beobachtung. Der
   Vergleich soll Aufbau, Beweis und Abfragen gegen das
   Potenzieren des vollständigen Operators messen.
   [Wiedemann-Folgen und Minimalpolynome](https://perso.ens-lyon.fr/gilles.villard/BIBLIOGRAPHIE/PDF/certificates-issac-2016.pdf) ·
   [Fiduccia, schnelle Auswertung linearer Rekurrenzen](https://doi.org/10.1137/0214007)

## Reproduktion

Mit Java 25 reproduzieren die Tests die aktuellen Tabellen
bytegenau. Die frühere Tabelle lässt sich im
[Stand der Gradaufteilung](https://github.com/carstenartur/Regelsuche/commit/efd067ebf76dd6b82c6d567025b8c2c4d8ca0e42)
mit `-Dtest=CoupledRecurrenceExperimentTest` wiederholen.

```bash
mvn -B -pl regelsuche-math-algorithms -am \
  -Dtest=CoupledRecurrenceExperimentTest,CoupledRecurrenceTransferExperimentTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Für einen Export der Tabellen
`-DcoupledRecurrenceOutput=/tmp/synthese.tsv` und
`-DcoupledRecurrenceTransferOutput=/tmp/transfer.tsv` ergänzen.
