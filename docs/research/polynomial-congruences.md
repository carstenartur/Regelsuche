# Restklassen zu ganzzahligen Polynomen

`PolynomialCongruence` im Modul `regelsuche-math-algorithms` bestimmt für
ein ganzzahliges Polynom \(P\), einen positiven `int`-Modulus \(m\) und einen
ganzzahligen Zielrest \(r\) die Menge

\[
\{x\in\{0,\ldots,m-1\}\mid P(x)\equiv r\pmod m\}.
\]

`Request` enthält die unveränderten `BigInteger`-Koeffizienten in aufsteigender
Exponentenreihenfolge, beginnend beim konstanten Glied. Die Liste enthält
mindestens einen Koeffizienten. Koeffizienten und Zielrest dürfen negativ sein.
Die Rechnung setzt weder einen Primmodulus noch einfache Wurzeln voraus.

## Rechnung und Prüfung

Der Kern reduziert die Koeffizienten und den Zielrest einmal auf Werte von
null bis \(m-1\). Er besucht die Parameterreste aufsteigend und wertet das
Polynom jeweils von seinem höchsten Koeffizienten aus nach Horner aus.
Der größte mögliche Zwischenwert vor der Reduktion ist
\((m-1)^2+(m-1)=m(m-1)\) und passt auch für \(m=2^{31}-1\) in `long`.
Jeder passende Parameter erscheint genau einmal und in aufsteigender Folge.
Bei Modulus eins besteht die Menge immer aus null, sofern die Rechnung ihr
Budget einhält.

`COMPLETE` enthält eine `Solution` mit der exakten ursprünglichen Anfrage und
der gesamten Restmenge. Eine leere Menge bestätigt, dass die Kongruenz keine
Lösung hat. `verify` prüft die unveränderte Anfrage einschließlich
Koeffizientenliste und Zielrest, auch wenn eine veränderte Anfrage zufällig
dieselbe Restmenge ergäbe. Anschließend prüft es Wertebereich und Reihenfolge
der übergebenen Reste und vergleicht sie mit einer vollständigen Neuberechnung.
Damit belegt die Lösung eine endliche Enumeration für die gegebene Anfrage.

`IterationLimits` beschränkt den Betrag jedes `BigInteger`-Eingabewerts auf
65.536 Bit. `maximumTerms` beschränkt die Koeffizientenzahl und die Wurzelzahl
jeweils einzeln. Die deterministische Arbeitszählung berechnet unter anderem
Kosten pro Koeffizientenschritt sowie nach Wortlänge der zu normalisierenden
Werte. Die Zeitgrenze und das Abbruchsignal werden an Rechenschritten geprüft.
Ein vorzeitig beendeter Lauf liefert `UNAVAILABLE` ohne Lösung, eine vorzeitig
beendete Prüfung liefert `valid=false`. Vollständige Läufe benötigen bei
\(k\) Koeffizienten \(O(mk)\) Horner-Schritte und sind für kleine Moduln gedacht.

`PolynomialCongruenceSdk` übersetzt `maximumStates` in `maximumTerms` und gibt
`maximumWork`, `timeoutMillis` und `CancellationToken` an den Kern weiter.
Anwendungen bestimmen die Herkunft der Polynome und die Bedeutung der Reste.

## Vergleich und Reproduktion

Der Kerntest erzeugt mit festem Startwert 250 Anfragen mit Moduln zwischen eins
und 58 und jeweils einem bis sieben vorzeichenbehafteten Koeffizienten bis
130 Bit. Seine Vergleichsrechnung summiert für jeden Parameter die einzelnen
Potenzen mit exakter `BigInteger`-Arithmetik und reduziert erst die Differenz
zum Zielrest. Weitere Prüfungen decken unter anderem \(x^2\equiv1\pmod 8\)
mit vier Wurzeln, die doppelte Wurzel von \((x-1)^2\) modulo fünf und die leere
Lösungsmenge von \(x^2\equiv-1\pmod 3\) ab. Veränderte Quellen, ausgelassene
oder doppelte Werte, erschöpfte Ressourcen und Abbruch prüfen die Grenzen
des Vertrags. Der SDK-Test löst und prüft \(1+2x\equiv5\pmod{12}\) mit
den Resten zwei und acht.

Mit Java 25 und Maven ab 3.9.9 aus der 3.9-Reihe im Repository-Stamm

```bash
mvn -B -ntp -pl regelsuche-math-algorithms,regelsuche-optimization-sdk -am \
  -Dtest=PolynomialCongruenceTest,PolynomialCongruenceSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
