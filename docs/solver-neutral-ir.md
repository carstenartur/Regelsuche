# Solver-neutrale Obligation- und Proof-IR

Issue [#233](https://github.com/carstenartur/Regelsuche/issues/233) führt einen versionierten Vertrag zwischen Discovery, Suche, Oracles und späterer Solver-Orchestrierung ein. Die IR ersetzt weder Discovery Evidence noch ein formales Beweisformat. Sie beschreibt exakt, **welche** mathematische Aussage unter **welchen** Annahmen an ein Backend übergeben wurde und bindet jedes Ergebnis an diese Revision.

## Warum ein eigener Vertrag notwendig ist

Bisher enthielten einzelne Komponenten eigene Formen von Proof Obligations, Ausdrucksstrings und Backend-Ergebnissen. Das funktioniert für einen einzelnen Oracle-Aufruf, erschwert aber:

- dieselbe Aufgabe reproduzierbar an mehrere Backends zu senden;
- Fähigkeiten und nicht unterstützte Theorien vor der Ausführung zu prüfen;
- Annahmen ohne stilles Weglassen zu übertragen;
- Refutation, unbekanntes Ergebnis, Timeout und Backendfehler zu unterscheiden;
- Proof Evidence dauerhaft an eine konkrete Problemrevision zu binden.

Das neue Modul `:regelsuche-solver-ir` liegt zwischen diesen Ebenen. Es hängt von `core`, `search` und `validation` ab; `learning` kann seine Proof Evidence an die stabilen Hashes binden.

## Obligation v1

Schema: [`regelsuche.solver-obligation/v1`](schemas/regelsuche-solver-obligation-v1.schema.json)

Eine Obligation enthält:

- deklarierte Symbole mit `REAL`, `INTEGER` oder `BOOLEAN`;
- einen solver-neutralen Ausdrucksbaum aus Literalen, Symbolen, binären Operatoren und Funktionsaufrufen;
- explizite Theorien wie `REAL_ARITHMETIC`;
- strukturierte Annahmen als Prädikate, nicht als undurchsichtige Strings;
- eine Zielrelation;
- die angeforderte Evidence-Stärke;
- Source-Provenance mit exaktem Revisionshash;
- einen kanonischen SHA-256-Hash über alle semantischen Felder.

Beispiel:

```json
{
  "schema": "regelsuche.solver-obligation/v1",
  "obligationId": "additive-identity",
  "declarations": [
    { "name": "x", "sort": "REAL" }
  ],
  "theories": ["REAL_ARITHMETIC"],
  "assumptions": [],
  "goal": {
    "relation": "EQUALS",
    "left": {
      "kind": "BINARY",
      "operator": "ADD",
      "left": { "kind": "SYMBOL", "name": "x" },
      "right": { "kind": "LITERAL", "value": "0" }
    },
    "right": { "kind": "SYMBOL", "name": "x" }
  },
  "requestedEvidence": "DECISION",
  "provenance": {
    "sourceType": "documentation-example",
    "sourceId": "solver-neutral-ir",
    "revisionHash": "sha256:..."
  },
  "contentHash": "sha256:..."
}
```

Deklarationen, Theorien und Annahmen werden kanonisch sortiert. Nicht deklarierte Symbole, doppelte IDs, ungültige Hashes oder inkonsistente `IS_INTEGER`-Prädikate werden bereits beim Aufbau beziehungsweise JSON-Round-Trip abgelehnt.

## Strukturierte Annahmen

Der erste bounded-algebra Slice übersetzt Vergleiche wie:

```text
A > 0
B != 0
n is integer
```

in typisierte Prädikate. Eine unbekannte Freitextannahme wird nicht gespeichert und nicht ignoriert, sondern als nicht unterstützte Übersetzung abgelehnt.

Direkte Backend-Adapter müssen anschließend separat erklären, ob sie diese Prädikate tatsächlich verarbeiten. Der aktuelle interne Search-Adapter und der bestehende symbolische Equivalence-Adapter unterstützen noch keine Annahmen. Eine Obligation mit `x != 0` wird daher vor Backend-Ausführung als:

```text
status = UNSUPPORTED
translationStatus = REJECTED
translationIssues = [ASSUMPTIONS_NOT_SUPPORTED]
```

retained.

## Result v1

Schema: [`regelsuche.solver-result/v1`](schemas/regelsuche-solver-result-v1.schema.json)

Ein Ergebnis unterscheidet:

- `CONFIRMED`;
- `REFUTED`;
- `UNKNOWN`;
- `TIMEOUT`;
- `UNSUPPORTED`;
- `ERROR`.

Zusätzlich wird die Übersetzung separat bewertet:

- `LOSSLESS` — alle IR-Felder wurden unverändert verarbeitet;
- `APPROXIMATED` — ein bestehender Legacy-Pfad wurde ausgeführt, aber eine Einschränkung ist explizit sichtbar;
- `REJECTED` — das Backend wurde wegen inkompatibler Fähigkeiten nicht ausgeführt.

Jedes Resultat enthält den Obligation-, Goal- und Assumptions-Hash, Backend-ID und -Version, Invocation-Hash, verwendete Capabilities, sichtbare Übersetzungsprobleme sowie optionale Counterexample- und Certificate-Hashes.

## Zwei unterschiedliche Backends

### Regelsuche Search

`RegelsucheSearchBackend` übersetzt eine Equality-Obligation verlustfrei zurück in den Core-AST und startet eine begrenzte `BestFirstSearchStrategy` über den normalen `AstRewriteTransformationEngine`.

Ein erreichbares Ziel liefert `CONFIRMED` und einen Hash über den retained Suchpfad. Ein nicht gefundenes Ziel liefert `UNKNOWN`, niemals `REFUTED`: unvollständige Suche ist kein Gegenbeweis.

### Symbolische Equivalence

`SymPySolverBackend` verwendet die vorhandene `EquivalenceService`-/`SymPyEquivalenceService`-Grenze. Ein positiver symbolischer beziehungsweise deterministisch numerischer Nachweis liefert `CONFIRMED`; explizite Nichtäquivalenz liefert `REFUTED`; fehlende Evidenz bleibt `UNKNOWN`.

Der CI-Referenzfall sendet **dieselbe** Obligation `x + 0 = x` an beide Backends. Beide Resultate referenzieren denselben `obligationHash` und bestehen unabhängig.

## Bindung an Open-Target Proof Evidence

`OpenTargetConjectureProofGate` behält seinen bestehenden Proof-Vertrag bei und ergänzt:

- `solverObligationHash`;
- `solverResultHash`;
- `solverTranslationStatus`.

Die alten Equivalence-Services erhalten nur linke und rechte Expression. Enthält eine bestehende Proof Obligation zusätzliche Annahmen, wird dieser Legacy-Link daher ausdrücklich als `APPROXIMATED` markiert:

```text
LEGACY_BACKEND_DID_NOT_CONSUME_STRUCTURED_ASSUMPTIONS
```

Damit bleibt die bisherige Evidence reproduzierbar, ohne fälschlich eine vollständige assumptions-aware Prüfung zu behaupten. Neue direkte Backend-Aufrufe bleiben strenger und antworten in diesem Fall `UNSUPPORTED`.

## Reproduktion

Modultests und kanonische Beispielartefakte:

```bash
./gradlew \
  :regelsuche-solver-ir:test \
  :regelsuche-solver-ir:writeSolverIrExample
```

Ausgabe:

```text
regelsuche-solver-ir/build/reports/solver-ir/
```

Der Workflow `Solver IR`:

1. führt Contract-, Round-Trip- und Backendtests aus;
2. schreibt zwei Obligationen und drei Backendresultate;
3. validiert sie gegen die Draft-2020-12-Schemas;
4. verlangt zwei unabhängige `CONFIRMED`-Resultate für dieselbe Obligation;
5. verlangt ein fail-closed `UNSUPPORTED` für die assumption-bound Obligation;
6. archiviert Evidence und Testdiagnosen.

## Bewusste Grenzen des ersten Slices

- Der Ausdrucksvertrag ist auf den vorhandenen algebraischen Core-AST begrenzt.
- Quantoren, Mengen, Matrizen und allgemeine logische Formeln sind noch nicht enthalten.
- Die direkten Backends verarbeiten derzeit keine strukturierten Annahmen.
- Regelsuche Search kann ein Ziel bestätigen, aber Sucherschöpfung nicht als Refutation deuten.
- Das symbolische Backend liefert noch kein universelles formales Proof-Objekt.

Diese Grenzen bleiben in Capability- und Translation-Status sichtbar. Issue #234 kann darauf aufbauend Backends nach Fähigkeiten, Kosten und geforderter Evidence-Stärke auswählen, ohne eigene Problemformate einzuführen.
