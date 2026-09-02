# Kanonische Arbeitsprojektion der Polynomtheorie-Nutzenstudie

Status: vor der ersten mathematischen Profilausführung eingefroren

Bezug: Issue #748

## Zweck

Die exakten Polynom-Pipelines führen einen feingranularen
`PolynomialWorkLedger`. Darin werden unter anderem AST-Besuche, arithmetische
Operationen, Quelltextvergleiche, Verifier-Arbeit, ausgegebene Codeeinheiten
und strukturelle Hasharbeit getrennt gezählt.

Die präregistrierte Nutzenstudie verwendet dagegen den stabilen
`PolynomialTheoryUtilityWorkBreakdown` und kleine, an sechs Checkpoints
skalierte Arbeitsbudgets. Ein Adapter darf diese beiden Ebenen weder
nachträglich passend rechnen noch nur die erfolgreichen Teile behalten.

`PolynomialTheoryUtilityCanonicalWorkProjection` legt deshalb vor der ersten
mathematischen Ausführung eine einzige Projektion fest.

## Vollständige Partition

Ein `RawWork` enthält:

- die unveränderte gesamte mechanische Pipeline-Ledger;
- die primitive Transformationsarbeit;
- genau ein Rohsegment für jede Dimension des Studienvektors.

Die Summe aller Rohsegmente muss der gesamten mechanischen Ledger exakt
entsprechen. Jeder rohe Stage-Name darf in genau einem Segment vorkommen.
Fehlende, auf mehrere Dimensionen aufgeteilte, doppelt gezählte oder
zusätzlich erfundene Arbeit wird abgewiesen. Die Cache-Segmente sind Teil
desselben Vertrags, bleiben für `ON_DEMAND_VERIFIED_FACTORIZATION` aber leer.

## Dimensionsgrenzen

Bekannte Pipeline-Stufen dürfen nur in die passende Dimension eingehen:

- `projection.*` und Positions-/Stalenessprüfungen zählen als Matching;
- sämtliche `exact-parsed-view.*`-Stufen und die Quell-Evidence zählen als
  Source Validation;
- `verify.*` zählt ausschließlich als Verification;
- `render.*` zählt ausschließlich als Rendering;
- der exakte Reparse besitzt eine eigene Dimension;
- ausschließlich der abschließende strukturelle Änderungsvergleich zählt als
  Reconstruction;
- struktureller Occurrence-Ersatz und Replay zählen als
  Occurrence Replacement;
- Cache- und Study-Evidence-Stufen besitzen getrennte Präfixe.

Die Zuordnung ist absichtlich disjunkt. Auch eine nach dem Reparse erneut
benutzte exakte Polynomansicht bleibt Source Validation; sie darf nicht
wahlweise in Reconstruction verschoben werden. Damit kann ein Adapter die
Arbeitsverteilung nicht nach Kenntnis eines Ergebnisses verändern.

Ein unbekannter Pipeline-Name darf nicht in eine günstigere bekannte
Dimension einsortiert werden. Er wird ausschließlich als native
Faktorisierungsarbeit akzeptiert und dort konservativ eins zu eins gezählt.
Neue optimierte oder anders benannte Stufen können dadurch nicht unbemerkt
Arbeit verschwinden lassen.

## Vorab festgelegte Quanten

Fast alle Rohwerte werden eins zu eins in kanonische Einheiten überführt. Nur
vier ausdrücklich bekannte Implementierungsmultiplikatoren werden
zurückgerechnet:

| Rohstufe | Rohwerte je kanonischer Einheit |
|---|---:|
| Literal-Evidence-Vergleich | 512 |
| Quelltext-Evidence-Vergleich | 4 |
| strukturelle Hasharbeit je Knoten | 128 |
| UTF-8-Evidence-Payload | 64 Byte |

Jede Division rundet nach oben. Ein nichtleerer Rohposten bleibt daher immer
mindestens eine kanonische Arbeitseinheit. Die Werte stammen aus den bereits
vorhandenen expliziten Sicherheitsmultiplikatoren und sind Bestandteil der
versionierten Projektionsrevision; sie werden nicht aus Qualification- oder
Ergebnisdaten geschätzt.

## Eingabebudget

Die Projektion ist an genau einen eingefrorenen
`PolynomialTheoryUtilityExecutionInput` gebunden. Sie verwirft Ergebnisse,
wenn

- primitive Arbeit das zugelassene primitive Budget überschreitet;
- mechanische Arbeit das Gesamtbudget überschreitet;
- Faktorisierungsarbeit das separate Faktorisierungsbudget überschreitet.

Der Projektions-Hash bindet Eingabe-ID, vollständige Rohpartition,
Projektionsrevision und den resultierenden Arbeitsvektor.

## Claim-Grenze

Dieser Vertrag legt ausschließlich fest, wie bereits tatsächlich verbrauchte
Pipeline-Arbeit in die Studie eingeht. Er beweist weder mathematischen Erfolg
noch zusätzliche Reichweite, geringere Arbeit, Cache-Nutzen oder einen
Produktstandard. Die Qualifikationsressource wird nicht gelesen.
