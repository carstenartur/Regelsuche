# Exakte Zertifikate für rationale lineare Programme

Der Aufrufer übergibt ein rationales Maximierungsproblem `Ax ≤ b` mit
Zielfunktion `cᵀx`. `LinearProgramCertificates.Problem` bewahrt die Originalreihenfolge
der Zeilen und Variablen. Es verlangt eine rechteckige Matrix mit mindestens
einer Zeile und Spalte sowie passende Vektoren. Variablen sind vorzeichenfrei.
Alle Eingabewerte sind `ExactRational`. Jedes Zertifikat bindet das vollständige
Tripel `(A,b,c)` einschließlich der Zielfunktion bei einem Unzulässigkeitsbeleg.

| Zertifikat | Unabhängig geprüfte Bedingungen | Folgerung |
| --- | --- | --- |
| `Infeasible(source, y)` | `y ≥ 0`, `Aᵀy = 0`, `bᵀy < 0` | Jede zulässige Belegung würde `0 ≤ bᵀy < 0` implizieren. |
| `Optimal(source, x, y)` | `Ax ≤ b`, `y ≥ 0`, `Aᵀy = c`, `cᵀx = bᵀy` | Für jedes zulässige `u` gilt `cᵀu = yᵀAu ≤ yᵀb = cᵀx`. |
| `Unbounded(source, x, v)` | `Ax ≤ b`, `Av ≤ 0`, `cᵀv = 1` | Für jedes `t ≥ 0` ist `x + tv` zulässig und erreicht den Zielwert `cᵀx + t`. |

`LinearProgramVerifier.verify` berechnet diese Bedingungen direkt aus den
Originalkoeffizienten. `FarkasSparsifier.sparsify` setzt einen gültigen
Unzulässigkeitsbeleg voraus. `LinearProgramSdk` bietet dieselben Operationen über
`OptimizationBudget` und `CancellationToken` an. Der SDK-Einstieg verwendet
`maximumWork`, `maximumStates` als Zellgrenze und `timeoutMillis`.

## Trägerreduktion

Zuerst skaliert die Reduktion den gegebenen Zeugen auf `bᵀy = −1`.
Die aktive Mischung der erweiterten Zeilen `(Aᵢ,bᵢ)` ergibt damit `(0,−1)`.
Für einen abhängigen Teil des aktiven Trägers berechnet der Algorithmus mit
rationaler Elimination eine exakte Richtung `d`, deren Mischung null ergibt.
Er wählt den Koeffizienten des ersten freien Generators als eins. Damit ist
mindestens ein Koeffizient von `d` positiv. Der Schritt
`t = min{yᵢ/dᵢ | dᵢ > 0}` ersetzt die aktiven Gewichte durch `yᵢ − tdᵢ`. Er
erhält die Mischung und Nichtnegativität. Mindestens ein bisher positives
Gewicht wird null. Ursprüngliche Nullen bleiben null.

Ein Präfix von höchstens `n+2` aktiven Zeilen genügt für jeden
Abhängigkeitstest bei `n` Variablen. Ein größeres aktives Set erzwingt bereits
in einem Präfix dieser Länge eine Abhängigkeit im Raum der erweiterten
Zeilen. Beim Ende sind alle noch aktiven erweiterten Zeilen unabhängig.
Der Raum, den sie aufspannen, hat höchstens die Dimension
`rank(A) + 1`. Diese Schranke erfordert daher keine getrennte
Rangberechnung. Der unabhängige Prüfer berechnet den ausgegebenen Zeugen
erneut auf der Originalquelle und verlangt dabei genau `bᵀy = −1`.

Das Verfahren liefert eine Teilmenge des vorhandenen Trägers mit der
Rangschranke. Ein kleinstmöglicher Unzulässigkeitskern ist eine eigene
Optimierungsaufgabe. Der Träger kann von der Reihenfolge der Eingabe
abhängen und der ausgegebene Multiplikator bewahrt alle Quellpositionen.

## Budget und Wiederholung

`VERIFIED` bedeutet, dass alle Gleichungen und Ungleichungen bis zum
abschließenden Abbruch- und Zeittest nachgerechnet wurden. `INVALID`
bezeichnet eine Form-, Quellen- oder Rechenabweichung. `UNAVAILABLE` steht
für einen unvollständigen Lauf, etwa bei Arbeitsgrenze, Zellgrenze,
Zeitablauf, Abbruch oder zu großen Zwischenwerten. Eine fehlgeschlagene
abschließende Prüfung kann ebenfalls kein Reduktionsergebnis ergeben.

Die Arbeitszählung umfasst Quellenvergleich, Eingabeprüfung, Normalisierung,
Elimination, Ausgabeaufbau und abschließende unabhängige Prüfung unter einer
gemeinsamen Grenze. Die Zellgrenze reserviert logische Eingabe-, Ausgabe-
und Arbeitszellen vor den größeren internen Allokationen. Sie misst keine
Java-Heap-Bytes. Das vorherige Erzeugen der unveränderlichen Eingabeobjekte
durch den Aufrufer gehört zum Aufruferaufwand. `CertificateWork` gewichtet
geprüfte Skalare nach Bitlänge. Rationale Zwischenresultate können wachsen,
und die Elimination kann für mehrere kleinere Träger neu anfallen.
Arbeitseinheiten sind daher kein Wandzeitmesswert.

Die Kern- und SDK-Tests decken unter anderem diese Fälle ab.

- `x ≤ 0, −x ≤ −1` mit Gewichten `(7,7)` ergibt `(1,1)`.
- Ein rational skaliertes Rang-eins-System mit vier aktiven Zeilen
  ergibt den Multiplikator `(1,1,0,0,0)` mit zwei aktiven Zeilen.
  Ein inaktiver Widerspruch mit Nullgewichten
  bleibt dabei unbenutzt. Ein Rang-null-System endet mit einer aktiven Zeile.
- Das Ziel `2x₁ + 3x₂` erreicht im Test exakt den optimalen Wert 6
  bei `x = (3/2,1)` und `y = (1,2,0)`. Die Ray-Tests decken Einheitsgewinn,
  zulässigen Start, homogene Nebenbedingungen und negative Variablenwerte ab.
- 60 mit festem Seed erzeugte rationale Widersprüche werden über separate
  exakte Skalarprodukte und eine Rangberechnung in der ursprünglichen
  Zeilenorientierung geprüft.
- Gegenproben verändern Vorzeichen, Objektivwert, Dimensionen oder einzelne
  Felder der Quelle. Die Tests decken auch Brüche mit über 200 Bit,
  Arbeit und Speicher knapp unter dem erforderlichen Umfang sowie einen
  Abbruch am letzten Kontrollpunkt ab.

Die beiden ersten Beispiele benötigen für die Prüfung des Eingabezeugen
44 beziehungsweise 199 Arbeitseinheiten. Der gesamte Reduktionsaufruf
mit Eingabe- und Ausgabeprüfung benötigt 143 beziehungsweise 811 Einheiten.
Die Beispiele messen die Kosten der zusätzlichen Reduktion. Beide Methoden
haben unterschiedliche Ausgaben.

Aus dem Repository mit Java 25 und Maven 3.9.9 oder neuer ausführbar.

```sh
mvn -B -ntp -pl regelsuche-optimization-sdk -am \
  -Dtest=LinearProgramCertificatesTest,LinearProgramSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## Herkunft und Geltungsbereich

Die Methode übernimmt die klassische Farkas-Normalisierung und
Abhängigkeitsreduktion aus dem Umfeld von `openai/math` am Commit
`3344da59991ed94f9ba711217b29c1d760badfa6`. Der Lean-Satz
`sparse_certificate_supported` sichert Normierung, Rangschranke und
Erhaltung des Trägers für ein allgemeines geordnetes Feld.
[Lean-Beweis](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/lean/OAI/Optimization/LinearProgramming/Farkas/Sparse.lean#L137-L171)

Diese Java-Komponente prüft gegebene Zertifikate und reduziert einen gegebenen
Farkas-Zeugen. Der zusätzliche [begrenzte LP-Erzeuger](linear-program-search.md)
findet solche Ausgänge mit einem klassischen exakten Zweiphasen-Simplex und
verwendet sie für affine Implikationen. Die hier beschriebene Prüfung bleibt
unabhängig vom Erzeuger. Die Lean-Quelle beschreibt darüber hinaus eigenständige
Algorithmen für streng polynomielle Zulässigkeitsprüfung und Optimierung.
Die Java-Implementierung ist durch Tests geprüft. Eine formale
Implementierungskorrespondenz zu Lean und eine allgemeine Laufzeitaussage
sind hier nicht belegt.
[Umfang der vorgelagerten Formalisierung](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/lean/docs/380.md)
