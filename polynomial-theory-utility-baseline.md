# Target-blinde Ausführungsgrenze der Polynomtheorie-Nutzenstudie

## Status und Grenze

Produktiv implementiert ist weiterhin nur `NO_FACTORIZATION`. Die vier
mathematischen Profile bleiben unverbunden; Qualifikation, Candidate-Freeze und
Produktentscheidung existieren noch nicht.

Der 30-Run-Runner und das noch nicht eingefrorene Ergebnisbatch verwenden jetzt
durchgehend den eigenständigen
`PolynomialTheoryUtilityCandidateResult`-Vertrag in Version 2. Der frühere
verschachtelte v1-Ergebnisdatensatz wurde entfernt. Die Testadapter der vier
unverbundenen Profile liefern ausschließlich klar als Testdaten markierte
`UNSUPPORTED`-Resultate. Daraus folgt keine mathematische oder produktbezogene
Evidenz.

## Eingabe- und Ergebnisvertrag

`PolynomialTheoryUtilityProfileAdapter` erhält ausschließlich einen gebundenen
Run-Deskriptor, eine target-blinde Eingabe und deren sichtbaren Formationsfall.
Jeder Run liefert ein `PolynomialTheoryUtilityCandidateResult`. Dieses bindet:

- den vollständigen wertgleichen Eingang aus der eingefrorenen 600-Zeilen-Matrix,
- die unveränderte Quellwurzel aus dem Formation-Korpus,
- terminalen Status und Detailcode,
- einen typisierten Arbeitsvektor,
- die geordnete Liste occurrence-gebundener Übergänge,
- das Ergebnis der unabhängigen Verifikation.

Der Nulladapter verlässt sich nicht nur auf sichtbare IDs. Beim Öffnen eines
Runs löst er dessen vollständige 20 Eingaben aus dem content-adressierten
600-Zeilen-Freeze auf. An jeder Position muss der übergebene Record exakt mit
der eingefrorenen Eingabe übereinstimmen. Ein syntaktisch gültiger Umschlag, der
beispielsweise eine echte `inputId` wiederverwendet, aber `rowId` oder Budgets
verändert, wird vor der Ausführung abgewiesen und verbraucht keine Run-Position.

Der Ergebnisvertrag prüft primitive, mechanische und
Faktorisierungsarbeitsgrenzen sowie die komponentenweise Deckung sämtlicher
lokaler Übergangsarbeit. `VALIDATED_TRANSITION` erfordert mindestens einen
typisierten Übergang und `VERIFIED`. Alle anderen Status dürfen keine
Übergangsautorität behalten. Weitere Status sind `NO_TRANSITION`,
`UNSUPPORTED`, `BUDGET_INCONCLUSIVE` und `TECHNICAL_FAILURE`.

## Nullprofil

Der Adapter

```text
regelsuche.polynomial-theory-utility.no-factorization/v1
```

akzeptiert nur die sechs berechneten Baseline-Run-Identitäten und je 20 Fälle in
Formationsreihenfolge. Alle 120 Ergebnisse lauten:

```text
NO_TRANSITION / FACTORIZATION_DISABLED_BY_FROZEN_PROFILE
all typed work dimensions = 0
transitions = []; verifier = NOT_REQUESTED
```

Jedes Resultat enthält zusätzlich die exakte Quellwurzel seines
Formation-Falls. Ungenutztes Budget wird weder berechnet noch umverteilt.

## Exaktes Adapterinventar

`PolynomialTheoryUtilityProfileAdapter.AdapterRegistry` akzeptiert genau einen
Adapter für jedes der fünf vorab registrierten Profil-/Adapter-Paare. Fehlende,
zusätzliche, doppelte oder unter einem anderen Profil eingesetzte Adapter werden
vor der ersten Ausführung abgelehnt.

Unabhängig von der Reihenfolge des Aufrufers speichert die Registry die Adapter
in der eingefrorenen Profilreihenfolge. Damit bleiben spätere Iteration,
Berichterstattung und Artefaktbildung deterministisch.

## Run-major Ausführung

`PolynomialTheoryUtilityProfileAdapter.TargetBlindRunner` konsumiert nur das
content-adressierte Ausführungseingabeartefakt:

```text
5 Profile × 6 Checkpoints × 20 Fälle = 600 Eingaben
```

Der Eingabeartefakt-Vertrag bindet die vollständigen kanonischen Bytes und weist
jede veränderte Zeile oder Reihenfolge zurück. Der Runner öffnet für jeden der
30 zusammenhängenden Runs genau eine Adapter-Session. Die 20 Eingaben müssen
dieselbe Run-, Profil-, Checkpoint- und Adapteridentität besitzen und der
eingefrorenen Formationsreihenfolge folgen.

Jedes Ergebnis wird unmittelbar gegen den exakten Input-Record und den
positionsgleichen Formation-Fall geprüft. Damit können weder eine fremde
Eingabe noch eine andere Quellwurzel unbemerkt in das Batch gelangen.

Eine Adapterausnahme erzeugt kein partielles Batch und wird nicht automatisch
als mathematisches Resultat umgedeutet. `try`-with-resources schließt die aktive
Session; ein zusätzlicher Schließfehler bleibt als unterdrückte Ausnahme am
ursprünglichen Fehler erhalten. Ein technischer Studienausgang muss vom Adapter
ausdrücklich als `TECHNICAL_FAILURE` geliefert werden.

## Noch nicht eingefrorenes Ergebnisbatch

`PolynomialTheoryUtilityProfileAdapter.CandidateBatch` bewahrt exakt 600
geordnete, eindeutige und an Eingabe plus Formation gebundene v2-Resultate. Sein
Schema lautet:

```text
regelsuche.polynomial-theory-utility-candidate-batch/v2
```

Der Status bleibt:

```text
TARGET_BLIND_RESULTS_COLLECTED_NOT_FROZEN
```

Das Batch bindet Inhaltsadresse und Bytelänge der Ausführungseingaben, besitzt
aber bewusst noch keine eigene kanonische JSON-Darstellung oder öffentliche
SHA-256-Identität. Es ist die einheitliche Pre-Freeze-Ausführungsgrenze, aber
noch nicht die vollständige vorregistrierte Messoberfläche. Der entfernte
aggregierte v1-Datensatz ist keine zulässige Zwischenautorität mehr.

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityNoFactorizationAdapterTest
```

Die Tests decken ab:

- alle sechs Nullprofil-Runs und 120 eindeutige Zero-Work-v2-Resultate;
- Eingabe-, Formation- und Quellwurzelbindung jedes Resultats;
- Reihenfolge, erfundene Run-Hashes, Session-Lebenszyklus, Budgetfehler und
  Rebinding;
- einen gefälschten Eingabeumschlag mit wiederverwendeter `inputId`;
- 30 exakt gebundene, geöffnete und geschlossene Sessions;
- 600 Eingaben und Resultate in unveränderter Reihenfolge;
- fehlende, doppelte und falsch profilierte Adapter sowie deterministische
  Registry-Reihenfolge;
- Null- und an fremde Eingaben gebundene Resultate;
- Session-Cleanup und unterdrückte Schließfehler;
- Unveränderlichkeit des Ergebnisbatches.

## Nächster Evidenzschritt

Vor einer Candidate-Freeze folgt ein eigener typisierter Mess-Slice. Er muss die
noch fehlenden vorregistrierten Dimensionen binden und gegen Resultat,
Übergänge und Profil revalidieren, insbesondere:

- Faktorisierungsanfragen und erzeugte Kandidaten,
- Pfadtiefe, primitive Expansionslänge und geordnete Primitive-Lineage,
- Quell- und Ergebnis-AST-Größen,
- Cache-Hits, -Misses, -Einfügungen und -Verdrängungen,
- normalisierte Annahmen und weitere Lineage-Identitäten.

Erst danach darf eine kanonische Candidate-Freeze den vollständigen
Resultatvertrag serialisieren. Anschließend werden native On-Demand-, Cache-,
Quartikkontroll- und optionale SymPy-Adapter einzeln angebunden.
