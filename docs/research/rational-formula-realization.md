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
