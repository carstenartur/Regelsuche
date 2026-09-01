# Gemessene Ausführung der Polynomtheorie-Nutzenstudie

Status: Runner-Integration nach Candidate-Freeze-Vertrag, vor mathematischen
Profiladaptern

Bezug: Issue #748

## Zweck

Der bestehende `PolynomialTheoryUtilityProfileAdapter.TargetBlindRunner` prüft
bereits die eingefrorene Run-Reihenfolge, exakte Eingabeumschläge, Fallpositionen
und den Lebenszyklus jedes Adapters. Er sammelt jedoch ausschließlich
`PolynomialTheoryUtilityCandidateResult`. Ein mathematischer Adapter könnte
deshalb zwar während seiner Ausführung vollständige Pfad-,
Faktorisierungs- und Cache-Evidenz erzeugen, diese aber vor dem Messbatch
verlieren.

`PolynomialTheoryUtilityMeasuredExecution` ersetzt den Runner nicht. Die Klasse
dekoriert die unveränderte Adapterliste, übergibt sie an denselben
`TargetBlindRunner` und bindet jedes dort erzeugte Resultat an den während genau
dieses Aufrufs erzeugten Messbegleiter.

## Atomarer Kandidat

`PolynomialTheoryUtilityMeasuredCandidate` enthält genau:

- ein typisiertes terminales `PolynomialTheoryUtilityCandidateResult`;
- das dazu passende `PolynomialTheoryUtilityCandidateMeasurements`.

Der Konstruktor prüft die Resultatbindung erneut. Die Hilfsmethode
`withoutObservations` ist nur für echte Nullbeobachtungen zulässig. Sobald ein
Resultat eine Transition, Faktorisierungsarbeit oder Cachearbeit enthält,
verwerfen die bestehenden Messvalidatoren eine leere Evidenzliste.

Damit können einfache Kontrolladapter weiterhin nur ein Nullresultat liefern.
Ein mathematischer Adapter muss dagegen seinen Pfad, seine Requests,
Kandidaten, Verifier-Ausgänge und gegebenenfalls Cacheereignisse explizit
bereitstellen.

## Bestehender Runner bleibt Autorität

Die gemessene Ausführung:

1. übernimmt die fünf eingefrorenen Adapter;
2. dekoriert deren `openRun`-Ergebnisse;
3. ruft ausschließlich den bestehenden `TargetBlindRunner` auf;
4. erfasst je zurückgegebenem Resultat genau einen Messbegleiter;
5. ordnet die Messungen anschließend anhand der unveränderten Resultatreihenfolge;
6. erzeugt das bereits festgelegte 600-Zeilen-
   `PolynomialTheoryUtilityCandidateMeasurementBatch`.

Run-Gruppierung, Fallreihenfolge, Inputprüfung, `try`-with-resources und
vollständiges Schließen eines Runs werden daher nicht dupliziert.

## MeasuredRun

Adapter mit nichtleerer Evidenz geben ein
`PolynomialTheoryUtilityMeasuredExecution.MeasuredRun` zurück. Dessen
`executeMeasured` liefert Resultat und Messung in einem Aufruf. Das geerbte
`execute` projiziert nur das bereits gebundene Resultat und kann keine zweite
Ausführung anstoßen.

Gewöhnliche ältere `Run`-Implementierungen bleiben zulässig, aber nur solange
ihre Ergebnisse tatsächlich null Beobachtungen besitzen. Eine ältere
Implementierung, die eine Transition zurückgibt, ohne `MeasuredRun` zu
verwenden, scheitert unmittelbar bei der leeren Messprojektion.

## Fail-closed-Grenzen

Abgewiesen werden insbesondere:

- ein Messbegleiter für ein anderes Resultat;
- ein nichtleeres Resultat ohne explizite Mess-Evidenz;
- eine doppelte Resultatidentität während der Erfassung;
- ein Runner-Resultat ohne während derselben Ausführung erfasste Messung;
- zusätzliche Messungen außerhalb des resultierenden 600-Zeilen-Batches;
- jede bereits vom Resultat-, Trace-, Attempt-, Cache- oder Batchvertrag
  verbotene Inkonsistenz.

Die Klasse liest weder Formation-Sollwerte noch die versiegelte Qualifikation.

## Charakterisierung

Der fokussierte Test führt die vollständige eingefrorene Matrix über den
bestehenden Runner aus:

```text
5 Profile × 6 Checkpoints × 20 Fälle = 600 Resultate und 600 Messungen
```

Er prüft drei getrennte Fälle:

- ausschließlich echte Nullbeobachtungen werden vollständig gebunden;
- ein `MeasuredRun` liefert in jedem Checkpoint eine verifizierte
  Beispieltransition mit Trace und Faktorisierungsversuch, die im finalen Batch
  erhalten bleibt;
- dieselbe Transition aus einem gewöhnlichen `Run` ohne Messbegleiter wird
  abgewiesen, statt stillschweigend als bloßes Resultat fortzubestehen.

Die Beispieltransition dient nur der Vertragscharakterisierung und ist kein
Ergebnis der späteren Nutzenstudie.

## Nächster Schritt

Nach Merge der Mess-, Batch-, Freeze- und Runner-Verträge kann
`ON_DEMAND_VERIFIED_FACTORIZATION` als erster mathematischer Adapter ein
`MeasuredRun` implementieren. Er muss die bereits vorhandenen Pfade verwenden:

```text
ExpressionParser.parseExactTerm
  -> ExactParsedFactorizationPipeline
  -> NativeUnivariateFactorizationEngine
  -> FactorizationVerifier
  -> ExactFactorizationTransformationPipeline
  -> TreePosition-gebundener lokaler Ersatz
```

Dabei sind alle terminalen negativen, nicht unterstützten und
budgetbedingt unentschiedenen Ausgänge ebenso als Faktorisierungsversuche zu
erhalten wie erfolgreiche Transformationen.

## Claim-Grenze

Dieser Slice belegt nur, dass die bestehende target-blinde Ausführung ihre
vollständige Mess-Evidenz bis zum Candidate-Freeze tragen kann. Er belegt weder
Faktorisierungserfolg noch zusätzliche Reichweite, geringere Arbeit,
Cache-Amortisation, historische Wiederentdeckung oder eine Produktentscheidung.
