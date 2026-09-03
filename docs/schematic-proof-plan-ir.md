# Schematische Beweispläne: kanonische IR v1

## Zweck

Die exakte Resttermkomposition zeigt, dass Regelsuche mehrere lokal gültige
algebraische Effekte kombinieren kann, ohne die historische Zielgestalt zu
kennen. Der nächste Schritt ist eine Darstellung für **noch nicht vollständig
gebundene Strategien**.

`SchematicProofPlan` ist dafür eine kanonische, begrenzte Zwischenrepräsentation.
Sie darf typisierte Lücken und offene mathematische Verpflichtungen beschreiben,
ist aber ausdrücklich **kein** ausführbares `RewriteProgram`, keine
`Transformation` und kein Beweis.

Der autorisierte Fluss lautet zunächst:

```text
TARGET-freie Formation
  -> SchematicProofPlan mit offenen Lücken und Obligationen
  -> getrennte Hole-Bindings und Checker-Outcomes
  -> SchematicProofPlanResolution
  -> strukturell vollständige Resolution mit bestätigten Referenzen
```

Ein späterer unabhängiger Evidence-Verifier und ein Compiler zu einem gewöhnlichen,
replaybaren `RewriteProgram` sind nicht Teil von v1.

## Informationsgrenze

Version 1 akzeptiert ausschließlich
`informationBoundary = TARGET_FREE_FORMATION`. Der Plan enthält absichtlich
keine Felder für:

- einen Zielausdruck;
- historische Namen oder Familienlabels;
- Referenzresultate;
- VALIDATION- oder FINAL-TEST-Ausgänge;
- Promotion- oder Novelty-Entscheidungen.

`formationScopeHash` bindet den Plan an die eingefrorene Formationseingabe,
ohne deren mögliche spätere Holdouts in den Plan zu kopieren.

## Geordnete Planschritte

V1 verwendet eine kleine lineare Folge statt bereits eine allgemeine
Programmtopologie zu behaupten. Die Reihenfolge ist semantisch relevant.
Unterstützt werden:

```text
FORM_CANDIDATES
SELECT_BINDINGS
SOLVE_HOLES
DISCHARGE_OBLIGATIONS
COMPOSE
EMIT_CANDIDATE
```

Jeder Schritt besitzt eine stabile ID und referenziert deklarierte Lücken und
Obligationen. Der Vertrag verlangt:

- eindeutige Schritt-, Lücken- und Obligation-IDs;
- jede deklarierte Lücke und Obligation wird tatsächlich referenziert;
- der in einer Obligation genannte Aussteller-Schritt referenziert genau diese
  Obligation;
- genau einen abschließenden `EMIT_CANDIDATE`-Schritt;
- der Emissionsschritt hängt von sämtlichen Obligationen ab.

Diese Regeln verhindern, dass eine offene Verpflichtung durch eine nicht
verbundene oder vorzeitige Ausgabe umgangen wird.

## Typisierte Lücken

Die IR unterscheidet Lückenart und Wertsort. Eine Lückenart darf nur mit ihrer
vorgegebenen Sortierung verwendet werden:

| Lückenart | Wertsort |
| --- | --- |
| `COEFFICIENT` | `EXACT_RATIONAL` |
| `SIGN` | `SIGN` |
| `TERM` | `TERM` |
| `OCCURRENCE` | `OCCURRENCE_PATH` |
| `DISJOINT_TERM_PAIR` | `OCCURRENCE_PAIR` |
| `INVARIANT`, `MEASURE`, `WITNESS` | `TERM` |
| `FORMULA` | `FORMULA` |

Jede Lücke bindet zusätzlich:

```text
domainId
grammarRevision
maxCandidates
maxCanonicalBytes
maxScalarBits
maxOccurrenceDepth
```

Die Budgets beschränken bereits die Formation und die spätere Belegung. Sie
sind keine nachträglichen Ausführungsdiagnosen.

## Offene Obligationen

V1 kann folgende Verpflichtungsarten benennen:

```text
EQUIVALENT
ZERO
NON_ZERO
DISJOINT_OCCURRENCES
COMPLETE_SOURCE_COVER
RESIDUAL_SUM_IS_ZERO
CAPABILITY_UNLOCKED
PRESERVES_INVARIANT
STRICTLY_DECREASES
PROVES
```

Eine Obligation enthält:

- ihren ausstellenden Planschritt;
- abhängige Lücken;
- normalisierte Annahmen;
- erforderliche Checker-Capability;
- den exakten Checker-Revisionshash;
- ausschließlich den Anfangszustand `OPEN`.

Ein Formation-Plan kann daher nicht selbst behaupten, eine Verpflichtung sei
schon bestätigt.

## Resolution und terminale Zustände

`SchematicProofPlanResolution` bindet getrennt:

- `HoleBinding`s mit Sort, kanonischem Wert und Evidence-Hash;
- `ObligationOutcome`s mit Status, Checker-Capability, Checker-Revision,
  Execution-Hash und stabilem Detailcode.

Die Zustände werden aus dem vollständigen Inhalt abgeleitet:

```text
PARTIAL
  mindestens eine Bindung oder ein Outcome fehlt,
  und kein vorhandenes Outcome blockiert

BLOCKED
  mindestens ein vorhandenes Outcome ist nicht CONFIRMED

COMPLETE_REFERENCES
  jede Lücke ist gebunden und jede Obligation besitzt ein CONFIRMED-Outcome
```

`REFUTED`, `UNKNOWN`, `UNSUPPORTED`, `BUDGET_INCONCLUSIVE` und `ERROR` bleiben
voneinander getrennt. Keiner dieser Zustände wird als Erfolg interpretiert.

## Planrelative Binding-Prüfung

Die erste Resolution-Grenze prüft bereits:

- Sortübereinstimmung zwischen Lücke und Bindung;
- Lücken- und Gesamtbytegrenzen;
- exakte, kanonische Rationalzahlen aus dem vorhandenen
  `ExactRationalDomain`;
- Zähler-/Nenner-Bitgrenzen;
- Vorzeichen ausschließlich als `-1` oder `1`;
- kanonische AST-Pfade wie `root`, `0` oder `0.1.2`;
- kanonisch geordnete Vorkommenspaare wie `0.1|1.0`;
- Pfadtiefengrenzen;
- exakte Übereinstimmung von Checker-Capability und Checker-Revision mit der
  Obligation.

Für allgemeine `TERM`- und `FORMULA`-Belegungen prüft dieser Slice nur die
kanonische, begrenzte Zeichenrepräsentation. Ihre mathematische Semantik muss
durch die referenzierte spätere Checker-Evidence bestätigt werden.

## Keine Ausführungs- oder Proof-Autorisierung in v1

Eine `COMPLETE_REFERENCES`-Resolution besagt lediglich:

```text
vollständige Bindungsmenge
+ ausschließlich CONFIRMED deklarierte Outcome-Records
+ planrelative Strukturprüfung
```

`isStructurallyCompleteFor(plan)` spielt diese Strukturprüfung gegen den
konkreten Plan erneut ab. Die Methode stellt bewusst kein Autorisierungsobjekt
aus. Insbesondere besagt das Ergebnis noch nicht:

- dass die referenzierten Evidence-Bytes geladen und unabhängig verifiziert
  wurden;
- dass ein ausführbarer Rewrite-Pfad existiert;
- dass primitive Regeln replayt wurden;
- dass ein formaler Beweis vorliegt;
- dass der Plan für Produktion, Promotion oder Public Evidence autorisiert ist.

Es existiert in diesem Slice absichtlich keine Methode von
`SchematicProofPlan` oder `SchematicProofPlanResolution` zu
`Transformation` oder `RewriteProgram`. Ein späterer Verifier muss die
referenzierten Evidence-Artefakte selbst laden und prüfen, bevor er eine stärkere
Autorität ausstellt.

## Kanonisierung und Grenzen

Schritte behalten ihre deklarierte Reihenfolge. Lücken, Obligationen,
Abhängigkeiten und Annahmen werden deterministisch normalisiert. Plan und
Resolution besitzen jeweils einen SHA-256-Hash über das kanonische JSON ohne das
eigene Hashfeld; Konstruktoren berechnen die Hashbeziehung erneut.

Plan- und Resolutiongrößen sind begrenzt. Direkte Konstruktion mit falschem
Hash, falschem abgeleitetem Zustand, unbekannten IDs, doppelten Bindings oder
einer ausgetauschten Checker-Revision wird fail-closed abgelehnt.

Die `schema`-IDs dieses ersten Slices versionieren die Java-interne IR und ihre
kanonische Darstellung. Ein öffentlicher Dateicodec, strikte externe
JSON-Schemas, unabhängige Evidence-Dereferenzierung und Manifest-last-Exports
bleiben nachfolgende, getrennte Review-Einheiten.

## Charakterisierung

```bash
./gradlew \
  :regelsuche-learning:test \
  --tests '*SchematicProofPlanTest'
```

Die Tests decken unter anderem ab:

- deterministische Definitionensortierung bei erhaltener Schrittfolge;
- Sortfehler, unbekannte Referenzen und inkonsistente Obligation-Aussteller;
- vorzeitige und doppelte Emission;
- `PARTIAL`, sämtliche blockierenden Outcome-Status und `COMPLETE_REFERENCES`;
- nichtkanonische Rationalzahlen und ungültige Vorzeichen;
- ausgetauschte Checker-Revisionsgrenzen;
- kanonische Vorkommenspaare und Tiefenbudgets;
- Plan-Substitution, doppelte IDs, Größenüberschreitung und Hash-/State-Tampering.

## Nächster Slice

Auf dieser IR kann #874 als Nächstes einen eng begrenzten Hole-Solver aufbauen:

1. endliche Vorzeichenbelegung;
2. exakte lineare rationale Koeffizientenbedingungen;
3. Erzeugung der zugehörigen Checker-Outcomes;
4. unabhängiges Laden und Prüfen der referenzierten Evidence;
5. erst danach ein expliziter Compiler für vollständig geprüfte Pläne in die
   vorhandene `RewriteProgram`-Ausführung.
