# Autonomous Discovery Result Card

The autonomous-discovery walkthrough turns the supported qualified production
run into a compact JSON and Markdown result card plus generated SVG views. The
presentation is derived from retained canonical evidence and does not contain a
hand-maintained success value.

> **NO EXTERNAL NOVELTY CLAIM**
>
> The walkthrough demonstrates target-free candidate formation, project-local
> novelty checks, symbolic proof evidence, independent qualification and paired
> held-out reuse. External mathematical novelty, promotion and Public Evidence
> remain separate `NOT_EVALUATED` states.

## One local command

```bash
./gradlew :regelsuche-release:runAutonomousDiscoveryWalkthrough
```

The default output is
`regelsuche-release/build/reports/autonomous-discovery-walkthrough/`. A custom
output directory can be selected without changing the evidence contract:

```bash
./gradlew :regelsuche-release:runAutonomousDiscoveryWalkthrough \
  -PwalkthroughOutput="$PWD/build/autonomous-discovery-walkthrough"
```

The task executes the supported qualified release run, verifies the required
claim boundaries in memory, writes the complete raw evidence bundle, derives
the card and exits non-zero if the autonomous-campaign claim is not qualified.
A failure leaves `walkthrough-failure.txt` and any evidence already written in
the output directory.

## Output contract

- `result-card.json` — canonical
  `regelsuche.autonomous-discovery-result-card/v1` with a self-verifiable
  content hash;
- `result-card.md` — human-readable result card generated from the same object;
- `walkthrough.md` — the result card plus a guided artifact inspection path;
- `figures/sequence.svg` — the complete evidence sequence;
- `figures/candidate-lineage.svg` — exact supporting observations and retained
  lineage root;
- `figures/paired-utility.svg` — all paired baseline/candidate explored-state
  measurements;
- `figures/representative-search.svg` — one evidence-selected held-out case;
- `evidence/` — the authoritative campaign, lifecycle, qualification and
  release-readiness artifacts.

Every result-card artifact reference carries both the semantic evidence hash
and the SHA-256 of the exact retained file bytes. Displayed counts and statuses
also name their authoritative artifact role.

## Independent verification

```bash
python3 -m pip install jsonschema==4.25.1
python3 scripts/verify-autonomous-discovery-walkthrough.py \
  --root regelsuche-release/build/reports/autonomous-discovery-walkthrough \
  --schema docs/schemas/regelsuche-autonomous-discovery-result-card-v1.schema.json
```

The verifier rejects duplicate JSON fields, schema violations, path traversal,
missing artifacts, file-hash drift, broken semantic links, inconsistent
candidate identities, copied rather than recomputed utility summaries and any
stronger-than-authorized novelty, promotion or Public Evidence status.

## Container equivalent

Build the pinned walkthrough target with the repository revision that is to be
bound into the card:

```bash
REVISION="$(git rev-parse HEAD)"
docker build \
  --target walkthrough \
  --build-arg REGELSUCHE_REPOSITORY_REVISION="$REVISION" \
  -t regelsuche-autonomous-walkthrough .
mkdir -p build/autonomous-discovery-container
chmod 0777 build/autonomous-discovery-container
docker run --rm \
  -v "$PWD/build/autonomous-discovery-container:/out" \
  regelsuche-autonomous-walkthrough
```

The dedicated CI workflow runs the local command twice, compares both complete
outputs byte-for-byte, verifies the schema and independently recomputed values,
then compares the pinned container output byte-for-byte with the local output.
It also regenerates the committed gallery SVGs and fails on drift.

## Evidence sequence

```text
Research Brief
  -> Seed families
  -> Untargeted observations
  -> Aggregate candidate formation
  -> Validation and counterexample search
  -> Project novelty and proof evidence
  -> Qualification
  -> Paired held-out reuse
```

The result card is a presentation of qualified project evidence. It is not a
substitute for an external novelty review, candidate promotion or Public
Evidence review.
