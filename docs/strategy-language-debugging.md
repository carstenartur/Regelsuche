# Strategie-DSL, Tracing und visualisierte Regelausführung

> **Status:** Architektur- und Umsetzungskonzept, Stand 24. Juli 2026  
> **Release-Grenze:** Dieses Dokument gehört zur Dokumentation des aktuellen Architekturstands. Die beschriebene Sprache, Strategy-IR, Trace-Runtime und Debugger-Oberfläche werden erst nach einem stabilen Release des bestehenden Systems implementiert.  
> **Claim-Grenze:** Das Konzept erweitert weder die implementierten Fähigkeiten noch die wissenschaftlichen Claims eines Releases.

Dieses Dokument beschreibt eine domänenspezifische Sprache für komponierbare mathematische Suchstrategien sowie die dafür notwendige Beobachtungs-, Trace- und Debugger-Architektur. Ziel ist, größere konzeptionelle Änderungen an Regelsuche künftig als kleine, lesbare Strategieprogramme ausdrücken zu können, ohne mathematische Korrektheit, Reproduzierbarkeit oder Nachvollziehbarkeit hinter einer neuen Abstraktionsschicht zu verbergen.

Verwandte Dokumentation: [Lernend geführte Regelsuche](learning-guided-search.md), [Discovery Engine](discovery-engine.md), [AST-Regelradar](ast-rule-radar.md), [Makroregeln](macro-rules.md), [Web-Workbench](web-workbench.md), [Replay und Reports](replay-and-reports.md) und [Release Readiness](release-readiness.md).

## 1. Ausgangslage

Regelsuche enthält bereits viele der Bausteine, die in unterschiedlichen erfolgreichen mathematischen Suchsystemen wiederkehren:

- mathematische Ausdrücke und andere Zustände;
- Regeln und parametrisierte Muster;
- konkrete Anwendungen einer Regel an einer Position;
- Suchstrategien, Heuristiken und Budgets;
- Equality Saturation und E-Graph-Verarbeitung;
- gelernte und deklarative Makroregeln;
- Validierung, Gegenbeispielsuche und Proof-Backends;
- reproduzierbare Evidence- und Replay-Artefakte.

Diese Bausteine sind derzeit jedoch zu stark mit konkreten Java-Kompositionen, spezialisierten Runnern und einzelnen Evaluationspfaden verbunden. Eine neue Suchidee kann deshalb Änderungen an vielen Stellen erfordern, obwohl sie konzeptionell nur aus einer anderen Kombination bekannter Mechanismen besteht.

Die Zielarchitektur soll diese Situation umkehren:

> Java implementiert stabile, wiederverwendbare Mechanismen. Ein kleines Strategieprogramm beschreibt sichtbar, wie diese Mechanismen zu einem mathematischen Suchverfahren zusammengesetzt werden.

Eine solche Sprache ist nur dann ein Fortschritt, wenn ihre Ausführung mindestens ebenso gut verständlich ist wie die heutige direkte Implementierung. Ohne Source Maps, Trace, Matcher-Erklärungen und einen fachlichen Debugger würde die DSL lediglich eine weitere undurchsichtige Schicht erzeugen.

## 2. Ziele

Die Sprache und ihre Runtime sollen folgende Ziele gleichzeitig erfüllen.

### 2.1 Kleine Programme für große konzeptionelle Varianten

Bekannte Suchansätze sollen sich als kurze, vergleichbare Programme ausdrücken lassen:

- faire Enumeration;
- Best-First- und A*-Varianten;
- Beam Search;
- Monte-Carlo-Baumsuche;
- Equality Saturation;
- gelernte Regel- oder Taktikpriorisierung;
- Checkpoint- und Zwischenzielsuche;
- Makrobildung aus Suchspuren;
- evolutionäre Strategie- oder Regelvariation.

Der wissenschaftliche Unterschied zwischen zwei Experimenten soll nach Möglichkeit als kleiner Programmdiff sichtbar sein.

### 2.2 Durchgängige Beobachtbarkeit

Für jede relevante Entscheidung muss rekonstruierbar sein:

- welche DSL-Stelle sie ausgelöst hat;
- welcher Strategy-IR-Knoten ausgeführt wurde;
- welcher Suchzustand vorlag;
- welche AST- oder E-Graph-Position betrachtet wurde;
- welche Regelinstanz geprüft wurde;
- wie das Pattern Matching verlief;
- welche Bindungen und Nebenbedingungen entstanden;
- warum eine Aktion verworfen, priorisiert, zurückgestellt oder ausgewählt wurde;
- welcher Folgezustand erzeugt wurde;
- welche Verifikation anschließend stattfand.

### 2.3 Trennung von Wahrheit, Strategie und Lernen

Die Sprache muss klar zwischen drei Ebenen unterscheiden:

1. **Mathematische Regeln** definieren zulässige Relationen oder Transformationen.
2. **Strategien und Taktiken** bestimmen, wann und in welcher Reihenfolge diese Regeln untersucht werden.
3. **Studien und Lernprozesse** erzeugen, bewerten oder verändern Strategien und Regelkandidaten.

Ein lernendes Modell darf Aktionen priorisieren oder Zwischenziele vorschlagen. Es darf weder eine mathematische Aussage als wahr deklarieren noch formale Gates umgehen.

### 2.4 Reproduzierbarkeit und Claim-Sicherheit

Jeder Lauf muss die tatsächlich verwendeten Regeln, Programme, Modelle, Plugins, Budgets und externen Backends binden. Debugging darf das kanonische Suchergebnis nicht verändern. Wissenschaftliche Evidence darf nicht aus unkontrolliertem Debug-Logging abgeleitet werden.

## 3. Nichtziele

Die erste Sprachversion soll ausdrücklich nicht:

- eine allgemeine, Turing-vollständige Programmiersprache sein;
- beliebiges Dateisystem-, Netzwerk- oder Prozess-I/O erlauben;
- Java-Plugins vollständig ersetzen;
- mathematische Gültigkeit durch Typen oder Modellwerte vortäuschen;
- jede existierende Regelsuche-Komponente sofort abbilden;
- vollständige symbolische KI-Forschung in einer einzigen Syntax vereinheitlichen;
- das bestehende System vor dem nächsten stabilen Release umbauen.

Die Sprache ist eine begrenzte Orchestrierungs- und Forschungsnotation über einem kontrollierten Runtime-Kern.

## 4. Architekturüberblick

Die Zielpipeline lautet:

```text
.rsl-Quelldateien
    ↓
Parser und konkrete Syntaxstruktur
    ↓
typisierte Sprach-ASTs
    ↓
Strategy-IR und Rule-IR
    ↓
statische Analysen und Ausführungsplan
    ↓
Regelsuche Virtual Machine
    ↓
kanonischer Laufzustand + typisierter Trace-Event-Strom
    ↓
Web-Debugger / LSP / Debug Adapter / Evidence-Projektion
```

Die DSL ist nicht die primäre Laufzeitrepräsentation. Die stabile Mitte ist eine versionierte, typisierte **Strategy-IR**. Sowohl die spätere Textsprache als auch eine interne Java-DSL kompilieren in dieselbe IR. Dadurch kann die Semantik vor der endgültigen Syntax entwickelt und getestet werden.

Die Runtime ist eine inspizierbare virtuelle Maschine für mathematische Suchstrategien. Sie besteht mindestens aus folgenden Rollen:

```text
Domain
State
Rule
Pattern
PositionSelector
ActionGenerator
Guard
Ranker
Scheduler
Workspace
GoalPredicate
Verifier
Learner
Observer
Budget
```

## 5. Drei Sprachebenen

### 5.1 Mathematische Objektregeln

Eine Objektregel beschreibt eine mathematische Relation. Die Syntax muss den Charakter der Relation sichtbar machen.

```text
rule factor_common
    Add(Mul(x, y), Mul(x, z))
    <=>
    Mul(x, Add(y, z))
where
    x : RingExpression
    y : RingExpression
    z : RingExpression
proof
    ring_normalizer
```

Vorgesehene Relationsarten sind mindestens:

```text
<=>   nachgewiesene oder beweispflichtige Äquivalenz
=>    gerichtete logische Folgerung
~>    heuristische oder repräsentationsändernde Transformation
```

Eine `~>`-Transformation darf nie stillschweigend als Äquivalenz gelten. Ihr Einsatz kann beispielsweise für Kandidatenbildung zulässig sein, nicht aber für einen äquivalenzbehauptenden Replay-Pfad ohne nachgelagerte Verifikation.

### 5.2 Taktiken und Suchstrategien

Eine Taktik gruppiert mehrere primitive Schritte oder beschreibt einen lokalen Transformationsplan.

```text
tactic expose_and_factor =
    sequence(
        normalize(associativity, commutativity),
        apply(factor_common, at = common_ancestor(equal_factor)),
        simplify
    )
```

Eine Suchstrategie orchestriert Aktionsquellen, Filter, Scores, Scheduler, Budgets und Verifikation.

```text
strategy guided_rewrite_search {
    workspace = ast

    actions =
        applicable(
            rules = core_rules + promoted_macros,
            positions = every_position(order = preorder)
        )

    actions = actions
        |> require(type_safe)
        |> require(assumptions_satisfied)
        |> require(not_exact_cycle)
        |> require(within_growth_limit)

    priority =
          4.0 * exposes_common_structure
        + 2.0 * reduces_goal_distance
        + 1.0 * reduces_ast_size
        + model("rewrite-policy-v1")

    scheduler = interleave(
        best_first(priority),
        fair(actions),
        ratio = 19 : 1
    )

    stop = solved or budget(
        expanded_states = 100_000,
        depth = 40,
        generated_actions = 2_000_000
    )

    verify = symbolic_then_smt
}
```

### 5.3 Studien und Lernprozesse

Training, Splits, Makrobildung und Evaluation gehören in eine eigene Sprachebene. Sie dürfen nicht als unsichtbare Seiteneffekte einer normalen Suche auftreten.

```text
study learn_factorization_strategies {
    split corpus by structural_family
        into train, validation, final_test

    repeat rounds = 20 {
        traces = solve(train, strategy = guided_rewrite_search)

        proposals = traces
            |> successful
            |> compress_paths
            |> anti_unify
            |> prove
            |> evaluate(validation)

        promoted_macros += proposals
            |> require(no_regression)
            |> require(held_out_gain)

        retrain policy from traces
    }

    evaluate_once final_test
}
```

Die bestehende Evidence- und Split-Architektur bleibt maßgeblich. Die DSL darf keine Abkürzung um Leakage-, Proof-, Novelty- oder Release-Gates schaffen.

## 6. Zentrale Laufzeittypen

Ein Suchzustand soll in der Sprache nicht auf einen einzelnen Ausdruck reduziert werden. Ein allgemeiner Zustand umfasst mindestens:

```text
State<Domain> {
    workspace
    goal
    assumptions
    history
    budgets
    evidence_context
}
```

Eine konkrete Aktion ist eine vollständig instanziierte Anwendung:

```text
Action<Domain> {
    rule
    position
    bindings
    parameters
    preconditions
    predicted_cost
}
```

Eine typische Identität ist:

```text
action = (rule, position, substitution, parameters)
```

Nicht nur die Regel, sondern auch AST-Position, Variablenbelegung und gegebenenfalls erzeugte Hilfsobjekte gehören zur Aktion.

## 7. Effektiv enumerierbare Aktionsräume

Abzählbarkeit allein reicht nicht. Die Runtime benötigt für jede Aktionsquelle eine effektive, deterministische Enumeration mit endlichen Präfixen.

Für normale Pattern-Regeln auf einem endlichen AST ist die konkrete Menge an Treffern meist endlich. Für Generatoren wie

- beliebige Hilfsterme;
- neue Lemmakandidaten;
- neue Variablen oder Invarianten;
- neue Makroprogramme;

ist der Raum potentiell unendlich.

Die Sprache behandelt solche Quellen als lazy enumerierbare Ströme:

```text
lemma_candidates = synthesize_lemma(
    grammar = proposition_grammar,
    assumptions = current_context,
    order = description_length
)
```

Die operative Semantik verlangt:

1. eine versionierte Grammatik;
2. eine kanonische Reihenfolge;
3. endliche Präfixe;
4. ein explizites Öffnungsbudget;
5. stabile Kandidatenidentitäten.

Progressive Erweiterung wird als eigener Kombinator ausgedrückt:

```text
actions = union(
    applicable(existing_rules, every_position),
    progressive_widen(
        synthesize_lemma(proposition_grammar),
        initial = 4,
        growth = logarithmic
    )
)
```

## 8. Harte Filter, weiche Priorisierung und bewusstes Pruning

Die Sprache muss drei semantisch verschiedene Operationen trennen.

### 8.1 `require`

```text
actions |> require(type_safe)
```

Die Bedingung beschreibt eine notwendige Zulässigkeit. Eine Verletzung macht die Aktion formal oder vertraglich ungültig.

Geeignete Fälle:

- Typfehler;
- nicht erfüllte zwingende Nebenbedingungen;
- bekannte identische Zyklen;
- überschrittene harte Ressourcenlimits;
- ungültige Evidence-Bindungen.

### 8.2 `prioritize`

```text
actions |> prioritize(model_score + structural_score)
```

Die Aktion bleibt erreichbar, wird aber früher oder später eingeplant.

### 8.3 `prune`

```text
actions |> prune(outside_top_k(100))
```

Die Aktion wird endgültig entfernt. Dadurch kann die Suche unvollständig werden. Die Sprache verlangt deshalb eine sichtbare Deklaration:

```text
strategy beam_search
    allows incompleteness
{
    ...
}
```

Der Compiler soll ein unmarkiertes `prune` ablehnen.

## 9. Typ- und Eigenschaftssystem

Die Sprache soll neben Datentypen auch relevante Such- und Evidence-Eigenschaften verfolgen.

Beispielhafte Typen:

```text
Term<CommutativeRing>
Rule<CommutativeRing, Equivalent>
Rule<RealArithmetic, Implies>
Action<PolynomialDomain>
Strategy<PolynomialDomain>
Verifier<Equivalent>
Enumerable<Action<PolynomialDomain>>
```

Beispielhafte Eigenschaften und Effekte:

```text
Sound
EquivalencePreserving
Deterministic
Replayable
Fair
FiniteAtState
Enumerable
Incomplete
RequiresModel
RequiresExternalSolver
UsesUnverifiedHeuristic
```

Ein Compilerbericht könnte lauten:

```text
strategy guided_rewrite_search

soundness:
    retained results require symbolic_then_smt

enumeration:
    infinite but effective

fairness:
    preserved by 1/20 reserve schedule

determinism:
    conditional on model artifact sha256:...

completeness:
    complete within declared grammar and budget

external effects:
    model inference
    SMT verification
```

## 10. Positionen als erstklassige Werte

AST-Positionen dürfen nicht nur interne Pfadstrings sein. Die Sprache braucht typisierte Selektoren:

```text
every_position
root
leaves
bottom_up
top_down
children_of(pattern)
ancestors_of(pattern)
common_ancestor(match_a, match_b)
goal_relevant_subtrees
positions_where(rule_is_applicable)
```

Beispiel:

```text
apply(
    reverse_distributivity,
    at = common_ancestor(
        equal_subterm(left_branch),
        equal_subterm(right_branch)
    )
)
```

Der Positionsbegriff muss erweiterbar sein:

```text
AstPosition
EGraphPosition
ProofGoalPosition
SequencePosition
```

## 11. Strategy-IR

Die erste Implementierungsstufe ist keine Textsyntax, sondern eine kleine, versionierte Strategy-IR.

Ein minimaler Kern besteht aus Kombinatoren wie:

```text
Generate
Map
Require
Prioritize
Choice
Sequence
Interleave
ProgressiveWiden
Schedule
Expand
Verify
Observe
Stop
```

Jeder IR-Knoten besitzt:

- eine stabile `StrategyNodeId`;
- einen Typ;
- deklarierte Effekte;
- Kindknoten in kanonischer Reihenfolge;
- einen Source-Map-Eintrag;
- eine versionierte Parameterstruktur;
- eine semantische Hash-Identität.

Die IR muss serialisierbar und unabhängig prüfbar sein. Ein DSL-Programm und eine Java-DSL-Konstruktion, die dieselbe Strategie ausdrücken, sollen auf dieselbe semantische IR normalisieren können.

## 12. Stabile Identitäten und Source Maps

Durchgängige Nachvollziehbarkeit benötigt stabile Identitäten für mindestens:

```text
ProgramId
ModuleId
StrategyNodeId
RuleId
PatternNodeId
SearchStateId
WorkspaceId
AstId
AstNodeId
ActionId
MatchAttemptId
QueueEntryId
VerificationId
TraceEventId
```

Jede Laufzeitaktion muss auf ihren Ursprung zurückführbar sein:

```text
DSL-Quelldatei und Textbereich
        ↓
Sprach-AST
        ↓
Strategy-IR-Knoten
        ↓
Ausführungsplan-Knoten
        ↓
Laufzeitaktion
        ↓
AST- oder E-Graph-Position
        ↓
Folgezustand
```

Ein Source-Map-Eintrag enthält mindestens:

```text
source_file
start_line
start_column
end_line
end_column
program_id
strategy_node_id
```

Generierte Programme oder Makros benötigen zusätzlich eine Herkunftskette zu den Suchspuren und Generalisierungsschritten, aus denen sie entstanden sind.

## 13. Typisierter Trace-Event-Strom

Die Runtime koppelt sich nicht direkt an eine Oberfläche. Sie erzeugt einen versionierten Ereignisstrom.

### 13.1 Ereignisfamilien

```text
RUN_STARTED
RUN_FINISHED

STRATEGY_ENTERED
STRATEGY_EXITED
STRATEGY_BRANCH_CHOSEN

STATE_CREATED
STATE_SELECTED
STATE_REJECTED
GOAL_EVALUATED

POSITION_ENUMERATED
POSITION_SKIPPED

RULE_CONSIDERED
RULE_SKIPPED
MATCH_STARTED
PATTERN_NODE_MATCHED
PATTERN_NODE_REJECTED
BINDING_CREATED
BINDING_REUSED
BINDING_CONFLICT
GUARD_EVALUATED
MATCH_SUCCEEDED
MATCH_FAILED

ACTION_GENERATED
ACTION_REJECTED
ACTION_SCORED
ACTION_QUEUED
ACTION_SELECTED
ACTION_APPLIED

VERIFICATION_STARTED
VERIFICATION_FINISHED

MODEL_REQUESTED
MODEL_RESPONDED

BUDGET_UPDATED
BUDGET_EXHAUSTED

SNAPSHOT_CREATED
CHECKPOINT_CREATED
```

### 13.2 Gemeinsamer Event-Umschlag

Jedes Ereignis enthält mindestens:

```text
schema
run_id
logical_sequence
physical_timestamp
kind
strategy_node_id
source_span
state_id
parent_event_id
payload
```

Nicht jedes Ereignis besitzt Regel-, Positions- oder Aktionsfelder. Spezifische Payloads sind strikt typisiert.

Beispiel:

```json
{
  "schema": "regelsuche.strategy-trace-event/v1",
  "runId": "run-2026-07-24-001",
  "logicalSequence": 241,
  "kind": "BINDING_CONFLICT",
  "strategyNodeId": "guided-factorization/applicable/1",
  "stateId": "state-17",
  "ruleId": "factor-common",
  "matchAttemptId": "match-93",
  "position": [1, 0],
  "patternNodeId": "pattern-x-second-occurrence",
  "astNodeId": "ast-17-node-12",
  "payload": {
    "expectedBinding": "a",
    "observedSubtree": "b"
  },
  "source": {
    "file": "strategies/factorization.rsl",
    "line": 22,
    "column": 13
  }
}
```

## 14. Trace-Stufen und Filter

Ein vollständiger Matcher-Trace kann sehr groß werden. Deshalb gibt es abgestufte Trace-Profile.

```text
trace off
trace summary
trace decisions
trace actions
trace matcher
trace full
```

### 14.1 `summary`

Enthält:

- ausgewählte Zustände;
- angewandte Aktionen;
- Ziele und terminale Ergebnisse;
- Budgetstände;
- Verifikationsresultate.

### 14.2 `decisions`

Zusätzlich:

- Rankingkomponenten;
- Queue-Entscheidungen;
- betrachtete Top-Kandidaten;
- Auswahlgrund des Schedulers.

### 14.3 `actions`

Zusätzlich:

- alle erzeugten Regelinstanzen;
- Positionen und Bindungen;
- harte Filter und Zurückweisungsgründe.

### 14.4 `matcher`

Zusätzlich:

- Matching jedes Pattern-Knotens;
- Backtracking;
- Bindungskonflikte;
- Guard-Auswertungen.

### 14.5 `full`

Zusätzlich:

- Modellanfragen und Modellantworten;
- Scheduler-Snapshots;
- Verifier-Zwischenschritte;
- periodische Zustands- und Workspace-Snapshots.

Trace-Filter sind Teil des Laufvertrags:

```text
trace matcher {
    rules = [factor_common, reverse_distributivity]
    states = 10..30
    positions = below(selected_node)
    stop_after = 50_000 events
}
```

Filter dürfen nur die Aufzeichnung, nicht die Suchentscheidung beeinflussen.

## 15. Tracing darf das Suchverhalten nicht verändern

Eine zentrale Invariante lautet:

```text
canonical_result(trace = off)
    ==
canonical_result(trace = full)
```

Abweichungen sind nur bei nichtkanonischen Laufzeitdiagnosen wie realer Dauer oder Speicherverbrauch zulässig.

Daraus folgen folgende Regeln:

- Trace-Ereignisse werden nach einer Entscheidung erzeugt.
- Listener erhalten unveränderliche Daten.
- Trace-Listener dürfen keine Suchobjekte oder Budgets verändern.
- Debugger-Pausen zählen nicht gegen logische Budgets.
- kanonische IDs dürfen nicht von Thread-Timing abhängen;
- Modellantworten werden für Replay gebunden;
- parallele Ausführung besitzt eine logische und eine physische Ordnung.

Die wissenschaftliche Evidence verwendet die logische Ordnung. Physische Zeitstempel bleiben nichtkanonische Diagnostik.

## 16. Debugger-Semantik

Der Debugger arbeitet auf Strategy-IR und fachlichen Laufzeitereignissen, nicht auf Java-Methodenaufrufen.

### 16.1 Breakpoints

Neben Quellzeilen-Breakpoints werden fachliche Breakpoints unterstützt:

```text
break on rule factor_common
break on match_failure rule factor_common
break on binding_conflict
break on state where ast_size > 100
break on action where score < 0
break on verification_failure
break on budget remaining_states < 1000
break on first_use macro expose_and_factor
```

Bedingte Breakpoints verwenden eine reine, nebenwirkungsfreie Abfragesprache.

### 16.2 Schrittarten

```text
Step Into Strategy
Step Over Strategy
Step Out

Next State
Next Position
Next Rule
Next Match Attempt
Next Successful Match
Next Generated Action
Next Selected Action
Next Applied Action
Next Verification
```

`Step Into` auf `applicable(core_rules, every_position)` springt zunächst in die Positionsenumeration, dann in die konkrete Regel und schließlich in den Matcher.

`Step Over` führt den gesamten Kombinator aus und hält mit der erzeugten Ergebnis- oder Aktionsmenge an.

### 16.3 Fachliche Stack Frames

Ein Debugger-Stack kann so aussehen:

```text
guided_factorization
  └─ interleave(best_first, fair)
      └─ prioritize(global_score)
          └─ applicable(core_rules, every_position)
              └─ rule factor_common
                  └─ position /arguments/1
                      └─ pattern Mul(x, y)
```

Beobachtbare Werte hängen vom Frame ab.

Strategie-Frame:

```text
current_state
goal
remaining_budget
queue_size
visited_states
```

Ranking-Frame:

```text
action
structural_score
goal_distance_score
model_score
exploration_bonus
final_score
```

Matcher-Frame:

```text
pattern_node
workspace_node
bindings
remaining_alternatives
guards
failure_reason
```

### 16.4 Watches

Watches sind reine Projektionen:

```text
state.expression
state.goal
state.depth

action.rule
action.position
action.bindings
action.score

queue.top(10)
queue.count(rule = factor_common)

matches.at(selected_ast_node)
budget.expanded_states
```

## 17. Strukturierte Matcher-Erklärungen

Der Matcher darf nicht nur `true`, `false` oder `Optional<Bindings>` liefern. Das Ergebnis ist strukturiert:

```java
sealed interface MatchResult {
    record Success(Bindings bindings, MatchTrace trace)
        implements MatchResult {}

    record Failure(MatchFailure reason, MatchTrace trace)
        implements MatchResult {}
}
```

Ein `MatchFailure` besitzt mindestens:

```text
failure_kind
pattern_node_id
workspace_node_id
position
expected
observed
bindings_so_far
guard_result
backtracking_context
```

Vorgesehene Ursachen:

```text
ROOT_KIND_MISMATCH
ARITY_MISMATCH
TYPE_MISMATCH
LITERAL_MISMATCH
BINDING_CONFLICT
GUARD_FAILED
ASSUMPTION_UNPROVEN
ASSOCIATIVE_PARTITION_EXHAUSTED
COMMUTATIVE_ALTERNATIVES_EXHAUSTED
BACKTRACKING_BUDGET_EXHAUSTED
UNSUPPORTED_MATCH_MODE
```

## 18. Visualisierung des AST-Matchings

Die zentrale Matcher-Ansicht zeigt drei synchronisierte Strukturen:

```text
Regelmuster        aktueller AST        Ergebnisvorschau
```

Für jeden Pattern-Knoten wird angezeigt:

- zugeordneter AST-Knoten;
- erzeugte oder wiederverwendete Bindung;
- Typ- und Sortenprüfung;
- Guard-Ergebnis;
- Erfolg oder erster Fehlschlag;
- alternative Zuordnungen bei Backtracking.

Die gleiche Pattern-Variable wird in allen Ansichten visuell verbunden:

```text
x  ───────────────► AST-Teilbaum a ───────────────► x im Zielmuster
y  ───────────────► AST-Teilbaum b
z  ───────────────► AST-Teilbaum c
```

### 18.1 Erfolgreicher Match

```text
Regel:
    Add(Mul(x, y), Mul(x, z)) <=> Mul(x, Add(y, z))

Position:
    /

Bindungen:
    x = a
    y = b
    z = c

Vorher:
    a*b + a*c

Nachher:
    a*(b+c)
```

Die Oberfläche zeigt zusätzlich einen strukturellen AST-Diff und markiert wiederverwendete, entfernte und neu erzeugte Knoten.

### 18.2 Bindungskonflikt

```text
Fehler: inkonsistente Bindung

Pattern:
    zweite Verwendung von x

bereits gebunden:
    x = a

aktuell beobachtet:
    b

AST-Position:
    /arguments/1/arguments/0
```

### 18.3 Nebenbedingung fehlgeschlagen

```text
Pattern matched structurally.
Guard failed:
    denominator != 0

Available assumptions:
    denominator : Real

Missing evidence:
    nonzero(denominator)
```

## 19. Regelinspektor für einen AST-Knoten

Ein Klick auf einen AST-Knoten öffnet alle betrachteten Regeln und ihre Ergebnisse.

| Regel | Matchergebnis | Strategieentscheidung |
|---|---|---|
| `factor_common` | Match mit `x=a, y=b, z=c` | ausgewählt |
| `expand_product` | Wurzeloperator falsch | nicht anwendbar |
| `remove_zero` | neutrales Element fehlt | nicht anwendbar |
| `commute_add` | Match | weich zurückgestellt |
| `macro_factor_then_normalize` | Match | Budget blockiert |

Die Oberfläche unterscheidet mindestens:

```text
NOT_APPLICABLE
MATCH_FAILED
GUARD_FAILED
ASSUMPTION_UNPROVEN
HARD_FILTERED
SOFT_DEPRIORITIZED
DUPLICATE_RESULT
BUDGET_BLOCKED
QUEUED
SELECTED
APPLIED
VERIFICATION_FAILED
```

Damit bleibt sichtbar, ob eine Regel mathematisch nicht passte oder nur strategisch nicht gewählt wurde.

## 20. Schwierige Matcherfälle

### 20.1 Assoziatives Matching

Bei assoziativen Operatoren zeigt die Visualisierung:

```text
ursprünglicher AST
        ↓ flatten
kanonische Argumentliste
        ↓ partition
Pattern-Gruppierung und Bindungen
```

Jede untersuchte Partition erhält eine stabile Alternative-ID.

### 20.2 Kommutatives Matching

Die Oberfläche zeigt die kanonische Reihenfolge und betrachtete Permutationen:

```text
attempt 1: x=a, y=b  → guard failed
attempt 2: x=b, y=a  → match succeeded
attempt 3: pruned by canonical symmetry rule
```

### 20.3 Backtracking

```text
Match attempt
├─ bind x = subtree 1
│  ├─ bind y = subtree 2 → conflict
│  └─ bind y = subtree 3 → success
└─ bind x = subtree 2
   └─ skipped by canonical order
```

### 20.4 E-Graph-Matching

Für E-Graphs wird nicht ein AST-Pfad, sondern die Kette aus Pattern, E-Class, E-Node und Substitution angezeigt:

```text
Pattern Node
    ↓
E-Class
    ↓
matching E-Nodes
    ↓
Substitution
    ↓
created E-Node / Union
```

## 21. Visualisierung von Strategieentscheidungen

Neben dem Matcher benötigt die Workbench eine Score- und Scheduler-Ansicht.

| Aktion | Struktur | Zielnähe | Modell | Exploration | Kosten | Gesamt |
|---|---:|---:|---:|---:|---:|---:|
| `factor_common @ /` | 0.80 | 0.90 | 0.73 | 0.02 | -0.10 | 2.35 |
| `commute_add @ /` | 0.10 | 0.00 | 0.05 | 0.30 | -0.02 | 0.43 |
| `expand_product @ /1` | -0.40 | -0.20 | 0.12 | 0.40 | -0.30 | -0.38 |

Bei einem Modell werden sichtbar gebunden:

```text
model_id
model_artifact_hash
feature_schema_hash
raw_output
normalization
calibrated_value
combined_priority
```

Ein einzelner undurchsichtiger `score=0.873` genügt nicht.

## 22. Queue- und Scheduler-Ansicht

Die Frontier wird als fachliche Warteschlange dargestellt:

```text
Frontier
├─ action-91  factor_common       priority 1.748
├─ action-77  normalize_product   priority 1.533
├─ action-82  commute_add         priority 0.421
└─ action-94  rare_macro          fair-reserve
```

Bei kombinierten Schedulern wird der Auswahlgrund angezeigt:

```text
selection_reason = BEST_FIRST_HEAD
selection_reason = FAIR_RESERVE_SLOT
selection_reason = MCTS_EXPLORATION
selection_reason = PROGRESSIVE_WIDEN_OPENING
```

## 23. Suchgraph, Trace und Quelltext verknüpfen

Die Workbench stellt bidirektionale Navigation bereit:

- DSL-Zeile → alle zugehörigen Strategy-IR-Knoten und Trace-Ereignisse;
- AST-Knoten → alle Matchversuche und Aktionen an dieser Position;
- Regeldefinition → alle betrachteten Positionen und Laufzeitanwendungen;
- Suchgraphkante → konkrete Regelinstanz, Bindungen, Score und Source Span;
- Queue-Eintrag → Folgeausdruck als Vorschau;
- Matchfehler → erster abweichender Pattern- und AST-Knoten;
- Verifikationsresultat → ursprüngliche Aktion und erzeugte Obligation.

## 24. Replay und Rückwärtsnavigation

Die Runtime speichert periodische Snapshots und einen deterministischen Event-Strom:

```text
Snapshot state-0
Events 1..1000
Snapshot state-1000
Events 1001..2000
```

Dadurch werden Funktionen möglich wie:

```text
Back to previous selected action
Back to previous successful match
Back to state creation
Replay from breakpoint
```

Für Modelle müssen Modellartefakt, Seed, Feature-Schema und konkrete Antwort gebunden werden. Externe Solver benötigen denselben bestehenden Reproduktionsvertrag wie andere Proof- und Validation-Läufe.

## 25. Editor- und Sprachunterstützung

### 25.1 Syntax-Highlighting

Semantische Klassen umfassen:

- Sprachschlüsselwörter;
- Regeln und Taktiken;
- Pattern-Variablen;
- AST-Konstruktoren;
- Typen und Domains;
- Positionselektoren;
- Verifier und Modelle;
- numerische Gewichtungen;
- gefährliche Operatoren wie `prune`;
- unbewiesene oder heuristische Relationen.

### 25.2 Language Server Protocol

Ein LSP-Server liefert:

- Parser- und Typdiagnosen;
- Autovervollständigung;
- Hover-Dokumentation;
- Sprung zur Definition;
- Referenzsuche;
- symbolisches Umbenennen;
- Formatierung;
- semantische Tokens;
- Inline-Hinweise zu Typen und Effekten;
- unbenutzte Regeln;
- unerreichbare Strategiearme;
- unbeschränkte Generatoren ohne Budget;
- Fairnessverlust;
- nicht verifizierte Transformationspfade.

Beispiel:

```text
warning RSL204:
`prune(outside_top_k(100))` removes fairness.

Declare `allows incompleteness` or replace the hard prune with
soft prioritization.
```

### 25.3 Debug Adapter Protocol

Langfristig stellt ein Debug Adapter dieselben fachlichen Stack Frames, Breakpoints und Watches für externe Editoren bereit. Die Web-Workbench und ein Editor-Plugin verwenden dieselbe Debug-Runtime.

## 26. Web-Workbench-Zielbild

Eine mögliche Anordnung ist:

```text
┌─────────────────────┬────────────────────────────┐
│ DSL-Editor          │ AST / Pattern Visualizer   │
│                     │                            │
│ Breakpoints         │ Regel / Ziel / Vorschau    │
├─────────────────────┼────────────────────────────┤
│ Search Graph        │ Trace / Bindings / Queue   │
│                     │                            │
└─────────────────────┴────────────────────────────┘
```

Die erste Umsetzung kann auf dem bestehenden AST-Regelradar aufbauen, soll dessen aktuelle Funktionen jedoch nicht mit der neuen DSL-Semantik verwechseln. Der neue Inspektor nutzt denselben versionierten Trace- und Matcher-Erklärungsvertrag wie externe Werkzeuge.

## 27. Debug-Trace und wissenschaftliche Evidence

Debugging und Evidence sind getrennte Projektionen desselben kanonischen Laufs.

### Debug-Trace

- kann sehr groß sein;
- enthält verworfene Alternativen;
- dient Entwicklung und Erklärung;
- kann gefiltert oder lokal gespeichert werden;
- ist nicht automatisch veröffentlichungsfähig.

### Wissenschaftliche Evidence

- enthält nur claim-relevante Fakten;
- besitzt eigene Schemata und Gates;
- bindet autoritative Eingaben und Ergebnisse;
- darf keine Entscheidung aus einem nicht gebundenen Debug-Trace ableiten;
- bleibt unabhängig verifizierbar.

Eine spätere Evidence-Projektion kann ausgewählte Trace-Referenzen enthalten, aber nicht umgekehrt einen Debug-Trace stillschweigend zum Autoritätsartefakt erklären.

## 28. Plugins und Erweiterungspunkte

Nicht jeder neue Forschungsansatz lässt sich sofort aus vorhandenen Kombinatoren bilden. Deshalb bleiben kontrollierte Plugins möglich:

```text
plugin "de.regelsuche.experimental.NewScheduler"
    provides scheduler new_scheduler_v1
```

Ein Plugin muss deklarieren:

- API- und Core-Version;
- bereitgestellte Primitive;
- Typ- und Effektverträge;
- deterministische Identität;
- externe Abhängigkeiten;
- Trace-Ereignisse;
- Debugger- und Source-Map-Unterstützung;
- Reproduktionsgrenzen.

Ein Plugin darf nicht die gesamte Strategie unsichtbar in Java verstecken. Die konzeptionelle Komposition soll im Strategieprogramm sichtbar bleiben.

## 29. Begrenzte Sprache statt allgemeiner Programmiersprache

Der Sprachkern soll nur kontrollierte Kombinatoren besitzen:

```text
sequence
choice
interleave
repeat(max = N)
until(condition, budget)
map
require
prioritize
progressive_widen
search
verify
observe
```

Nicht zum Kern gehören:

- unbeschränkte Rekursion;
- beliebige Threads;
- frei zugängliches Dateisystem;
- Netzwerkzugriff;
- dynamischer Prozessstart;
- versteckte globale Zustände.

Dadurch bleiben Terminierung, Budgets, Fairness, Reproduzierbarkeit und Effekte zumindest teilweise statisch analysierbar.

## 30. Beispielprogramme

### 30.1 Faire Enumeration

```text
strategy exhaustive =
    applicable(all_rules, every_position)
    |> fair
    |> search
```

### 30.2 Best-First

```text
strategy best_first_search =
    applicable(all_rules, every_position)
    |> prioritize(goal_distance + ast_cost)
    |> best_first
```

### 30.3 Gelernte Taktikauswahl

```text
strategy learned_tactics =
    applicable(tactics, proof_state)
    |> prioritize(model("tactic-policy"))
    |> best_first
```

### 30.4 MCTS

```text
strategy tactic_mcts =
    mcts(
        actions = applicable(tactics, proof_state),
        prior = model("policy"),
        value = model("value"),
        simulations = 10_000
    )
```

### 30.5 Equality Saturation

```text
strategy equality_saturation =
    egraph(input)
    |> saturate(
        rules = equivalence_rules,
        schedule = fair
    )
    |> extract(minimize = ast_size + evaluation_cost)
```

### 30.6 Gelernte Equality Saturation

```text
strategy guided_saturation =
    egraph(input)
    |> saturate(
        rules = equivalence_rules,
        schedule = prioritize(model("egraph-rule-policy"))
    )
    |> extract(minimize = target_cost)
```

### 30.7 Checkpoint-Suche

```text
strategy checkpoint_guided {
    checkpoints = model("strategy-planner")
        |> propose_checkpoints(state, goal)

    for checkpoint in checkpoints {
        saturate_until(
            matches(checkpoint),
            rules = relevant_rules(checkpoint)
        )
    }
}
```

### 30.8 Makrobildung

```text
study macro_learning {
    repeat rounds = 10 {
        traces = solve(tasks, strategy = guided_rewrite_search)

        new_macros = traces
            |> successful
            |> frequent_subpaths
            |> anti_unify
            |> prove
            |> require(search_gain)

        promoted_macros += new_macros
    }
}
```

## 31. Modulstruktur

Eine mögliche spätere Modulstruktur lautet:

```text
regelsuche-strategy-ir/
    ir/
    types/
    effects/
    normalization/
    codec/

regelsuche-strategy-runtime/
    interpreter/
    scheduler/
    trace/
    snapshots/
    debugger/

regelsuche-language/
    parser/
    ast/
    compiler/
    formatter/
    lsp/

regelsuche-debug-protocol/
    events/
    source-maps/
    matcher-explanations/
    debug-adapter/

stdlib/
    ast.rsl
    rewriting.rsl
    search.rsl
    egraph.rsl
    proof.rsl
    learning.rsl
    evidence.rsl

examples/
    exhaustive-search.rsl
    best-first.rsl
    learned-tactics.rsl
    equality-saturation.rsl
    guided-saturation.rsl
    checkpoint-search.rsl
    evolutionary-macro-learning.rsl
```

## 32. Versionierte Verträge

Voraussichtlich benötigte Schemata:

```text
regelsuche.strategy-program/v1
regelsuche.strategy-ir/v1
regelsuche.strategy-source-map/v1
regelsuche.strategy-execution-plan/v1
regelsuche.strategy-trace-event/v1
regelsuche.strategy-trace-manifest/v1
regelsuche.match-explanation/v1
regelsuche.debug-snapshot/v1
regelsuche.debug-session/v1
regelsuche.strategy-model-binding/v1
```

Die genaue Aufteilung wird erst nach einem IR-Prototyp festgelegt. Es sollen keine Schemas vorzeitig eingefroren werden, bevor die minimalen Referenzstrategien damit ausdrückbar sind.

## 33. Umsetzungsphasen

### Phase 0 — aktuelles System stabil releasen

Vor Beginn der Architekturarbeit:

- `main` ist grün;
- der bestehende Funktionsstand ist versioniert veröffentlicht;
- Release-Artefakte und reproduzierbare QA sind abgeschlossen;
- die neue Architektur erweitert keine offenen Release-PRs;
- ein eigener Entwicklungszweig beginnt auf dem Release-Tag.

### Phase 1 — Beobachtungsfundament ohne DSL

Ziel: Die bestehende Laufzeit wird erklärbar, bevor eine neue Sprache eingeführt wird.

- stabile IDs für Zustände, AST-Knoten, Regeln, Aktionen und Matchversuche;
- strukturierte `MatchResult`- und `MatchFailure`-Typen;
- minimale Trace-Ereignisse;
- Invariante `trace off == trace full`;
- AST-Regelinspektor für Erfolg und ersten Fehlschlag;
- kanonische logische Ereignisordnung.

### Phase 2 — kleine Strategy-IR und Java-DSL

- IR-Knoten für `Generate`, `Require`, `Prioritize`, `Schedule`, `Verify`, `Stop`;
- Java-Builder als erste Frontend-Notation;
- IR-Codec, Normalisierung und Hash-Identität;
- Source Maps von Java-Builder-Aufrufen beziehungsweise synthetischen Quellen;
- Referenzstrategien: faire Enumeration und Best-First.

### Phase 3 — Trace-Workbench

- synchronisierte AST-, Regel-, Match-, Queue- und Suchgraph-Ansichten;
- Source-Map-Navigation;
- Trace-Filter und Snapshots;
- Breakpoints auf Regel, Matchfehler und Aktion;
- `Next Match`, `Next Action`, `Next State`.

### Phase 4 — externe DSL

- kleine Grammatik;
- Parser und Formatter;
- Kompilierung in dieselbe Strategy-IR;
- Syntax-Highlighting und semantische Tokens;
- erste LSP-Diagnosen;
- identische Ausführung von Java-DSL und Textprogramm.

### Phase 5 — vollständiger fachlicher Debugger

- konditionale Breakpoints;
- fachliche Stack Frames;
- Watches;
- Rückwärtsnavigation per Snapshot und Replay;
- Debug Adapter Protocol;
- deterministische Modellantwortbindung.

### Phase 6 — weitere Referenzstrategien

- Equality Saturation;
- gelernte Best-First-Priorisierung;
- faire Reserveexploration;
- Checkpoint-Planung;
- Makrobildung;
- MCTS oder evolutionäre Strategievariation.

### Phase 7 — Studien- und Lernsprache

- Split- und Leakage-Verträge;
- Trainings- und Evaluationsläufe;
- Makropromotion;
- Ablationen;
- automatisch erzeugte Programmdiffs und Experimentmanifeste.

## 34. Akzeptanzkriterien für den ersten brauchbaren Stand

Die erste Sprach- und Debugger-Version gilt erst als brauchbar, wenn:

1. ein Nutzer einen AST-Knoten auswählen und alle dort betrachteten Regeln sehen kann;
2. für jede nicht passende Regel der erste konkrete Matchfehler sichtbar ist;
3. für eine passende Regel Bindungen, Guards und Folge-AST visualisiert werden;
4. ein Klick auf eine Suchgraphkante zur verantwortlichen Strategie- und Regelstelle führt;
5. ein Breakpoint auf einer Regel vor ihrer Anwendung pausiert;
6. `Next Match`, `Next Action` und `Next State` funktionieren;
7. Score-Komponenten und Scheduler-Auswahlgrund sichtbar sind;
8. ein Lauf aus Snapshots und gebundenem Trace reproduziert werden kann;
9. `trace off` und `trace full` denselben kanonischen Ausgang erzeugen;
10. Parser-, Typ- und Effektfehler im Editor erscheinen;
11. harte Filter, weiche Priorisierung und unvollständiges Pruning klar getrennt sind;
12. faire Enumeration und Best-First als kleine Programme dieselbe Runtime verwenden;
13. die Text-DSL keine unkontrollierten Nebenwirkungen ausführen kann;
14. Debug-Trace und wissenschaftliche Evidence getrennte Artefakte bleiben.

## 35. Qualitäts- und Teststrategie

### 35.1 Golden Tests

Kleine Strategien besitzen eingefrorene:

- normalisierte IR;
- Source Maps;
- Trace-Präfixe;
- Matcher-Erklärungen;
- Debugger-Stack-Frames.

### 35.2 Differentialtests

Java-DSL und Text-DSL müssen für dieselbe Strategie identische IR und kanonische Resultate erzeugen.

### 35.3 Trace-Invarianztests

Jeder Referenzlauf wird mit mehreren Trace-Stufen ausgeführt. Kanonische Ergebnisse und Evidence müssen identisch bleiben.

### 35.4 Matcher-Mutationstests

Verifier sollen manipulierte Bindungen, ausgelassene Guards, falsche Fehlerpositionen und unvollständige Backtracking-Traces erkennen.

### 35.5 Parallelitätscharakterisierung

Läufe mit unterschiedlichen Parallelitätsgraden müssen dieselbe logische Ereignisordnung oder eine explizit äquivalente kanonische Projektion liefern.

### 35.6 UI-End-to-End-Tests

Mindestens folgende Interaktionen werden automatisiert:

- DSL-Zeile zu Trace;
- AST-Knoten zu Regelinspektor;
- Matchfehler zu Pattern-Knoten;
- Suchkante zu Aktion;
- Breakpoint und Einzelschritt;
- Replay vorwärts und rückwärts.

## 36. Risiken und Gegenmaßnahmen

### 36.1 Die DSL wird nur eine Fassade

**Risiko:** Die eigentliche Strategie bleibt in Java-Plugins verborgen.

**Gegenmaßnahme:** Plugins implementieren Mechanismen; die Komposition und alle relevanten Parameter bleiben im Strategieprogramm sichtbar. Der IR-Report weist Plugin-Effekte aus.

### 36.2 Trace-Daten explodieren

**Risiko:** Matcher- und Queue-Ereignisse erzeugen unbeherrschbare Datenmengen.

**Gegenmaßnahme:** Trace-Stufen, Filter, Snapshots, begrenzte Ringpuffer, Streaming-Speicher und getrennte kanonische Evidence.

### 36.3 Debugging verändert Timing und Auswahl

**Risiko:** Pausen oder Listener verändern das Suchergebnis.

**Gegenmaßnahme:** logische Budgets, immutable Events, deterministische IDs, Replay-Bindung und verpflichtende Trace-Invarianztests.

### 36.4 Zu frühes Einfrieren der Syntax

**Risiko:** Eine attraktive Syntax stabilisiert falsche Abstraktionen.

**Gegenmaßnahme:** zuerst Strategy-IR und Java-DSL, danach externe Syntax.

### 36.5 Zu allgemeines Typsystem

**Risiko:** Die Sprache versucht alle mathematischen Domains im ersten Schritt abzubilden.

**Gegenmaßnahme:** Start mit AST-Rewrite-Domain und expliziten Erweiterungspunkten. E-Graph und Proof-State folgen als getrennte Positions- und Workspace-Typen.

### 36.6 Erklärungen werden nachträglich rekonstruiert

**Risiko:** UI-Erklärungen raten aus Endzuständen, warum eine Entscheidung gefallen ist.

**Gegenmaßnahme:** Entscheidungen und Matcher-Schritte erzeugen ihre strukturierten Erklärungen während der Ausführung.

## 37. Architekturentscheidungen vor Implementierungsbeginn

Nach dem Release sind zunächst folgende Entscheidungen als ADRs festzuhalten:

1. minimale Strategy-IR-Knoten und ihre Normalform;
2. Identitätsmodell für AST-Knoten über Transformationen hinweg;
3. logische Ereignisordnung bei Parallelität;
4. Snapshot- und Replay-Modell;
5. Trennung von Trace- und Evidence-Schemata;
6. erster unterstützter Pattern-Matching-Modus;
7. Java-DSL-Form vor Einführung der Textsyntax;
8. Plugin-Effektvertrag;
9. LSP- und DAP-Zielumfang;
10. Speicher- und Datenbegrenzungen für `trace full`.

## 38. Empfohlener erster Forschungsprototyp

Der erste Prototyp soll bewusst klein bleiben:

- Domain: algebraische AST-Rewrites;
- Regeln: vorhandene Grundregeln und ein kleiner Makrobestand;
- Strategien: faire Enumeration und Best-First;
- Positionen: `root`, `top_down`, `bottom_up`, `every_position`;
- Trace: `summary`, `decisions`, `matcher`;
- Debugger: Breakpoint auf Regel, `Next Match`, `Next Action`, `Next State`;
- Visualisierung: Pattern, AST, Bindungen, erster Fehlschlag, Folge-AST;
- keine Modelle, keine MCTS, keine Lernschleife in der ersten Stufe.

Dieser Prototyp beantwortet die wichtigste Architekturfrage:

> Lassen sich vorhandene Suchverfahren über eine kleine gemeinsame IR ausdrücken und vollständig bis zu Regel, Match, Position und Quellstelle erklären?

Erst wenn diese Frage positiv beantwortet ist, werden Textsyntax, LSP, DAP und lernende Komponenten ausgebaut.

## 39. Erfolgskriterium des Gesamtvorhabens

Der langfristige Erfolg ist nicht primär die Existenz einer neuen Syntax. Er besteht darin, dass eine neue Suchidee als kleines, lesbares und debuggbares Programm formuliert werden kann.

Ein typischer experimenteller Unterschied soll so sichtbar werden:

```diff
 strategy guided {
-    priority = structural_score
-    scheduler = best_first
+    priority = structural_score + learned_policy
+    scheduler = interleave(best_first, fair, 19:1)
 }
```

Gleichzeitig muss jede Entscheidung bis zur konkreten Regelinstanz, AST-Position, Bindung, Score-Komponente, Scheduler-Auswahl und Verifikation nachvollziehbar bleiben.

Die angestrebte Architektur lautet daher:

```text
typisierte mathematische Regeln
    + effektiv enumerierbare Aktionsquellen
    + Positionsselektoren
    + Strategiekombinatoren
    + kontrollierte Lernkomponenten
    + formale Verifikation
    + durchgängige Source Maps
    + kanonischer Trace
    + fachlicher Debugger
```

Regelsuche wird damit nicht nur zu einer virtuellen Maschine für mathematische Suchstrategien, sondern zu einer **vollständig inspizierbaren virtuellen Maschine**, in der neue Ansätze schnell kombiniert, verglichen, erweitert und bis auf den einzelnen AST-Match erklärt werden können.
