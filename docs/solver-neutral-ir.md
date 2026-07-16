# Solver-neutrale Obligation- und Proof-IR

Issue [#233](https://github.com/carstenartur/Regelsuche/issues/233) führt einen versionierten Vertrag zwischen Discovery, Suche, Oracles und späterer Solver-Orchestrierung ein. Die IR ersetzt weder Discovery Evidence noch ein formales Beweisformat. Sie beschreibt exakt, **welche** mathematische Aussage unter **welchen** Annahmen an ein Backend übergeben wurde und bindet jedes Ergebnis an diese Revision.

## Bewusste inkompatible Ablösung

Regelsuche hat keine externen Nutzer der bisherigen internen Proof-Records. Deshalb wurde keine Kompatibilitätsschicht beibehalten:

- `OpenTargetConjectureProofGate.ProofObligation` wurde entfernt;
- `regelsuche.open-target-conjecture-proof/v1` wurde durch v2 ersetzt;
- `proof-obligation.json` wurde entfernt;
- der Production Lifecycle wurde von v2 auf v3 angehoben;
- Production Campaign, Promotion und Bridge Qualification verwenden unmittelbar `SolverIr.Obligation` und `SolverIr.SolverResult`;
- es gibt keinen Legacy-Adapter und keinen alternativen Proof-Pfad.

Damit existiert nur ein autoritatives Obligation-Modell. Alte intern erzeugte Zwischenformate werden nicht gelesen, migriert oder parallel weitergeschrieben.

## Warum ein eigener Vertrag notwendig ist

Die früheren Komponenten enthielten eigene Formen von Proof Obligations, Ausdrucksstrings und Backend-Ergebnissen. Das erschwerte:

- dieselbe Aufgabe reproduzierbar an mehrere Backends zu senden;
- Fähigkeiten und nicht unterstützte Theorien vor der Ausführung zu prüfen;
- Annahmen ohne stilles Weglassen zu übertragen;
- Refutation, unbekanntes Ergebnis, Timeout und Backendfehler zu unterscheiden;
- Proof Evidence dauerhaft an eine konkrete Problemrevision zu binden.

Das Modul `:regelsuche-solver-ir` ist nun die gemeinsame Grenze. Es hängt von `core`, `search` und `validation` ab; `learning`, Autopilot und Promotion referenzieren seine stabilen Hashes und Ergebnisse direkt.

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

Jeder Backend-Adapter muss erklären, ob er diese Prädikate tatsächlich verarbeitet. Der aktuelle interne Search-Adapter und der symbolische Equivalence-Adapter unterstützen noch keine Annahmen. Eine Obligation mit `x != 0` wird daher **vor** Backend-Ausführung als:

```text
status = UNSUPPORTED
translationStatus = REJECTED
translationIssues = [ASSUMPTIONS_NOT_SUPPORTED]
```

retained. Es gibt keinen Fallback auf einen assumptions-blinden Aufruf.

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

- `LOSSLESS` — alle relevanten IR-Felder wurden unverändert verarbeitet;
- `APPROXIMATED` — ein Backend führt eine ausdrücklich deklarierte, überprüfbare Approximation aus;
- `REJECTED` — das Backend wurde wegen inkompatibler Fähigkeiten nicht ausgeführt.

`APPROXIMATED` ist kein Kompatibilitätsmodus. Die aktuellen beiden Backend-Integrationen liefern ausschließlich `LOSSLESS` oder `REJECTED`. Spätere Approximationen müssen ihre Abweichung in `translationIssues` sichtbar machen und dürfen nicht als verlustfreie Bestätigung ausgegeben werden.

Jedes Resultat enthält den Obligation-, Goal- und Assumptions-Hash, Backend-ID und -Version, Invocation-Hash, verwendete Capabilities, sichtbare Übersetzungsprobleme sowie optionale Counterexample- und Certificate-Hashes.

## Zwei unterschiedliche Backends

### Regelsuche Search

`RegelsucheSearchBackend` übersetzt eine Equality-Obligation verlustfrei zurück in den Core-AST und startet eine begrenzte `BestFirstSearchStrategy` über den normalen `AstRewriteTransformationEngine`.

Ein erreichbares Ziel liefert `CONFIRMED` und einen Hash über den retained Suchpfad. Ein nicht gefundenes Ziel liefert `UNKNOWN`, niemals `REFUTED`: unvollständige Suche ist kein Gegenbeweis.

### Symbolische Equivalence

`SymPySolverBackend` verwendet die vorhandene `EquivalenceService`-/`SymPyEquivalenceService`-Grenze. Ein positiver symbolischer beziehungsweise deterministisch numerischer Nachweis liefert `CONFIRMED`; explizite Nichtäquivalenz liefert `REFUTED`; fehlende Evidenz bleibt `UNKNOWN`.

Der CI-Referenzfall sendet **dieselbe** Obligation `x + 0 = x` an beide Backends. Beide Resultate referenzieren denselben `obligationHash` und bestehen unabhängig.

## Open-Target Proof Evidence v2

Schema: [`regelsuche.open-target-conjecture-proof/v2`](schemas/regelsuche-open-target-conjecture-proof-v2.schema.json)

`OpenTargetConjectureProofGate` erzeugt unmittelbar eine `SolverIr.Obligation`, übergibt sie an genau ein `SolverBackend` und retains das zugehörige `SolverResult`. Der Report enthält deren Hashes und Backendstatus; er besitzt kein zweites eingebettetes Obligation-Modell.

Eine Proof-Evidence gilt nur dann als symbolisch verifiziert, wenn:

- das Resultat zu exakt derselben Obligation gehört;
- der Backendstatus `CONFIRMED` ist;
- die Übersetzung `LOSSLESS` ist;
- keine Proof-Blocker vorliegen.

Nicht unterstützte Annahmen führen zu `INCONCLUSIVE` plus `UNSUPPORTED`-Resultat und blockieren Lifecycle, Qualification und Promotion.

## Production Lifecycle v3

Schema: [`regelsuche.autonomous-production-lifecycle/v3`](schemas/regelsuche-autonomous-production-lifecycle-v3.schema.json)

Die Production Campaign schreibt:

```text
proof-report.json
solver-obligation.json
solver-result.json
production-lifecycle-run.json
```

`proof-obligation.json` und das Lifecycle-v2-Schema existieren nicht mehr. Campaign-Manifest, Release Readiness und Docker-Reproduktion binden die beiden neuen Solver-Artefakte separat und validieren sie gegen ihre Schemas.

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

Die Workflows `Autopilot Evidence` und `Release Readiness` validieren zusätzlich Proof Report v2, Production Lifecycle v3 sowie die exakte Hashverkettung von Obligation, Resultat, Proof und Campaign.

## Bewusste Grenzen des ersten Slices

- Der Ausdrucksvertrag ist auf den vorhandenen algebraischen Core-AST begrenzt.
- Quantoren, Mengen, Matrizen und allgemeine logische Formeln sind noch nicht enthalten.
- Die direkten Backends verarbeiten derzeit keine strukturierten Annahmen.
- Regelsuche Search kann ein Ziel bestätigen, aber Sucherschöpfung nicht als Refutation deuten.
- Das symbolische Backend liefert noch kein universelles formales Proof-Objekt.

Diese Grenzen bleiben in Capability- und Translation-Status sichtbar. Issue #234 kann darauf aufbauend Backends nach Fähigkeiten, Kosten und geforderter Evidence-Stärke auswählen, ohne eigene Problemformate einzuführen.
