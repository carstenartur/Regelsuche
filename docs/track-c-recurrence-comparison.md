# Open-target recurrence formation on the public #383 sequences

The production exact fitter and a bounded integer grammar both emitted the doubling rule in all four campaigns. The seeded grammar emitted a rule that fit the same geometric sequence in three campaigns. None of the frozen rule sets fit any of the four VALIDATION or TEST prefixes. The common candidate-validation allowance was 60 proposals per campaign. The production fitter emitted one proposal per campaign and did substantially less external validation work.

## Experiment

The [plan](../research/benchmarks/candidate-independent/track-c-recurrence/comparison-plan.json) was committed in [95c1eefa](https://github.com/carstenartur/Regelsuche/commit/95c1eefada1d2e989182e4bc92369e11c4840fc4) before executing these three arms. The six original sequence cases and historical production outcomes were already public. This comparison uses their unchanged corpus, formation-profile and freeze roots.

Each former receives only the two TRAIN prefixes and their maximum orders. The production arm calls the existing exact linear-recurrence fitter. The enumerator traverses admissible integer coefficient tuples in increasing order and then lexicographic order. The randomized control samples uniformly from that same grammar, with replacement, using the four pre-existing campaign seeds. All three use the same coefficient limits, exact support checks and held-out scoring. A candidate that fits its generating TRAIN prefix can enter the frozen set. The evaluator applies that fixed rule to all six cases without refitting it. It records each prefix equation and each of the three teacher-forced continuation predictions separately. A candidate must pass both checks to confirm a case. The historical #383 adapter evaluated the available model form by fitting a fresh model for each case, a different question.

## Measured outcomes

| Four campaigns combined | Production exact fit | Enumeration | Seeded random |
| --- | ---: | ---: | ---: |
| Candidate proposals | 4 | 240 | 240 |
| Rejected by exact prefix checks | 0 | 236 | 234 |
| Outside the coefficient grammar | 0 | 0 | 0 |
| Duplicate attempts | 0 | 0 | 60 |
| Supported attempts | 4 | 4 | 6 |
| Unique admitted models, summed across campaigns | 4 | 4 | 5 |
| Campaigns with zero output | 0 | 0 | 1 |
| Formation equation checks | 16 | 768 | 756 |
| Evaluation equations and predictions | 184 | 184 | 206 |
| Confirmed case slots (of 24) | 4 | 4 | 3 |
| Case slots with prefix mismatch | 20 | 20 | 15 |
| Case slots with matching prefix but refuted continuation | 0 | 0 | 0 |
| Case slots with no candidate | 0 | 0 | 6 |
| Confirmed VALIDATION or TEST slots (of 16) | 0 | 0 | 0 |

All confirmations concern the same TRAIN case. In addition to the doubling rule, the randomized control formed coefficients `[1, 2]` and `[3, -2]`: both reproduce that finite geometric sequence. The raw evidence retains duplicate draws, all prefix rejections, the empty campaign, all failed transfers and every per-candidate check. Each rejected or supported count includes duplicate draws of that disposition.

The common allowance measures candidate validations. Exact equation solving, generating an ordered coefficient grammar and randomized sampling have different internal costs. Total operation counts and runtime were not matched. The experiment supports a bounded comparison of fixed-rule formation and reuse in this recurrence grammar. It does not cover finite differences, rational rewrites, reusable macros, the other #235 tracks, proof, project novelty or external mathematical novelty.

## Reproduction

The regular release-module test regenerates the complete result in a temporary directory, compares it byte for byte with the retained artifact and rejects a modified frozen corpus. Run it from the repository root:

```bash
mvn -B -pl regelsuche-release -am -Dtest=TrackCRecurrenceComparisonTest test
python3 scripts/verify-track-c-recurrence.py
```

The independent Python verifier recomputes the campaign seeds, solves the production linear systems over fractions, enumerates the grammar, reproduces the Java random stream, replays all 484 proposals and checks all 72 case rows. To inspect individual proposals and predictions, open the [raw comparison](../research/benchmarks/candidate-independent/track-c-recurrence/comparison-result.json).
