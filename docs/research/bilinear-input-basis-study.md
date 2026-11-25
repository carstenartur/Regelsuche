# Rationale Eingabebasiswechsel auf getrennten Tensoren

**Versuch vom 25. November 2026, bilinear-input-basis/v1.** Aus zwei Ausgangstensoren gewann die endliche Suche jeweils ein rationales Drei-Produkt-Programm. Alle sechs Zieltensoren erhielten unter einem vorgegebenen rationalen Basiswechsel einen geprüften Plan. Die klassische feste Drei-Produkt-Methode verbrauchte auf diesen Zielen weniger Rechenarbeit.

## Quellen und Kontrollen

Der Erwerb verwendet die Multiplikation in $\mathbb{Q}[t]/(t^2-d)$ für $d=-1$ und $d=2$ in der Monombasis. Für beide Quellen erzeugt der Test für $n=1,2,3$ zwei verschiedene rationale Scherungen mit $h=n/(n+1)$ und $k=-(n+1)/(n+2)$. Aus den neuen Operanden $x,y$ werden die alten Koordinaten $(x_0+hx_1,x_1)$ und $(y_0,ky_0+y_1)$. Der Test berechnet die Zielmatrizen separat aus der ausmultiplizierten Formel. Der erste Koeffizient ihrer zweiten Ausgabe ist $k$ und unterscheidet alle Ziele von den Erwerbstensoren. Ein weiterer Kontrollaufruf pro Ziel ersetzt den Koeffizienten $1+hk$ durch null. Alle sechs solchen Transfers werden abgewiesen.

Jeder erfolgreiche Plan wird auf 15 vorab festgelegten rationalen Operandenpaaren ausgeführt. Eine direkte Kontrolle berechnet die vier Koordinatenprodukte einmal und teilt sie zwischen beiden Ausgaben. Eine zweite feste Kontrolle erhält dieselben Scherungen und den Algebra-Parameter. Sie berechnet nach dem Basiswechsel die drei Produkte $p=ac$, $q=be$, $r=(a+b)(c+e)$ und liefert $(p+dq,r-p-q)$. Alle drei Methoden stimmen in beiden Ausgabekomponenten bei allen 90 Paaren überein. Der rationale Rangnachweis und die geprüfte Zerlegung ergeben für jedes Ziel das Intervall $[3,3]$.

## Gemessene deterministische Arbeit

| Vorgang | Anzahl | Belastete Arbeitseinheiten |
| --- | ---: | ---: |
| Erwerb der Programme aus den zwei Ausgangstensoren | 2 | 55.288 |
| Erfolgreicher Basiswechsel einschließlich Verifikation | 6 | 7.548 |
| Zurückgewiesener Transfer auf den verfälschten Zieltensor | 6 | 6.018 |
| Konstruktion der Zieluntergrenze und gemeinsame Prüfung beider Grenzen | 6 | 4.374 |
| Ausführung der zuvor geprüften übertragenen Programme | 90 | 7.740 |
| Feste direkte Koordinatenrechnung | 90 | 5.760 |
| Feste klassische Drei-Produkt-Formel mit Eingabescherungen | 90 | 4.680 |

Der vollständige ausgeführte Versuchspfad mit Erwerb, begründetem Produktintervall, Fehlkontrollen und Auswertung verbrauchte 80.968 Einheiten. Die festen Ausführungen verwenden die vorhandene Aufgabenformel und benötigen keine Suche. Die Ausführungszahlen messen die durch `CertificateWork` belastete Arbeit an rationalen Werten und linearen Formen. Das Erzeugen der Testeingaben findet vor der Messung statt. Es liegen keine JVM-Zeit- oder Allokationsmessungen vor. Der Versuch untersucht bekannte algebraische Zusammenhänge unter neuen Koordinaten. Allgemeiner Transfer auf unbekannte Zusammenhänge bleibt offen.

## Reproduktion

```sh
mvn -pl regelsuche-math-algorithms -am -Dtest=BilinearInputBasisStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die Kennung `bilinear-input-basis/v1` bezeichnet die Ergebniszeile. Die [Versuchsimplementierung](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/algebra/BilinearInputBasisStudyTest.java) enthält die Quellen, den vollständigen Auswertungssatz und die Kontrollen.
