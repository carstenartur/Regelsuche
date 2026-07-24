# Complete target-free production campaign

Issue [#348](https://github.com/carstenartur/Regelsuche/issues/348) is implemented as one pinned, unattended production campaign that starts from a versioned Research Brief and ends with a deterministic feedback decision and next plan.

## Complete chain

```text
AutonomousResearchBriefV2
→ two target-free seed-generator families
→ 12 immutable UNTARGETED observation branches
→ OpenTargetConjectureMiner
→ one cross-family candidate plus one explicit zero-output rejection batch
→ fresh positive and negative holdouts
→ deterministic counterexample search
→ project-internal novelty
→ target-free proof obligation
→ symbolic equivalence result
→ conservative lifecycle handoff
→ factual feedback/reallocation
→ deterministic empty next plan
→ complete campaign manifest
```

The retained candidate is:

```text
(A + 2)*x + A*x → (2*A + 2)*x
```

It is supported by all twelve observations from both configured generator families. A predeclared pair with identical parameters and only an alpha rename is retained as `alpha-distinct-support<2` rejection evidence and creates no candidate branch.

## Feedback semantics

The feedback decision consumes only hashes and receipts already produced by earlier stages.

The next plan contains zero decisions because:

- the candidate branch completed every configured Autopilot stage;
- the alpha-equivalent cluster was rejected;
- no eligible incomplete Autopilot branch remains.

Unused budget remains visible as `remaining`. It is not silently converted into executed or skipped work. An empty next plan therefore means `CAMPAIGN_COMPLETE`, not missing execution.

Promotion, Public Evidence, external novelty and formal proof remain `NOT_EVALUATED` and outside Autopilot completion.

## Complete resource ledger

`regelsuche.autonomous-campaign-resource-ledger/v2` combines the factual receipts for every configured non-time resource:

- generated states, explored states and observations;
- mining batches and candidates;
- validation checks;
- counterexample attempts;
- project-novelty comparisons;
- proof attempts;
- lifecycle handoffs.

Every entry satisfies:

```text
configured = executed + skipped + remaining
```

Each entry references the exact source receipt hash.

## Gradle reproduction

```bash
./gradlew :regelsuche-autopilot:runProductionCampaign
```

The complete evidence set is written to:

```text
regelsuche-autopilot/build/reports/autopilot-production-campaign/
```

A custom output directory can be selected with:

```bash
./gradlew :regelsuche-autopilot:runProductionCampaign \
  -PautopilotOutputDir=/absolute/output/path
```

## Docker reproduction

Build the dedicated Java 21 runtime image:

```bash
docker build -f Dockerfile.autopilot -t regelsuche-autopilot-campaign .
```

Run it with an output directory mounted at `/output`:

```bash
mkdir -p build/autopilot-docker-output
chmod 0777 build/autopilot-docker-output
docker run --rm \
  -v "$PWD/build/autopilot-docker-output:/output" \
  regelsuche-autopilot-campaign
```

The runtime image contains the Gradle `installDist` output, not the repository source tree or a Gradle installation.

## CI reproduction contract

The `Autopilot Evidence` workflow:

1. executes the characterization tests with sequential and parallel generation;
2. runs the complete Gradle campaign command;
3. requires all 36 canonical evidence files;
4. builds and runs `Dockerfile.autopilot`;
5. requires the Docker manifest;
6. compares the complete Gradle and Docker output directories with `diff -ru`;
7. uploads both evidence sets and diagnostics.

A difference in any retained file fails the workflow.

## Final artifacts

The last campaign layer adds:

- `next-plan-v2.json`;
- `campaign-round-v2.json`;
- `feedback-reallocation.json`;
- `campaign-resource-ledger.json`;
- `production-campaign-manifest.json`.

`production-campaign-manifest.json` references more than thirty upstream semantic artifacts by type and canonical hash, including generation, observations, mining, rejection, lineage, validation, counterexample, novelty, proof, lifecycle, feedback and budget evidence.
