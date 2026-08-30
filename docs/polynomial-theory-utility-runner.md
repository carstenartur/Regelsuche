# Zielblinder Runner der Polynomtheorie-Nutzenstudie

## Status

Der Runner-Vertrag und das reale Nullprofil sind implementiert, die vier
mathematischen Vergleichsprofile aber noch nicht verbunden. Die Qualifikation
bleibt versiegelt; es existieren weder ein fester Ergebnis-Freeze noch eine
Default-, Opt-in- oder Nullentscheidung.

| Bestandteil | Status |
| --- | --- |
| Run-Lebenszyklus und Registry | `IMPLEMENTED_NOT_EXECUTED` |
| `NO_FACTORIZATION` | `IMPLEMENTED_NOT_EXECUTED` |
| Native Faktorisierung, Cache, Quartik, SymPy | `NOT_CONNECTED` |
| Ergebnis-Freeze | `NOT_CREATED` |
| Qualifikation | `SEALED_NOT_OPENED` |

## Run- und Adaptergrenze

Die 600 Eingaben bleiben `RUN_MAJOR_CONTIGUOUS`. Der Runner öffnet je
Profil-/Checkpoint-Paar genau einen Adapter-Run, verarbeitet die 20 Fälle in
eingefrorener Reihenfolge und schließt den Zustand vor dem nächsten der 30
Läufe.

Ein Adapter erhält ausschließlich Run-Deskriptor, Eingabe-Envelope und sichtbare
Fallformation. Qualifikationslabels, Referenzausdrücke, andere Profilergebnisse
und Produktentscheidungen gehören nicht zur API. Die Registry akzeptiert genau
einen Adapter für jedes vorab gebundene Profil-/Adapter-ID-Paar.

Der Runner bricht unter anderem ab bei:

- nicht zusammenhängenden oder unvollständigen Runs;
- abweichender Fallreihenfolge oder gemischten Profilpolitiken;
- fehlenden, doppelten oder falsch zugeordneten Adaptern;
- überschrittenen Arbeitsbudgets oder anderen Vertragsverletzungen.

Eine sonstige Adapter-Laufzeitausnahme wird für die betroffene Eingabe als
`TECHNICAL_FAILURE` erhalten und nicht als mathematischer Miss ausgegeben.

## Outcome und Arbeitsgrenzen

Terminale Ausgänge sind `NO_TRANSITION`, `TRANSITION`, `UNSUPPORTED`,
`BUDGET_INCONCLUSIVE` und `TECHNICAL_FAILURE`. Ein Fall kann mehrere Übergänge
oder Cachezugriffe enthalten; die jeweiligen Anzahlen bleiben eigene Zähler.

Vor der Aufnahme in den Candidate-Freeze gelten fail-closed:

```text
primitiveWork        <= admittedPrimitiveWork
factorizationWork    <= factorizationWork
totalMechanicalWork <= totalMechanicalWork
```

Mechanische Arbeit wird getrennt nach Quellvalidierung, Faktorisierung,
Render/Reparse, Cache-Lookup, Cache-Replay und sonstiger Arbeit erfasst.
Ungenutztes Budget wird nicht zwischen Kanälen verschoben.

## Candidate-Freeze

`PolynomialTheoryUtilityCandidateFreeze` erzeugt je Eingabe eine
inhaltsadressierte Zeile und ein dynamisch inhaltsadressiertes UTF-8/LF-Artefakt.
Zeilen binden IDs, terminales Ergebnis, Verifier-/Transformationsstatus,
Arbeits- und Cachezähler sowie primitive Regeln und Lineages. Konstruktoren
rekonstruieren IDs und kanonische Bytes erneut.

Alle dynamischen Strings werden vollständig JSON-kodiert. Verboten bleiben
Qualifikations- und Entscheidungsfelder. Ein fester Ergebnishash wird erst nach
der tatsächlichen Ausführung aller fünf realen Adapter versioniert.

## Nullprofil

`PolynomialTheoryUtilityNoFactorizationAdapter` erzeugt für seine 120 Eingaben

deterministisch:

```text
NO_TRANSITION / PROFILE_FORBIDS_FACTORIZATION
```

Alle Arbeits-, Verifier-, Transformations- und Cachezähler bleiben null.

## Verifikation und nächster Schritt

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTargetBlindRunnerTest
./gradlew :regelsuche-experiments:check
```

Die Tests decken 30 isolierte Runs, 600 Inputs, Target-Blindheit, JSON-Encoding,
Budget- und Registryfehler, technische Fehler sowie mehrere Übergänge und
Cacheereignisse innerhalb eines Falls ab.

Als nächster eigener Slice wird `ON_DEMAND_VERIFIED_FACTORIZATION` an den
bestehenden exakten Parser-/Request-/Engine-/Verifier-/Transformationspfad
angebunden. Cache, Quartik-Kontrolle und SymPy bleiben getrennte Folgearbeiten.
