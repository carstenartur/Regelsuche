# Praktische vollständige Faktorisierung über großen Primmoduln

Der zusätzliche Einstieg `LargePrimeFactorization.factor` zerlegt ein
Nichtnull-Polynom in einer Variablen in seine Einheit und verschiedene normierte
irreduzible Faktoren mit positiven Vielfachheiten. Der Modul kann tatsächlich
ein `BigInteger` sein: weder der Modul noch die Koeffizienten werden für die
Arithmetik auf `int` oder `long` verengt. Der bisherige `PrimeField`- und
Berlekamp-Pfad bleibt unverändert. Die SDK-Überladungen in
`PrimeFieldFactorizationSdk` delegieren an den neuen Rechenkern beziehungsweise
an `LargePrimeFactorizationVerifier`.

Dies ist eine eigene Implementierung der bekannten Quadratfrei-/DDF-/EDF-
Pipeline. Die Struktur ist beispielsweise in der
[offiziellen FLINT-Algorithmendokumentation](https://flintlib.org/doc/algorithms/polynomials/factoring-finite-fields.html)
und in der [NTL-Schnittstelle `CanZass`](https://libntl.org/doc/ZZ_pXFactoring.cpp.html)
beschrieben. Es wurde kein fremder Implementierungscode übernommen. Der neue
uniforme deterministische Algorithmus aus Familie 142 wird damit nicht
implementiert und seine Bitkomplexität wird nicht beansprucht.

## Der Primzahlvertrag ist sichtbar

`BigPrimeField.of(int)` verwendet dieselbe deterministische Primzahlprüfung wie
`PrimeField.of(int)`. `BigPrimeField.assumingPrime(BigInteger)` deklariert dagegen
ausdrücklich die Hypothese, dass der angegebene Modul prim ist. Die zweite
Fabrik bleibt auch dann bei `ASSUMED_PRIME`, wenn ihr ein kleiner tatsächlich
primzahliger Modul übergeben wird. Modul und Primzahlvertrag gehören zum
stabilen Domain-Identifier sowie zu `equals`/`hashCode`. Beide neuen Domain-
Varianten sind von den bisherigen `PrimeField`-Instanzen getrennt.

Die Fabrik verwirft Zahlen kleiner als zwei, gerade zusammengesetzte Zahlen und
zusammengesetzte Zahlen im deterministisch prüfbaren `int`-Bereich. Bei größeren
ungeraden Moduln führt der begrenzte Algorithmus einen budgetierten
Miller-Rabin-Gegenbeweistest mit den Basen 2, 3, 5, 7, 11, 13 und 17 aus. Ein
Zeuge widerlegt die Primzahlannahme; das Bestehen dieser Tests beweist sie nicht.
Die Feldhypothese wird dadurch niemals hochgestuft. Das Prüfresultat lautet:

| Status | Bedeutung |
| --- | --- |
| `COMPLETE` | Vollständige Faktoren wurden unabhängig geprüft; der Eingabevertrag enthält die deterministische kleine Primzahlprüfung. |
| `COMPLETE_CONDITIONAL` | Dieselben algebraischen Prüfungen bestanden; die Folgerung gilt unter der ausdrücklich mitgeführten Primzahlhypothese. |
| `INVALID_EVIDENCE` | Das eingereichte Zertifikat widerspricht der Quelle, den Faktoranforderungen, der Irreduzibilitätsprüfung oder dem Gesamtprodukt. |
| `UNSUPPORTED` | Die Eingabe ist kein unterstützter univariater Primkörper, der Primzahlannahme wurde widersprochen oder die Rechnung fand ein nicht invertierbares Feldelement. |
| `INCONCLUSIVE` | Arbeit, Speicherreservierungen, Modulbits, Zeit, Versuchszahl oder Abbruchanforderung verhindern eine vollständige Antwort. |

Nur die beiden vollständigen Statuswerte tragen ein Zertifikat. Ein
zusammengesetzter Modul, der die endlichen Gegenbeweistests besteht, bleibt eine
unbewiesene und gegebenenfalls falsche Eingabehypothese. Das API ist keine
Primzahlbeweisbibliothek.

Der Prüfer bindet das Zertifikat an das genaue Quellpolynom, die Variablen, die
Monomordnung und den tatsächlichen typisierten Koeffizientenvertrag. Er
vergleicht zusätzlich die unveränderlichen Feldobjekte, weil die allgemeine
`PolynomialRing`-Gleichheit Domain-IDs vergleicht. Eine benutzerdefinierte Domain
kann den Vertrag daher nicht durch Kopieren einer fremden ID ersetzen.

## Verfahren und unabhängige Prüfung

Die Suche normiert zuerst mit dem Leitkoeffizienten und bewahrt diesen als
Einheit. Ableitung und GGT zerlegen das normierte Polynom in quadratfreie
Vielfachheitsschichten. Ein verbleibender inseparabler Rest wird durch seine
Frobenius-Wurzel auf kleinere Exponenten abgebildet; die Vielfachheit wird mit
$p$ multipliziert. Die Implementierung wandelt $p$ erst dann in einen
Exponentenindex um, wenn zuvor $p\leq\deg(f)$ festgestellt wurde. Für einen
nichtkonstanten $p$-Potenzrest ist diese Bedingung notwendig. So entsteht bei
großen Moduln kein übergelaufener `int`-Charakteristikwert.

Die Distinct-Degree Factorization (DDF) berechnet die Frobeniusfolge durch
wiederholtes Potenzieren mit $p$ modulo dem jeweils verbleibenden Polynom.
$\gcd(f,x^{p^d}-x)$ liefert die jeweilige Gradschicht. Keine Schleife läuft über
alle $p$ Körperreste, und ein Polynom vom Grad $p^d$ wird nicht angelegt.

Für Equal-Degree Factorization (EDF) werden zufällige Polynome mit gleichmäßig
per Zurückweisung gezogenen Koeffizienten benutzt. Ein erster GGT kann bereits
einen echten Teiler liefern. Bei ungeradem $p$ berechnet der Cantor-Zassenhaus-
Schritt

\[
 a^{(p^d-1)/2}
 =\prod_{i=0}^{d-1}\left(a^{(p-1)/2}\right)^{p^i}\pmod f.
\]

Die rechte Seite benötigt nur Exponenten von höchstens der Bitlänge von $p$
und reduzierte Polynome. Insbesondere wird auch der ganzzahlige Exponent
$p^d$ nicht materialisiert. Bei $p=2$ verwendet die Suche die Spur
$a+a^2+\cdots+a^{2^{d-1}}$. Die weitere Zerlegung benutzt einen expliziten
Arbeitsstapel. Alle Schichten und Teilprobleme teilen eine einzige
Versuchsgrenze. Der Seed macht die ausgeführten Zufallsversuche reproduzierbar;
er macht aus der Suche keinen deterministischen Polynomialzeit-Algorithmus.
Ein nicht aufgespaltener Rest ist kein Irreduzibilitätsbeweis.

Die Schlussprüfung verwendet eine getrennte Implementierung dichter
`BigInteger[]`-Arithmetik. Sie ruft weder die Sucharithmetik über
`UnivariatePolynomialView` noch DDF/EDF auf. Sie prüft Quellenbindung, Einheit,
Normiertheit, positive und gradverträgliche Vielfachheiten, verschiedene Faktoren
und das vollständige Produkt. Für jeden Faktor vom Grad $n$ verwendet sie den
[Rabin-Test](https://flintlib.org/doc/algorithms/polynomials/irreducibility.html):

\[
 x^{p^n}\equiv x\pmod f,\qquad
 \gcd(f,x^{p^{n/r}}-x)=1\quad\text{für jeden Primteiler }r\mid n.
\]

Auch hier wird die Frobeniusfolge schrittweise modulo $f$ berechnet. Nur der
kleine ganzzahlige Polynomgrad muss faktorisiert werden; eine Faktorisierung
von $p-1$ wird nicht verlangt. Externe Zertifikate laufen durch genau denselben
Prüfer. Der Prüfer benötigt keine Berlekamp-Matrix und keine
Feldelement-Enumerationsgrenze. `maxCandidates == 0` schaltet die Suche aus,
verhindert aber nicht die Prüfung eines bereits vorliegenden Zertifikats.

## Grenzen und Kostenrechnung

`LargePrimeFactorizationVerifier.Policy` enthält eine eigene
`maxModulusBitLength`, `maxAllocatedCoefficientSlots` und `maxNanos`. Die
Modulbitgrenze wird vor teuren Rechnungen geprüft: kleine Koeffizienten einer
Eingabe sind ausdrücklich kein Ersatz für diese Grenze.
`LargePrimeFactorization.Policy` ergänzt diese Ressourcen um eine gesamte
`maxSplitAttempts`-Grenze und `randomSeed`. Die strukturellen Quellgrenzen und
das gesamte Arbeitslimit kommen aus `FactorizationRequest`.

Ein nicht zurücksetzbares Budget gilt vom Eintritt in die Suche bis nach der
unabhängigen Schlussprüfung. Arbeit für die erneute Quellenprüfung und den
erneuten Primzahl-Gegenbeweistest zählt ebenfalls. Ein separat aufgerufener
Prüfer erhält sein eigenes Budget. Abbruch und Thread-Unterbrechung werden vor
Reservierungen, in Rechenschleifen und unmittelbar vor Ausgabe eines positiven
Resultats abgefragt. Die abgelaufene Zeit wird mit `System.nanoTime` erfasst.
Auch Konstruktionsarbeit wird unterbrochen: die geprüften Überladungen von
`UnivariatePolynomialView.from`, `of` und `toSparsePolynomial` reichen den
optionalen `PolynomialWorkSink.checkpoint` durch Nullfüllung, Kanonisierung,
Kopien, Abschneiden von Endnullen und beide Konvertierungsrichtungen. Die
Schleifen prüfen spätestens nach 1.024 Einträgen sowie an Phasengrenzen.
Rechenergebnisse verwenden denselben geprüften Konstruktor. Der unabhängige
Prüfer kontrolliert seine eigenen Füll-, Kopier- und Vergleichsschleifen
entsprechend. Diese Kontrollpunkte addieren keine bereits vorausbezahlte Arbeit
oder Speicherreservierungen; vorhandene Sink-Implementierungen behalten durch
die standardmäßig leere Methode ihre bisherigen Buchungsereignisse.

Die Begrenzung ist kooperativ: einzelne bereits gestartete JVM-Arrayallokationen
oder durch die Modulbitgrenze begrenzte `BigInteger`-Operationen werden nicht
intern unterbrochen. Auch Betriebssystemplanung kann ein Zeitlimit überziehen.

Die Speichergrenze ist eine harte Grenze kumulierter Koeffizienten-
Reservierungen, keine Messung der JVM-Heap-Bytes. Vor dichten Vektoren werden
deren Größen und Kopien reserviert; elementare Rechenschritte reservieren
zusätzlich konservative Plätze für skalare Zwischenwerte. Ein Zwischenwert der
modularen Multiplikation/Addition hat höchstens $2b+1$ Bits bei $b$ Modulbits.
Da die Reservierungen nie freigegeben werden, begrenzt die kumulierte Größe
auch die während eines Aufrufs lebenden neu angelegten Koeffizienten. Das
Verfahren kann deshalb früher abbrechen als eine genaue Lebensdaueranalyse.
Bereits vom Aufrufer erzeugte Eingabeobjekte gehören nicht zu diesen
Allokationen; ihre Struktur wird vor der dichten Konvertierung begrenzt.

Die Arbeitsrechnung gewichtet elementare Operationen mit
$\lceil b/32\rceil^2$. Eine Inversion wird zusätzlich mit $b$ modularen
Operationseinheiten reserviert. Diese reproduzierbare Kostenrechnung deckt
Suche, Konvertierung und Prüfung ab. Sie ist kein gemessener JVM-Zeitgewinn und
kein Beweis einer asymptotischen Bitkostenschranke für die Bibliothek.

## Beispiel über einem Modul mit 381 Bits

```java
var p = new BigInteger(
    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eab"
  + "fffeb153ffffb9feffffffffaaab", 16);
var ring = new PolynomialRing<>(BigPrimeField.assumingPrime(p),
    List.of(new PolynomialVariable("x")),
    PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
var source = UnivariatePolynomialView.of(ring,
    List.of(BigInteger.valueOf(-6), BigInteger.valueOf(11),
            BigInteger.valueOf(-6), BigInteger.ONE)).toSparsePolynomial();
var request = FactorizationRequest.independentComplete(source,
    new FactorizationRequest.StructuralLimits(1, 20, 21, 512), 1, 500_000_000);
var resources = new LargePrimeFactorizationVerifier.Policy(
    512, 100_000_000, 15_000_000_000L);
var policy = new LargePrimeFactorization.Policy(resources, 256, 142);
var result = PrimeFieldFactorizationSdk.factor(request, policy, CancellationToken.NONE);
// COMPLETE_CONDITIONAL: (x-1)(x-2)(x-3), jede Vielfachheit gleich eins.
```

Die Aussage ist absichtlich an die im Ring gespeicherte Primzahlhypothese
gebunden. Mit einem deterministisch validierten kleinen `PrimeField` oder
`BigPrimeField.of(int)` lautet der vollständige Status `COMPLETE`.

## Kontrollen und Referenzvergleich

Die Tests kontrollieren Einheiten, Gradschichten, verschiedene irreduzible
Faktoren und wiederholte Faktoren bei Moduln mit genau 64, 128, 256 und 381
Bits. Die Grad-sieben-Familie ist
$7(x-2)^3(x^2-a)^2$ mit $a=2,5,3,2$ für die folgenden Moduln:

| Bits | Modul |
| --- | --- |
| 64 | $2^{64}-59$ |
| 128 | $2^{128}-159$ |
| 256 | $2^{256}-2^{32}-977$ |
| 381 | Der hexadezimale Modul des Beispiels |

Weitere Fälle prüfen vollständig gespaltene Polynome, zwei verschiedene
irreduzible quadratische Faktoren, die binäre Spurzerlegung sowie rein
inseparable Potenzen in Charakteristik zwei und drei. Alle 143
Nichtnull-Polynome über F2 bis Grad fünf und F3 bis Grad drei werden neu
faktorisiert. Eine separate kleine ganzzahlige Probedivision modulo $p$ testet
jeden ausgegebenen Faktor gegen alle normierten möglichen Teiler bis zum halben
Grad. Eine vom Suchcode getrennte Produktrechnung rekonstruiert jede Quelle.

Im zusätzlichen lokalen Referenzlauf wurden neun Resultate mit
**SymPy 1.14.0 `factor_list(Poly(..., modulus=p))`** verglichen. Quelle und
Java-Ausgabe wurden als Koeffizientenarrays übertragen; Einheit, kanonische
Faktoren und Vielfachheiten stimmten in allen neun Fällen überein. Dazu gehören
auch die vorher nicht in den Java-Erwartungslisten festgelegten Zerlegungen von
$x^8+x^4+x^2+1$ über F2, F3 und F7. Der Java-Test über F5 hält eine weitere
SymPy-Referenzzerlegung als explizite Koeffizienten fest. SymPy ist keine neue
Laufzeit- oder CI-Abhängigkeit. Der Vergleich prüft Resultate; er behauptet
keinen Geschwindigkeitsvorteil.

Mit Seed 142, höchstens 256 Splitversuchen, 512 Modulbits und den obigen
Arbeits-/Reservierungsgrenzen wurden folgende mechanische Kosten beobachtet:

| Fall | Gesamte Suche und Prüfung | Separate Prüfung | Kumulierte Koeffizientenplätze | Splitversuche |
| --- | ---: | ---: | ---: | ---: |
| Gemischt, 64 Bits | 30.844 | 19.276 | 128.218 | 0 |
| Gemischt, 128 Bits | 242.832 | 153.984 | 251.969 | 0 |
| Gemischt, 256 Bits | 1.933.248 | 1.235.136 | 501.096 | 0 |
| Gemischt, 381 Bits | 4.761.648 | 2.956.896 | 547.091 | 0 |
| Drei lineare Faktoren, 381 Bits | 15.741.648 | 628.560 | 1.905.970 | 6 |
| Zwei quadratische Faktoren, 381 Bits | 90.295.632 | 6.851.952 | 10.630.210 | 6 |
| Grad acht über F2 | 555 | 357 | 9.572 | 0 |
| Grad acht über F3 | 6.615 | 1.079 | 111.587 | 2 |
| Grad acht über F7 | 18.052 | 2.451 | 301.802 | 3 |

Negative Tests verfälschen Quelle, Domain, Einheit, Faktoren und
Vielfachheiten, reichen einen reduzierbaren oder nicht normierten Faktor ein
und wiederholen denselben Faktor. Ein Test kopiert eine vertrauenswürdige
Domain-ID in einen anderen Koeffizientenvertrag. Weitere Tests begrenzen
Modulbits bei winzigen Eingabekoeffizienten, erzwingen einen übergroßen
Exponentenindex, erschöpfen Splitversuche, Zeit oder Allokationen und brechen
unmittelbar beziehungsweise an der letzten Abfrage ab. Sowohl der einzelne
Prüfer als auch die kombinierte Suche bestehen ihr exaktes Arbeitslimit und
bleiben mit einer Einheit weniger offen. Eine zusammengesetzte große ungerade
Zahl erhält nach dem Gegenbeweis kein Zertifikat.

Eine unabhängige Prüfung fand zunächst einen längeren ungeprüften Abschnitt
beim Laden sehr dünn besetzter Polynome in die dichte Darstellung. Sie maß für
$x^{8.000.000}+1$ über F2 bei einem Zeitlimit von 5 ms ungefähr 680 ms bis zum
Abbruch. Nach der Korrektur lieferte derselbe lokale Reproducer `TIME_LIMIT`
nach 11,501 ms und eine späte Abbruchanforderung nach 0,114 ms. Die Pollnummern
haben sich durch die zusätzlichen Kontrollpunkte verschoben; sieben
zeitunabhängige Regressionstests messen deshalb direkt, dass jede betroffene
Konstruktionsphase vorzeitig beendet wird. Es gibt keine feste Gradkappung als
Ersatz für Abbruchprüfungen. Alle neun obigen Kostenzeilen sind einschließlich
Allokationszähler und Versuchszahl exakt gleich geblieben. Der letzte
betroffene Java-Lauf enthält 51 bestandene Tests; alle 15 geänderten
Produktions-/Testdateien bestehen zusätzlich `javac -Xlint:all -Werror`.

Reproduktion der Java-Kontrollen mit Java 25:

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=UnivariatePolynomialCheckpointTest,UnivariatePolynomialViewTest,SparsePolynomialTest,PolynomialDivisionBoundaryReviewTest,BigPrimeFieldTest,LargePrimeFactorizationTest,LargePrimeFactorizationVerifierTest,LargePrimeFactorizationSdkTest,PrimeFieldMultiplicityTest,PrimeFieldTest,PrimeFieldArithmeticTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.failOnWarning=true test
```

Der im Test verwendete Seed, die exakten Modulkonstanten und die expliziten
Referenzfaktoren stehen in `LargePrimeFactorizationTest`. Die unabhängigen
Negativfälle stehen in `LargePrimeFactorizationVerifierTest`. Ein vollständiger
Aufruf muss auch bei ausgeschöpften Ressourcen ohne positives Zertifikat
enden; kleine Messreihen begründen keine allgemeine Laufzeit- oder
Vollständigkeitszusage unter einem festen Suchbudget.
