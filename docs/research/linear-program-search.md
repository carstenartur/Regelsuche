# Rationale LP-Zeugen suchen und affine Folgerungen verwenden

`LinearProgramSearch` erzeugt für das rationale Maximierungsproblem

\[
\max c^T x\quad\text{unter}\quad Ax\leq b
\]

einen Unzulässigkeitsbeleg, ein primal-duales Optimum oder einen zulässigen
Strahl mit Einheitsgewinn. Die Suche verwendet einen klassischen exakten
Zweiphasen-Simplex. Jede erfolgreiche Ausgabe wird anschließend durch
`LinearProgramVerifier` auf dem ursprünglichen Tripel `(A,b,c)` geprüft.
`AffineImplication` verwendet diese Suche tatsächlich, um aus den Zeilen
`Ax <= b` eine weitere Ungleichung `c^T x <= d` abzuleiten oder eine konkrete
Gegenbelegung zu erzeugen.

## Vertrag und mathematische Aussagen

Die unveränderliche Quelle ist `LinearProgramCertificates.Problem` mit
`ExactRational`-Koeffizienten, mindestens einer Zeile und einer Spalte.
Variablen sind vorzeichenfrei. Eine Gleichung lässt sich als zwei
Ungleichungen eingeben; eine vollständig freie zulässige Menge lässt sich
durch die tautologische Zeile `0 <= 0` darstellen. Zeilen und Spalten behalten
ihre ursprüngliche Reihenfolge in allen ausgegebenen Vektoren.

| Zertifikat | Unabhängig nachgerechnete Bedingungen | Aussage |
| --- | --- | --- |
| `Infeasible(source,y)` | `y >= 0`, `A^T y = 0`, `b^T y < 0` | Die Nebenbedingungen sind widersprüchlich. Die Suche normiert zusätzlich auf `b^T y = -1`. |
| `Optimal(source,x,y)` | `Ax <= b`, `y >= 0`, `A^T y = c`, `c^T x = b^T y` | `x` erreicht das globale Maximum. |
| `Unbounded(source,x,v)` | `Ax <= b`, `Av <= 0`, `c^T v = 1` | `x + t v` ist für alle `t >= 0` zulässig und verbessert das Ziel um `t`. |

Die Bedingungen und ihre Folgerungen werden auch im bestehenden
[Zertifikatsvertrag](linear-program-certificates.md) beschrieben. Insbesondere
bindet auch ein Unzulässigkeitsbeleg die vollständige Quelle einschließlich
der Zielfunktion. `VERIFIED` bezeichnet ausschließlich eine erfolgreich
nachgerechnete Ausgabe. `UNAVAILABLE` enthält kein Zertifikat und lässt die
mathematische Klassifikation offen.

## Zweiphasen-Simplex und Rückübersetzung

Jede freie Variable wird als `x_j = z_j - z_(n+j)` mit zwei nichtnegativen
Variablen dargestellt. Die Ungleichungen erhalten ursprüngliche
Schlupfvariablen:

\[
[A,-A,I]z=b,\qquad z\geq0.
\]

Eine Zeile mit negativer rechter Seite wird einschließlich ihres
Schlupfkoeffizienten mit `-1` multipliziert. Für jede Zeile kommt eine
künstliche Variable hinzu. Deren Einheitsmatrix liefert eine unmittelbar
zulässige Anfangsbasis für das Hilfsproblem. Phase eins maximiert das
Negative der Summe aller künstlichen Variablen.

Ein negativer optimaler Hilfswert liefert den Farkas-Multiplikator. Bei
Hilfswert null haben alle noch basischen künstlichen Variablen den Wert
null. Sie werden durch Pivots auf nichtkünstliche Spalten aus der Basis
entfernt. Solche Spalten existieren: Die ursprünglichen Schlupfspalten
machen `[A,-A,I]` auch bei redundanten oder degenerierten Eingabezeilen
vollzeilenrangig. Ein Pivot in einer Zeile mit rechter Seite null bewahrt
die Zulässigkeit unabhängig vom Vorzeichen des gewählten Koeffizienten.
Danach optimiert Phase zwei die ursprüngliche Zielfunktion; künstliche
Variablen dürfen nicht mehr eintreten.

Der Tableauzustand enthält `B^-1 M`, `B^-1 b`, die Basisindizes und die
negativen reduzierten Kosten. Die ursprünglich vorzeichenbehafteten
Schlupfspalten bewahren die Transformation der Originalzeilen. Ihre
Zielfunktionskoeffizienten sind deshalb unmittelbar die Multiplikatoren
`y` der Originalquelle. Für einen unbeschränkten Eintritt ist die
Standardformrichtung eins in der eintretenden Variablen und das Negative
der Eintrittsspalte in den Basisvariablen. Nach Rückübersetzung aus
`x+ - x-` wird sie auf `c^T v = 1` skaliert.

Beide Optimierungsphasen wählen den kleinsten Index mit verbessernden
reduzierten Kosten. Bei gleichem minimalem Quotienten verlässt die Variable
mit dem kleinsten Basisindex die Basis. Diese Bland-Regel vermeidet Zyklen
auch bei degenerierten Pivots. Die Suche setzt keine Ecke im ursprünglichen
Variablenraum voraus: Gerade vorzeichenfreie Variablen, Streifen, affine
Unterräume und Linealität werden durch die Standardform erfasst.

Die Implementierung ist ein dichter klassischer Simplex mit potenziell
exponentiell vielen Pivots und wachsenden rationalen Zwischenwerten. Sie
implementiert keine der neuen streng polynomialen LP-Komplexitätsaussagen.
Es wird kein Laufzeitvorteil gegenüber etablierten LP-Paketen behauptet.

## Der tatsächliche Verbraucher: affine Implikation

`AffineImplication.Request(problem, upperBound)` fragt nach

\[
Ax\leq b\quad\Longrightarrow\quad c^T x\leq\mathrm{upperBound}.
\]

Affine Konstanten stehen auf den rechten Seiten: `a^T x + alpha <= beta`
wird als `a^T x <= beta - alpha` eingegeben. Die vollständige typisierte
Anfrage `(A,b,c,upperBound)` ist die Quelle jedes Belegs. Änderungen an
Nebenbedingungen, Zielfunktion oder Schlussgrenze machen einen zuvor
erzeugten Beleg für die geänderte Anfrage ungültig.

| Suchausgabe | Beleg | Nachprüfung |
| --- | --- | --- |
| `PROVED` | `Proof(request, Infeasible(...))` | Widersprüchliche Prämissen erlauben die Folgerung; der Farkas-Beleg wird geprüft. |
| `PROVED` | `Proof(request, Optimal(...))` | Das geprüfte Maximum ist höchstens die Schlussgrenze. |
| `REFUTED` | `Counterexample(request,x)` | Alle ursprünglichen Prämissen gelten, aber `c^T x` liegt strikt über der Schlussgrenze. |
| `UNAVAILABLE` | Kein Beleg | Das gemeinsame Budget genügte nicht für die vollständige Suche und Prüfung. |

Liegt ein endliches Maximum oberhalb der Grenze, liefert dessen Primalpunkt
die Gegenbelegung. Bei einem unbeschränkten LP benutzt der Verbraucher den
geprüften Einheitsstrahl. Ist der Startpunkt noch kein Gegenbeispiel, setzt
er `t = upperBound - c^T x + 1` und gibt `x + t v` aus. Die unabhängige
`AffineImplicationVerifier`-Prüfung rechnet anschließend die ursprünglichen
Ungleichungen und die strikte Verletzung direkt nach. Sie benötigt dafür
weder das Simplextableau noch den Entstehungsweg der Gegenbelegung.

Der Verbraucher benötigt keine vom Aufrufer vorgegebenen Multiplikatoren,
Primalpunkte oder Strahlen. Er behandelt geschlossene affine Ungleichungen;
ein Vertrag für strikte Prämissen oder ganzzahlige Variablen ist nicht Teil
dieser Schnittstelle.

### Beispiel über das SDK

```java
var source = new LinearProgramCertificates.Problem(
    List.of(List.of(ONE, ONE), List.of(NEGATIVE_ONE, ZERO), List.of(ZERO, NEGATIVE_ONE)),
    List.of(ExactRational.integer(4), ZERO, ZERO),
    List.of(ONE, ONE));
var request = new AffineImplication.Request(source, ExactRational.integer(4));
var result = LinearProgramSdk.imply(request, OptimizationBudget.DEFAULT, CancellationToken.NONE);
// PROVED: x >= 0, y >= 0 und x + y <= 4 implizieren x + y <= 4.

var stronger = new AffineImplication.Request(source,
    new ExactRational(BigInteger.valueOf(39), BigInteger.TEN));
var counterexample = LinearProgramSdk.imply(
    stronger, OptimizationBudget.DEFAULT, CancellationToken.NONE);
// REFUTED: Die ausgegebene zulässige Gegenbelegung erreicht x + y = 4 > 39/10.
```

`LinearProgramSdk.search` liefert die allgemeinen LP-Zertifikate.
`LinearProgramSdk.verifyImplication` prüft gespeicherte Implikationsbelege
unabhängig nach. Die SDK-Methoden delegieren ohne eigene Mathematik. Sie
übernehmen `maximumWork`, `maximumStates` als Zellgrenze und `timeoutMillis`;
`maximumCandidates` ist für diesen deterministischen Simplex ohne
Kandidatenkatalog nicht maßgeblich.

## Gemeinsame Ressourcenrechnung

Tableauaufbau, Pivots, Rückübersetzung und LP-Ausgabeprüfung verwenden
dasselbe `CertificateWork`-Objekt. Beim Implikationsaufruf gehören auch
Schlussgrenze, Gegenbeispielkonstruktion und unabhängige Verbraucherprüfung
zu demselben Arbeits- und Zeitbudget. Ein verschachtelter Aufruf beginnt
kein neues Budget. Vor der Ausgabe liegt ein letzter Abbruch- und Zeittest.

Vor der Tableauallokation werden die Quellgröße und die Dimensionen
geprüft, bevor sie verengt oder multipliziert werden. Für `m` Prämissen
und `n` freie Variablen gibt es `m+1` Tableauzeilen mit jeweils
`2n+2m+1` Einträgen. Die Reservierung zählt zusätzlich Zeilenreferenzen,
Basisindizes, Originalquelle, Ausgabearrays und unveränderliche Kopien.
Die Implikation reserviert ihre zusätzlich erhaltene Schlussgrenze und die
gegebenenfalls erzeugte Gegenbelegung. Die Grenze ist konservativ und
zählt logische Zellen, keine Java-Heap-Bytes. Die globale Obergrenze des
gemeinsamen Zählers beträgt eine Million Zellen.

Eingaben, Operanden und rationale Ergebnisse unterliegen der bestehenden
65.536-Bit-Grenze für Zähler und Nenner. Die Arbeitszählung berücksichtigt
deren Bitlängen. Einzelne `BigInteger`-Operationen werden nicht mitten im
Aufruf unterbrochen; Zeit und Abbruch sind Kontrollpunktgrenzen. Ein
Arbeits-, Zell-, Zeit-, Abbruch- oder Bitlimit ergibt ausschließlich
`UNAVAILABLE`. Auch eine unerwartete interne Tableauinvariante oder ein
ungültiger erzeugter Beleg wird niemals als mathematische Unzulässigkeit
ausgegeben.

## Unabhängige Kontrollen und Reproduktion

`LinearProgramSearchTest` enthält:

- einen gekoppelten Zweivariablenfall mit eindeutigem Optimum `(3,1)` und
  Wert 11 sowie einen rationalen Fall mit negativem freien Variablenwert;
- Farkas-Erzeugung für widersprüchliche Zeilen und eine unmögliche Nullzeile;
- einen Strahl mit negativem zulässigem Start, Streifen ohne Ecken,
  vollständig freie Ebenen, redundante Gleichungen und degenerierte Zeilen;
- einen degenerierten Vier-Variablen-Fall mit getrennt angegebenem
  Primalpunkt und Dualmultiplikator, beide mit Wert eins;
- alle 243 Einvariablenprobleme mit zwei Nebenbedingungen und
  Koeffizienten aus `{-1,0,1}`, unabhängig durch Intervallgrenzen klassifiziert;
- 48 gekoppelte Fälle mit zwei bis fünf Variablen: 16 invertierbar
  transformierte rationale Boxen mit unabhängig berechnetem Optimalwert,
  16 Widersprüche aus einer umgekehrten Intervallgrenze und 16
  unbeschränkte Fälle durch Entfernen einer relevanten oberen Grenze;
- verfälschte und quellenfremde erzeugte Belege, knappe Arbeitsgrenzen,
  Zellgrenzen, sofortigen und späten Abbruch, Zeitablauf, übergroße Eingaben
  und Bitwachstum aus zunächst zugelassenen Eingaben.

`AffineImplicationTest` prüft bewiesene und widerlegte Schlussgrenzen,
vakuose Folgerungen, endliche Gegenbelegungen aus Strahlen, geänderte
Prämissen und Schlussgrenzen, gefälschte Belege und die gemeinsame
Ressourcenrechnung. Die SDK-Kontrollen rufen denselben Such- und
Verbraucherpfad mit SDK-Budgets auf. Die bisherigen 13 Zertifikats- und
Trägerreduktionstests bleiben Bestandteil des gezielten Testlaufs.

Mit Java 25 und Maven 3.9.9 oder neuer:

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=LinearProgramSearchTest,AffineImplicationTest,LinearProgramSearchSdkTest,LinearProgramSdkTest,LinearProgramCertificatesTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.failOnWarning=true test
```

Die Suchverfahren sind implementierte, unabhängig nachgerechnete
Standardverfahren. Die endlichen Kontrollen belegen weder eine formale
Implementierungskorrespondenz noch eine allgemeine Laufzeit- oder
Skalierungsverbesserung.
