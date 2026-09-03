# Exakte endliche Polynom-Lösungen als schematische Planresolution

## Zweck

Der endliche Polynom-Lückensolver erzeugt exakte Belegungen für einen zuvor
festgelegten Ansatz. `ExactFinitePolynomialPlanResolver` verbindet einen solchen
Lauf mit der kanonischen `SchematicProofPlan`-IR, ohne daraus bereits eine
ausführbare Transformation oder formale Proof-Evidence zu machen.

Der gebundene Ablauf lautet:

```text
Quelle + eingefrorener Ansatz + endliche Lückendomänen
  -> formationScopeHash
  -> SchematicProofPlan
  -> vollständiger ExactFinitePolynomialHoleSolver-Lauf
  -> eine SchematicProofPlanResolution je zurückbehaltener Lösung
  -> content-addressed PlanRun
  -> deterministischer vollständiger Replay
```

## Planformation

Der Resolver bildet aus den Formationseingaben einen Hash. Er bindet:

- Resolver-ID und Resolverrevision;
- Solver-ID und Solverrevision;
- Plan-ID und Planlimits;
- Quelle;
- Ansatztext;
- alle Lücken-IDs, Sorten und exakten Werte;
- die Grenze der zurückbehaltenen Lösungen.

Der Plan enthält nur den opaken `formationScopeHash`; Quelle und Ansatz bleiben
Bestandteil des extern eingefrorenen Formationseingangs. Die deklarierte
Informationsgrenze ist `TARGET_FREE_FORMATION`.

Für jede Solverdomäne entsteht genau eine Planlücke:

| Solverdomäne | Planlücke | Plansort |
| --- | --- | --- |
| `COEFFICIENT` | `COEFFICIENT` | `EXACT_RATIONAL` |
| `SIGN` | `SIGN` | `SIGN` |

Domänen-ID, Grammatikrevision und Budgets werden aus der tatsächlich
übergebenen endlichen Domäne abgeleitet. Der Resolver akzeptiert beim späteren
Lauf nur exakt dieselben Hole-Metadaten, nicht lediglich größere kompatible
Budgets.

Version 1 verwendet genau diese lineare Topologie:

```text
FORM_CANDIDATES
  -> SOLVE_HOLES
  -> DISCHARGE_OBLIGATIONS
  -> EMIT_CANDIDATE
```

Der `SOLVE_HOLES`-Schritt stellt eine assumption-free `EQUIVALENT`-Obligation
aus. Checker-Capability und Checkerrevision sind fest an den exakten endlichen
Polynomsolver gebunden. Zusätzliche Selektionsschritte, ausgetauschte
Hole-Grammatiken oder ein anderer Checker werden abgelehnt.

## Resolution

Der Resolver führt den vollständigen endlichen Solverlauf selbst aus. Für jede
zurückbehaltene Lösung erzeugt er:

1. eine `HoleBinding` je exakter Solverbelegung;
2. eine `CONFIRMED`-Outcome-Referenz für die `EQUIVALENT`-Obligation;
3. eine `SchematicProofPlanResolution` im Zustand
   `COMPLETE_REFERENCES`.

Der Checker-Ausführungs-Hash ist der vollständige Content-Hash des
Solverergebnisses. Der Evidence-Hash jeder einzelnen HoleBinding bindet
zusätzlich:

```text
Resolverrevision
+ Planhash
+ Solverergebnishash
+ Lösungshash
+ Lücken-ID
+ kanonischer exakter Wert
```

Ein `ResolvedCandidate` prüft diese Beziehungen erneut. Er akzeptiert keine
Resolution mit veränderten Bindungswerten, ausgetauschten Evidence-Hashes,
falscher Checkerrevision oder fremdem Solverlauf.

## Laufstatus

Die Statusbedeutung des exakten Solvers bleibt erhalten:

```text
COMPLETE_WITHOUT_SOLUTION
  vollständiger endlicher Suchraum geprüft, keine Resolution

COMPLETE_WITH_RESOLUTIONS
  vollständiger Suchraum geprüft, alle Lösungen als Resolution gebunden

COMPLETE_RESOLUTION_SET_TRUNCATED
  vollständiger Suchraum geprüft, aber nur der deklarierte Präfix der
  Lösungsmenge als Resolution gebunden
```

Ein `PlanRun` verlangt eine Eins-zu-eins-Übereinstimmung zwischen den im
Solverergebnis zurückbehaltenen Lösungen und den gebundenen Resolutionen. Sein
Content-Hash bindet Resolverrevision, Plan, Solverergebnis, Status und sämtliche
Kandidaten.

## Replay und Trust Boundary

`replay(...)` wiederholt Planvalidierung, vollständige Solverausführung,
HoleBinding-Erzeugung und Resolutionserzeugung. Erfolg verlangt ein identisches
`PlanRun`-Objekt.

Das ist stärker als eine ungebundene Outcome-Referenz, bleibt aber bewusst
unterhalb einer unabhängigen Proof-Autorität:

- Evidence-Bytes werden noch nicht aus einem externen Store geladen;
- der Replay verwendet dieselbe Implementierung und Revision;
- kein `RewriteProgram` wird erzeugt;
- keine primitive Regelspur wird nachgespielt;
- `COMPLETE_REFERENCES` bleibt kein formaler Beweis- oder Promotionstatus;
- die Ansatzgrammatik ist weiterhin Formationseingabe und wird nicht gelernt.

Plan-ID und Planlimits sind Teil des Formation-Hashes und können deshalb nicht
nachträglich ausgetauscht werden. Ein wissenschaftlicher Lauf muss Plan-ID,
Hole-Namen, Ansatz und Domänen dennoch vor VALIDATION und FINAL TEST einfrieren
und separat auf Informationslecks prüfen.
Die Java-IR allein beweist keine prozessuale Leakage-Freiheit.

## Charakterisierung

Die Integrationstests verwenden dieselben beiden allgemeinen Ansätze wie der
Solver-Slice:

- quadratische Ergänzung mit genau einer Resolution für `shift = 3` und
  `constant = -4`;
- eine quartische Quadratdifferenz mit zwei symmetrischen Resolutionen für
  `alpha = 2` und `beta = +/-2`.

Zusätzlich werden geprüft:

- vollständiges Nullresultat ohne erfundene Resolution;
- gekürzte Ausgabe bei vollständig ausgeführtem Suchraum;
- Austausch von Quelle, Ansatz, Domänen oder Lösungslimit;
- Checker-, Topologie- und Hole-Grammatik-Substitution;
- manipulierte Kandidaten-, Solver-, Plan- und Lauf-Hashes;
- vollständiger deterministischer Replay.

## Modulgrenze

Der Resolver liegt in `:regelsuche-learning`, weil dort die portable
Plan- und Lern-IR liegt. Seine öffentliche API verwendet die exakten
Solverdomänen und -resultate aus `:regelsuche-math-algorithms`; deshalb ist die
Abhängigkeit in Gradle als `api` und im Maven-Reaktor als normale
Compile-Abhängigkeit deklariert. Architektur- und Dependency-Dokumentation
führen diese direkte Kante ausdrücklich auf.

## Reproduktion

```bash
./gradlew \
  :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanResolverTest'

./gradlew --no-configuration-cache ciCheck
```

## Nächster Slice

Als nächste Autoritätsstufe wird ein unabhängiger Evidence-Store-/Verifier-Port
benötigt. Erst wenn Plan, Solverresultat und referenzierte Checker-Evidence aus
persistierten Bytes geladen und unabhängig replayt wurden, darf ein separater
Compiler einen vollständig geprüften Plan in die bestehende
`RewriteProgram`-Ausführung überführen.
