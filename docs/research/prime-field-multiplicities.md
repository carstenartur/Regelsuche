# Vollständige Faktorisierung mit Vielfachheiten über Primkörpern

`PrimeFieldFactorizationSdk.factor(request, policy, token)` zerlegt ein
von null verschiedenes univariates Polynom über einem ausdrücklich
angegebenen `PrimeField`. Es liefert den Leitkoeffizienten als Einheit und
die verschiedenen normierten irreduziblen Faktoren mit ihren positiven
Vielfachheiten. Eine von null verschiedene Konstante hat eine leere
Faktorliste. Die gemeinsame Klasse `FactorizationRequest` schließt das
Nullpolynom bereits beim Erzeugen aus. Der Rechenkern heißt
`PrimeFieldMultiplicity`. Der vorhandene Einstieg
`FiniteFieldFactorization.factorSquareFree` verlangt weiterhin
quadratfreies, nichtkonstantes Eingangsmaterial für seine
Berlekamp-Kernzertifikate und die nachfolgende Hensel-Hebung.

## Zerlegung und Prüfung

Nach der Leitkoeffizientennormierung berechnet das Verfahren
$g=\gcd(f,f')$ und $w=f/g$. In Runde $i$ berechnet es $y=\gcd(w,g)$.
Der Quotient $w/y$ ist die Schicht der Faktoren mit exakter
Vielfachheit $i$ für die im aktuellen Rekursionsschritt sichtbaren Faktoren.
Dann ersetzt der Algorithmus $(w,g)$ durch $(y,g/y)$.
Bei $w=1$ ist der verbliebene Rest eine $p$-te Potenz. Die
Frobenius-Wurzel übernimmt die Koeffizienten der Potenzen $t^{pj}$ bei
Exponent $j$, prüft das Verschwinden der dazwischenliegenden Koeffizienten
und multipliziert die Vielfachheiten der rekursiven Zerlegung mit $p$.
Das gilt auch, wenn schon zu Beginn $f'=0$ ist.

Die vorhandenen Berlekamp-Routinen zerlegen jede nichttriviale
quadratfreie Schicht. Anschließend kontrolliert ein von der Schichtsuche
unabhängiger Prüfer die Quellbindung, Einheit, Normiertheit, Eindeutigkeit,
Rabin-Irreduzibilität und das vollständige Produkt mit allen Vielfachheiten.
Ein falsches Zertifikat kann auch extern über
`PrimeFieldFactorizationSdk.verify(request, policy, certificate, token)`
eingereicht werden. Das Urteil `INVALID_EVIDENCE` unterscheidet sich von
`INCONCLUSIVE` bei Ressourcenabbruch und von `UNSUPPORTED` bei einer
ungeeigneten Ringstruktur. Nur `COMPLETE` trägt geprüfte Faktoren.

## Kontrollen und Kosten

In $\mathbb F_2[t]$ liefert $t^7+t^6+t+1$ die Faktoren $(t+1)^3$ und
$(t^2+t+1)^2$. Bei einem Arbeitsbudget von einer Million, einer
Feldelementgrenze von fünf und einer Matrixzellgrenze von 4.096
benötigt der vollständige Aufruf einschließlich der Schlussprüfung 610
Arbeitseinheiten. Die separate erneute Zertifikatsprüfung benötigt 194.
Der Arbeitszähler erfasst die geladenen Koeffizienten, die
Schichtzerlegung, die Kosten des vorhandenen Berlekamp-Kerns, die
Faktorkonvertierung, die Rabin-Tests und die Produktrechnung.
Alle Schritte teilen dasselbe Arbeitsbudget und prüfen Abbruchanforderungen.

Der Test erzeugt alle 63 von null verschiedenen Binärpolynome bis Grad
fünf und alle 80 entsprechenden ternären Polynome bis Grad drei. Er
multipliziert die ausgegebenen Faktoren wieder aus und testet ihre
Irreduzibilität mit separater ganzzahliger Probedivision modulo $p$
durch alle normierten Teiler bis zum halben Grad. Kontrollfälle reichen
falsche Einheiten, Quellen, Faktoren, doppelte Einträge und gefälschte
Vielfachheiten ein.

Der Algorithmus verwendet die bestehende begrenzte
`FiniteFieldFactorizationPolicy`: Die Primzahl muss die Enumerationsgrenze
einhalten und $3\deg(f)^2$ in das Zellenbudget passen. Berlekamp-Splitting
kann alle $p$ Körperreste durchlaufen. Die für Familie 142 beanspruchte
Bitkomplexität polynomial in $\log(p)$ und Grad folgt daraus nicht. Der
lokale Arbeitstest misst keine Bitkomplexität und keinen
Geschwindigkeitsvorteil gegenüber anderen Faktorisierern.
[Manuskript, Familie 142](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Deterministic-Polynomial-Factorization-over-Prime-Fields-October-4-2026/Deterministic-Polynomial-Factorization-over-Prime-Fields.pdf)

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=PrimeFieldMultiplicityTest,MultiplicitySdkTest,FiniteFieldFactorizationTest,HenselLiftingTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
