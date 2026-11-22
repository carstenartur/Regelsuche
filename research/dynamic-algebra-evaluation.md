# Dynamische Auswertung über quadratfreien Quotientenalgebren

Ein Auftrag besteht aus einer positiven Primzahl $p$ im Java-`int`-Bereich,
einem nichtkonstanten quadratfreien Polynom $F$ und einem typisierten
Ausdruck über $\mathbb F_p[T]/(F)$. Koeffizientenlisten beginnen beim
konstanten Term. Die Eingabe darf negative oder nicht reduzierte Koeffizienten
enthalten. Der Kern reduziert sie modulo $p$, normiert $F$ auf Leitkoeffizient
eins und prüft $\gcd(F,F')=1$.

`Literal` bezeichnet ein ganzes Polynom, etwa `[0,1]` für $T$.
`Add` und `Product` werten von links nach rechts aus. Ein erreichter
undefinierter Operand macht die betreffende Komponente undefiniert.
`Inverse` fordert eine Einheit. `IfZero` wertet die Bedingung aus und
verfolgt auf jeder Komponente nur den ausgewählten Ausdruck.
Die Auswertung eines undefinierten Bedingungsausdrucks ist selbst undefiniert.

## Teilung und Zertifikat

Für einen erreichten Rest $a$ und aktuellen Modul $M$ berechnet der Kern
$g=\gcd(M,a)$. Auf dem Faktor $g$ verschwindet $a$. Auf $M/g$ ist $a$
eine Einheit, weil $M$ quadratfrei ist. Der erweiterte euklidische Algorithmus
liefert dort einen inversen Rest $b$. Faktoren vom Grad null entfallen.
Der Nullzweig eines Inversen endet mit `Optional.empty()`. Ein definierter
Nullwert besitzt dagegen `Optional.of(List.of())`. Beide Teilrechnungen
behalten ihren bisherigen Verlauf. Eine spätere Aufteilung projiziert frühere
Inversen auf den jeweiligen Endfaktor.

Ein Zertifikat enthält die ursprüngliche Anfrage und für jede Komponente

- den normierten Endfaktor,
- den definierten Rest oder die explizite Undefiniertheit,
- in Auswertungsreihenfolge je eine Nullentscheidung oder einen inversen
  Rest für jeden erreichten Test und jedes erreichte Inverse.

Der getrennte Prüfer vergleicht die Anfrage strukturell, prüft
Teilerfremdheit jedes Faktors zum bisherigen Produkt und gleicht das
Gesamtprodukt mit dem normierten Ausgangsmodul ab. Auf jedem vorgegebenen
Faktor wertet er den ursprünglichen Baum erneut aus. Ein Nullzeuge erfordert
den Nullrest. Ein Einheitenzeuge erfordert $ab\equiv1\pmod M$. Ungenutzte
oder fehlende Zeugen und abweichende Ausgabewerte führen zur Ablehnung.
Daher kann auch eine unabhängig erzeugte feinere Zerlegung akzeptiert werden.

Der algebraische Bezug ist die simultane Auswertung über endlichen Körpern
und ihren Produktkomponenten. Die Implementierung liefert eine Auswertung für
den angegebenen Baum und die angegebene Quotientenalgebra. Ein Endfaktor
kann mehrere irreduzible Faktoren zusammenfassen, wenn die erreichten
Entscheidungen sie gleich behandeln. Der vollständige Faktorisierungsalgorithmus
der Quellenfamilie ist eine weitergehende Aufgabe.
[Quelle 142, Lemma ff:simultaneous im fixierten Quellstand](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Deterministic-Polynomial-Factorization-over-Prime-Fields-October-4-2026/build/sections/02-finite-fields.tex)

## Ausführbares SDK-Beispiel

In JShell mit den SDK- und Kernklassen im Klassenpfad:

```java
import java.math.BigInteger;
import java.util.*;
import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import de.regelsuche.sdk.optimization.*;
import de.regelsuche.sdk.optimization.polynomial.DynamicAlgebraSdk;
List<BigInteger> poly(long... c) { return Arrays.stream(c).mapToObj(BigInteger::valueOf).toList(); }
var req = new Request(5, poly(-1,0,1), new Inverse(new Literal(poly(-1,1))));
var budget = new OptimizationBudget(100_000, 2_000, 1, 2000);
var cert = DynamicAlgebraSdk.evaluate(req, budget, CancellationToken.NONE).value().orElseThrow();
cert.branches().forEach(b -> System.out.println(b.factor()+" => "+b.value()));
System.out.println(DynamicAlgebraSdk.verify(req, cert, budget, CancellationToken.NONE).status());
```

| Faktor | Eingesetztes $T$ | $T-1$ | Ergebnis des Inversen |
|---|---:|---:|---|
| $T-1$, Liste `[4, 1]` | 1 | 0 | undefiniert |
| $T+1$, Liste `[1, 1]` | 4 | 3 | 2 |

## Ressourcen und Prüfvergleich

Ein gemeinsames `CertificateWork`-Konto erfasst Konstruktion und abschließende
Prüfung. Primzahlprüfung, Formelvorkommen, Koeffizientengrößen und Operationen
der vorhandenen `PrimeField`-/`UnivariatePolynomialView`-Arithmetik werden
verbucht. Die unterstützte Baumtiefe ist 64. Wiederverwendete Teilbäume zählen
pro Vorkommen. Die Bitgrenze für eingehende ganze Zahlen beträgt 65.536.
Die Feldarithmetik begrenzt reduzierte Koeffizienten auf weniger als $p$.

Vor der Auswertung gilt eine konservative Zellreserve. Für Grad $d$, $n$
Formelvorkommen und maximale Eingabepolynombreite $w$ enthält sie zusätzlich
zu den Eingabezellen $24w+6(d+1)^2(n+1)$ Plätze für Endkomponenten,
Zwischenergebnisse und Verläufe. Der Prüfer berücksichtigt die vorgelegten
Zertifikatszellen zusätzlich. Bei kleinen Speichergrenzen kann diese Reserve
bereits vor einer tatsächlichen Teilung erschöpft sein. Die Zellzahl misst
logischen Arbeitsspeicher. Das Erzeugen typisierter, unveränderlicher
Auftrags- und Zertifikatsdaten ist ein vorgelagerter Schritt. Das SDK reicht
`maximumStates` als Zellgrenze, die Arbeitsgrenze, die Frist und das
Abbruchsignal weiter. Ein Ressourcenende ergibt `INCONCLUSIVE` ohne
Teilzertifikat oder mathematische Aussage. `REJECTED` kennzeichnet unzulässige
Eingabedomänen oder eine festgestellte Abweichung im vorgelegten Zertifikat.

Die Tests vergleichen alle Elemente von $\mathbb F_2$, $\mathbb F_3$,
$\mathbb F_5$ und $\mathbb F_7$ mit einer direkten skalaren Rechnung. Bei
40 weiteren erzeugten Bäumen wird der Wert bzw. die Undefiniertheit an jeder
Stelle in $\mathbb F_3$ oder $\mathbb F_5$ unabhängig interpretiert.
Im Beispiel $F=T(T^2+2)$ über $\mathbb F_5$ kontrolliert eine symbolische
Handrechnung zusätzlich die Komponente vom Grad zwei ohne Basiskörperwurzel.
Kontrollen verändern Quelle, Faktorüberdeckung, Entscheidung, Inversen und
Ausgabe oder brechen vor dem Abschluss der unabhängigen Prüfung ab.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=DynamicAlgebraEvaluationTest,DynamicAlgebraSdkTest,UnivariatePolynomialAlgorithmsTest,SquareFreeDecompositionTest,CertificateWorkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
