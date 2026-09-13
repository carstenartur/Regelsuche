# Selected combined-program internal authorization

The selected genome, `RewriteProgram` and search configuration can now reach an explicit, bounded
internal executor. The additive consumers live in the existing `LearnedPatternRuleAuthorizationService`
and `LearnedRewriteProgramAuthorizationService`; they reuse the actual native gate algorithms and
`compileAuthorized`. They do not activate a default runtime profile or register an application source.

## Evidence and execution boundary

Start with the independently retained combined final plan, split, expected repository revision and
authoritative private final store. `LearnedSelectedProgramAuthorizationBundle` binds their actual
final evaluation and native qualification assessment hashes, complete selected configuration, and
an explicit validity interval. This versioned manifest has no caller-supplied approval status.

The pattern service's `authorizeSelected` reads the durable final record, verifies the imported
assessment against it and the expected revision, and requires its actual native gates to pass. It
then repeats the existing split check, fixed counterexample verification and exact assumption-free
polynomial promotion for the requested gene. The returned `SelectedLeafAuthorization` has a private
constructor and exposes evidence and receipt data, not its executable rule. Every genome gene needs
one matching authority, including genes not referenced by the program: the flat genome sources also
execute in the selected native profile.

The program service compiles the exact selected topology with those proved rules. Its versioned
`LearnedSelectedProgramReplayEvidence` retains the full configuration, temporal bundle and leaf
authority hashes. On explicit ordinary inputs it records both the real interpreter's ordered
candidates, primitive lineage, assumptions and work, and the native selected search's path, audit,
work and terminal outcome. These are two separate computations: `totalReplayWorkUnits()` adds both
only when both observations are complete. Unknown or incomplete work stays unknown. The interpreter
uses the selected primitive allowance and zero exact-theory allowance; search uses the same effective
selected budget and reserved audit allowance as the combined study evaluator.

`authorizeSelected` independently executes that replay and requires equality with the supplied
artifact, complete outcomes, confirmed reached paths and declared candidate assumptions. Rehashing
a changed lineage, assumption, work observation or broader configuration cannot replace that check.
Complete unsuccessful searches remain explicit negative outcomes. Incomplete replay cannot create
an authority, and expiry is checked again after replay before issuance.

The returned `SelectedAuthorization` has a private constructor and no deserializer, rule accessor or
engine accessor. Its binding includes the actual program authorization instant. Each `execute` call
checks the expected revision, current clock, actual issuance time and all leaf expiry bounds, then
runs the native search and mathematical audit under the frozen selected configuration. The returned
measurement retains any unsuccessful, incomplete or unconfirmed outcome on the new input; a caller
must inspect that result. The capability does not claim every future search will succeed.

## Explicit library use

Given an already authorized study execution and its retained inputs:

```java
var assessment = new EvolutionRewriteProgramQualificationService().assess(
    expectedFinalPlan, authoritativeFinalStore, expectedRevision);
var bundle = LearnedSelectedProgramAuthorizationBundle.create(
    assessment, splitManifest, issuedAt, expiresAt);
var leaves = new ArrayList<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization>();
var patterns = new LearnedPatternRuleAuthorizationService();
for (var gene : expectedFinalPlan.selectedConfiguration().candidate().genome().rewrites()) {
    leaves.add(patterns.authorizeSelected(expectedFinalPlan, authoritativeFinalStore,
        assessment.toCanonicalJson(), splitManifest, bundle, gene.geneId(), expectedRevision, clock.instant()));
}
var programs = new LearnedRewriteProgramAuthorizationService();
var replay = programs.evaluateSelectedReplay(expectedFinalPlan, leaves, publicReplayInputs,
    expectedRevision, clock.instant());
var internal = programs.authorizeSelected(expectedFinalPlan, leaves, replay, expectedRevision, clock);
var measured = internal.execute(ordinaryInput, expectedRevision);
```

The application supplies its actual current clock and independently expected revision. The example
does not execute or authorize a protected study. Authorization reads the existing final ledger and
repeats bounded native mathematics/public-input replay; it never opens a held-out reveal or requests
a second final attempt. Combined and genome-only protocols retain their shared once-per-study final
reservation. Existing v1 genome/program hashes, receipts and replay algorithms remain unchanged;
the new schemas bind the combined configuration explicitly.

## Claims and remaining requirements

The capability's scope is `INTERNAL_EXECUTION_ONLY`. Its immutable project novelty, external novelty,
public evidence, public promotion and release statuses remain `NOT_EVALUATED`. These claims retain
their own appropriate evidence requirements. Missing external evidence does not become a new blanket
block on safe internal execution, and passing native checks does not grant publication or automatic
promotion. Existing open-target and release consumers keep their actual conjecture/campaign subjects;
this adapter does not relabel those subjects or fabricate evidence for them.

Independent review and full integrated CI remain required. Actual #220 study claims still need the
preregistered roots, private custody, concrete authorization for the real held-out protocol, its one
real execution and resulting qualification evidence. Actual external novelty/publication/release
claims need their corresponding authorities. The temporary `synthetic_` public controls demonstrate
the library path, not study gains, external novelty, independent timestamps or production readiness.
No CLI/HTTP/UI activation or persistent application registration is introduced by this adapter.

Focused controls:

Independent runtime review also checks expiry between actual replay and capability issuance,
rejection of retained assessment JSON after the actual final reservation is missing, and fresh
assumption checks across successive uses of a conditional ordinary rewrite. The latter retains
`MISSING_ASSUMPTION`, `CONFIRMED`, then `MISSING_ASSUMPTION` as declarations change; an earlier
call cannot supply a later call's assumptions. The focused integrated review passed 45 tests in
seven suites, including the existing final-custody and leaf/program authorization controls.

```bash
mvn -o -pl regelsuche-learning -am -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest=LearnedSelectedProgramAuthorizationTest,LearnedSelectedProgramRuntimeReviewTest -Dsurefire.failIfNoSpecifiedTests=false test
```
