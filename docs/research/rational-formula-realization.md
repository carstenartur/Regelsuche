# Rationale nichtkommutative Formeln mit Definitionsbedingungen

Ein Formelbaum über den rationalen Zahlen enthält `Scalar`, `Variable`,
`Sum`, geordnete `Product`-Knoten und `Inverse`. Die Auswertung verlangt
gleich große quadratische rationale Matrizen für alle verwendeten Variablen.
Ein Skalar wirkt als Vielfaches der Einheitsmatrix. Ein ursprüngliches
Inversenvorkommen verlangt eine invertierbare Operandenmatrix, auch wenn der
Wert des Zweigs später mit null multipliziert oder weggekürzt wird.

## Realisierung und Prüfung

Für jeden Teilbaum $h$ erzeugt die Blockrekursion eine affine Realisierung
$h=u_hL_h^{-1}v_h$ der Ordnung $|h|+1$. Ein Blatt belegt einen $2\times2$-Block.
Die Summe ordnet die beiden Operandenblöcke diagonal an. Das Produkt ergänzt
den oberen rechten Block $-v_f u_g$, wodurch die Reihenfolge $fg$ erhalten
bleibt. Die Inverse ergänzt eine Zeile und eine Spalte um den Operandenblock.
Die zugehörigen Selektoren wählen den letzten Block mit negativem Vorzeichen.
Die Bibliothek übernimmt diese Rekursion aus der
[Abschnittsfassung von OpenAI Math, Familie 116](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Polynomial-Hitting-Lists-for-Noncommutative-Rational-Formulas-September-24-2026/build/sections/encoding.tex).

Der Wurzelpencil speichert alle Teilpencils als Hauptuntermatrizen. Jede
Inversenbedingung gibt den nullbasierten Knotenindex in Postorder sowie Anfang
und Ordnung ihrer Hauptuntermatrix an. Mehrfachreferenzen auf dasselbe
Formelobjekt erhalten pro Vorkommen einen eigenen Index. Der strukturelle
Prüfer liest jeden Eintrag des eingereichten Pencils, leitet die Selektoren
aus der Quelle ab und prüft die Liste der Inversenvorkommen vollständig.
`realize` verwendet diese Prüfung vor der Rückgabe eines Zertifikats.

`evaluate` prüft das Zertifikat und die Matrixbelegung. Die Inversenbedingungen
werden in Postorder auf Invertierbarkeit geprüft. Eine unabhängige direkte
Baumauswertung muss beim selben ersten Inversenvorkommen eine singuläre
Operandenmatrix finden, bevor `UNDEFINED` zurückgegeben wird. An einem
definierten Punkt löst der Pencil-Weg $L(X)S=v\otimes I$ exakt und vergleicht
$(u\otimes I)S$ mit dem Wert der direkten Baumauswertung. Der Status `DEFINED`
gilt auch für singuläre Ergebniswerte. Der SDK-Einstieg gibt ein Urteil für
die konkrete Belegung des ursprünglichen Formelbaums.

## SDK-Beispiel

```java
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.noncommutative.RationalFormulaSdk;
import de.regelsuche.scalar.ExactRational;

import java.util.List;
import java.util.Map;

class RationalFormulaExample {
    public static void main(String[] args) {
        var formula = new Sum(new Inverse(new Inverse(new Variable(7))), new Scalar(ExactRational.ONE));
        var limits = new IterationLimits(100_000, 10_000, 5_000);
        var token = CancellationToken.NONE;
        var certificate = RationalFormulaSdk.realize(formula, limits, token).certificate().orElseThrow();

        var zero = new Assignment(1, Map.of(7, new Matrix(1, List.of(ExactRational.ZERO))));
        var two = new Assignment(1, Map.of(7, new Matrix(1, List.of(ExactRational.integer(2)))));
        System.out.println(RationalFormulaSdk.evaluate(formula, certificate, zero, limits, token).status());
        System.out.println(RationalFormulaSdk.evaluate(formula, certificate, two, limits, token)
            .value().orElseThrow().entries().getFirst().canonicalText());
    }
}
```

Das Beispiel gibt `UNDEFINED` und `3` aus. Die innere Inversion bindet den
Definitionsbereich auch bei der doppelten Inversion.

## Grenzen und Reproduktion

`IterationLimits` steuert Arbeit, Tabellen- und Vektorplätze und Laufzeit.
Die Implementierung verwendet höchstens 64 Baumebenen und die gemeinsamen
Laufzeitgrenzen aus `CertificateWork`. Das Platzbudget zählt die im Aufruf
angelegten Tabellen und Vektoren kumulativ. Die skalaren Zähler und Nenner
sind auf jeweils 65.536 Bit begrenzt. Die dichte Auswertung eines Pencils der
Ordnung $q$ bei Eingabematrizen der Ordnung $d$ benötigt Tabellen der Ordnung
$q\cdot d$ und verwendet exakte Gauß-Jordan-Elimination.
`RESOURCE_LIMIT` und `CANCELLED` liefern weder einen partiellen Wert noch
eine Aussage über den Definitionsbereich. `INVALID_CERTIFICATE` und
`INVALID_ASSIGNMENT` bezeichnen fehlgeschlagene Eingabeprüfungen.

Die Belegung speichert Variablen-IDs in einer unveränderlichen sortierten
Map. Jeder Zugriff belastet die logarithmische Vergleichsgrenze des Baums;
ungünstige Hashbereiche verursachen keine linearen Kollisionsketten.

Die Tests prüfen geordnete Produkte an nichtkommutierenden Matrizen,
exakte Brüche, singuläre Operanden, doppelte Inversion und mit null
multiplizierte Zweige. Manipulationen treffen Pencil-Einträge, Selektoren,
Inversenvorkommen und den ursprünglichen Baum. Diese Kontrollen prüfen
konkrete Auswertungen. Der divisionsfreie Identitätsprüfer besitzt einen
[eigenen Einstieg und einen anderen mathematischen Vertrag](noncommutative-identity.md).

Mit Java 25 und Maven vom Repository-Stamm ausführen.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=RationalFormulaRealizationTest,RationalFormulaSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Vergleich am vorgegebenen gemeinsamen Skalarpunkt

`RationalFormulaSdk.compare(left, right, center, limits, token)` erwartet zwei
rationale Formelquellen und eine `Assignment` der Ordnung eins. Alle benutzten
Variablen brauchen einen rationalen Wert. Die beiden ursprünglichen Bäume
müssen dort definiert sein. Der Algorithmus überprüft ihre Inversenvorkommen
mit der konkreten Auswertung. Bei einem singulären Zentrum, einer fehlenden
Variablen oder einem Ressourcenabbruch liefert er `INCONCLUSIVE` und leere
Evidenz. Beispielsweise ist $(XY-YX)^{-1}$ an jedem skalaren Zentrum
undefiniert. Dieser Einstieg mit festem Zentrum sucht kein anderes Zentrum;
die unten beschriebene Policy-Überladung übernimmt die begrenzte Suche.

Die Blockdiagonale der beiden überprüften Pencils realisiert die Differenz
mit dem Ausgangsselektor $u=(u_l,-u_r)$ und der gestapelten Eingangsspalte
$v$. Seien $C=L(c)$, $L_i$ die linearen Koeffizienten und $A_i=-C^{-1}L_i$.
Die Suche startet mit $C^{-1}v$ und erweitert die Basis durch linear
unabhängige Spalten $A_i b$. Sie besucht höchstens so viele unabhängige
Spalten wie die Ordnung $q$ des Differenzpencils. Jede neue Spalte speichert
das Wort ihrer Erzeugung.

* **Gleichheit.** Das Zertifikat enthält die beiden Quellrealisierungen,
  das Zentrum und Spalten $B$. Der Prüfer bindet beide Realisierungen an die
  erwarteten Bäume und prüft in den ursprünglichen Koordinaten exakt
  $v\in\operatorname{im}(CB)$, $\operatorname{im}(L_iB)\subseteq
  \operatorname{im}(CB)$ für jedes $i$ und $uB=0$. Er braucht die von der
  Suche berechnete Inverse von $C$ dazu nicht. Für beliebige Matrizen der
  Ordnung $d$ erhält der normierte Pencil $(C^{-1}\otimes I_d)L(X)$ den
  Raum $\operatorname{im}(B)\otimes\mathbb Q^d$. Sind beide ursprünglichen
  Bäume definiert, ist dieser Pencil invertierbar. Seine Einschränkung auf
  den endlichdimensionalen invarianten Raum ist ebenfalls invertierbar.
  Daher liegt die Lösung zum Eingang $C^{-1}v\otimes I_d$ in diesem Raum,
  wo der Ausgang verschwindet. Das Urteil `EQUAL_ON_SHARED_DOMAIN` gilt
  in jeder Matrixordnung auf dem gemeinsamen Definitionsbereich. Zum
  Beispiel stimmen $(X^{-1})^{-1}$ und $X$ bei invertierbarem $X$ überein.
* **Verschiedenheit.** Zu einem Wort $i_1\dots i_k$ mit
  $u A_{i_1}\cdots A_{i_k}C^{-1}v\ne0$ bildet der Algorithmus Matrizen
  der Ordnung $k+1$. Variable $j$ hat Diagonale $c_j$ und genau an den
  Positionen $(r,r+1)$ mit $i_r=j$ eine Eins. In der endlichen
  Dreiecksentwicklung der Resolvente isoliert der rechte obere Eintrag
  den Wortkoeffizienten. An jedem Inversengatter bleiben die diagonalen
  Operandenwerte des definierten Zentrums erhalten. Zur Ausgabe `DISTINCT`
  wertet der Prüfer zusätzlich beide ursprünglichen Bäume an den gelieferten
  Matrizen aus und vergleicht die konkreten Ergebnisse. Ein undefinierter
  oder gleicher Matrixwert verwirft den Zeugen.

`RationalFormulaSdk.verifyComparison(left, right, center, evidence, limits,
token)` prüft die eingereichte Evidenz erneut. Er benötigt weder die
gefundene Basisreihenfolge noch einen früheren Ergebnisstatus. Die folgende
Messung verwendet `RationalFormulaComparisonTest`, Baumsubtraktion
$a+((-1)b)$, das Nullzentrum sowie Arbeit/Platz/Zeit
20.000.000/500.000/15.000 ms. Die Arbeit beinhaltet beide
Quellrealisierungen, die konkrete Zentrumsprüfung, die Suche und die
abschließende Evidenzprüfung. Die letzte Spalte zählt einen separaten
Prüfaufruf. Diese Zähler sind keine Laufzeitmessungen.

| Vergleich | $q$ | Evidenz | Arbeit gesamt | Erneute Prüfung |
| --- | ---: | --- | ---: | ---: |
| $(I-XY)^{-1}X=X(I-YX)^{-1}$ | 22 | 4 Spalten | 33.184 | 11.685 |
| $XY$ gegen $YX$ | 8 | Zeuge der Ordnung 3 | 6.227 | 3.971 |

Die universelle Trefferliste aus Familie 116 hat einen anderen
Eingabevertrag. Für $n=s=1$ geben die Manuskriptparameter $w=2$, $B=3$,
$\Delta=48$, $M=256$, Matrixordnung $D=M^2/2=32.768$ und
$H=1+3(s+2)BM^3=452.984.833$. Der Generator betrachtet $H^3$ Tripel und
filtert singuläre Ausgangsmatrizen. Bereits eine dichte Matrix dieser
Ordnung benötigt mehr als eine Milliarde Plätze. Die Implementierung
benutzt das gegebene Zentrum und die tatsächlichen Quelldaten. Sie
implementiert keine universelle Trefferliste.
[Parameter](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Polynomial-Hitting-Lists-for-Noncommutative-Rational-Formulas-September-24-2026/build/sections/expansion.tex) · [Generator](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Polynomial-Hitting-Lists-for-Noncommutative-Rational-Formulas-September-24-2026/build/sections/generator.tex)

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=RationalFormulaComparisonTest,RationalFormulaComparisonSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Ein gemeinsames Skalarzentrum automatisch suchen

`RationalFormulaSdk.compare(left, right, policy, limits, token)` sammelt die
Variablen beider ursprünglicher Formeln und besucht das kartesische Gitter der
in der Policy vorgegebenen rationalen Werte. Die Standardpolicy versucht
`[0, 1, -1, 2, -2]` in dieser Reihenfolge und höchstens 256 Zentren. Variablen
stehen nach ihrer ID aufsteigend; ein gemischter Zähler vermeidet den Aufbau
der möglicherweise riesigen Gittergröße. Andere Werte und Grenzen sind
explizit konfigurierbar.

Die Suche realisiert beide Quellen einmal. Sie prüft an jedem Zentrum die
Inversenvorkommen beider ursprünglicher Bäume. Nur ein undefiniertes Zentrum
wird übersprungen. Alle Versuche, gespeicherten Zwischenergebnisse, die
anschließende Raumsuche und die unabhängige Evidenzprüfung teilen dieselbe
Arbeits-, Speicher-, Zeit- und Abbruchgrenze. Bei Erfolg enthält das Ergebnis
das tatsächlich verwendete Zentrum und die gewöhnliche Vergleichsevidenz.
`verifyComparison` kann sie unabhängig von der Zentrumsuche erneut prüfen.

Für $(X(X-1))^{-1}$ sind null und eins unzulässig; die Standardpolicy findet
minus eins im dritten Versuch. Für $(X^{-1})^{-1}=X$ findet sie eins und
behält beide ursprünglichen Inversionsbedingungen auf der linken Seite.
Das Ergebnis behauptet daher keine Gleichheit der Definitionsbereiche.
`XY` und `YX` stimmen an allen Skalarpunkten überein, erhalten aber weiterhin
einen geprüften Matrixgegenzeugen. Die Inverse des Kommutators
$(XY-YX)^{-1}$ ist an jedem Skalarzentrum undefiniert: Ein ausgeschöpftes
Gitter liefert `INCONCLUSIVE / CENTER_GRID_EXHAUSTED`, keine Gleichheit.

Die Tests prüfen zusätzlich eine exakt um eins zu kleine Gesamtarbeitsgrenze,
kumulativen Platzverbrauch, Schlussabbruch und unverändertes Replay. Diese
endliche Suchpolicy ist keine universelle Trefferliste und kein
Vollständigkeitsbeweis für rationale nichtkommutative Formeln.

Für die vier oben beschriebenen Eingaben aus `RationalFormulaCenterSearchTest`
mit `X=Variable(17)`, `Y=Variable(-2)` und Arbeit/Platz/Zeit
5.000.000/500.000/10.000 ms ergibt die Ledger-Beobachtung:

| Vergleich | Policy-Werte | Besuchte Zentren | Gesamte Such- und Prüfarbeit | Separate frische Prüfung |
| --- | --- | ---: | ---: | ---: |
| $(X(X-1))^{-1}$ mit sich selbst | `[0,1,-1]` | 3 | 20.480 | 6.828 |
| $(X^{-1})^{-1}$ gegen $X$ | Standard | 2 | 3.137 | 1.337 |
| $XY$ gegen $YX$ | Standard | 1 | 6.282 | 3.971 |
| $(XY-YX)^{-1}$ mit sich selbst | `[0,1]` | 4 | 8.057 | keine Evidenz |

Die beschränkten Policies erlauben jeweils höchstens zehn Zentren; die
Standardpolicy erlaubt 256. Die frische Prüfung ist ein separater Aufruf
von `verifyComparison` mit dem ausgegebenen Zentrum und Beleg. Der Zähler
der Suche enthält bereits ihre eigene Abschlussprüfung und sämtliche
erfolglosen Zentren. Die Tabelle misst keine Laufzeit.

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=RationalFormulaCenterSearchTest,RationalFormulaComparisonTest,RationalFormulaSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
