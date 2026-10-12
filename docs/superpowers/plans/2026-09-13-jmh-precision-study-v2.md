# JMH precision study v2 implementation plan

The approved successor preserves the failed v1 run 34729477401 and changes only
collection compatibility, typed error retention and explicitly versioned launch
and evidence identities. No further remote measurement is authorized until the
complete implementation receives independent review and the coordinator opens
one new qualifying PR. No retry or synchronize measurement of PR #993 occurs.

1. Add a separately hashed v2 policy and verifier. Compare the complete policy
   against the pinned v1 policy after applying only the declared new schema,
   study/launch identities, jar schema and predecessor reference. Reuse existing
   pure matrix, command, JSON, numerical decision and summary helpers. Reject
   non-object GC metrics before invoking the unchanged numerical verifier.
2. Add v2 runner and replay entrypoints with new manifest/report schemas. Reuse
   the unchanged subprocess supervisor, runner/source provenance and launch
   checks. Require jar identity v2 and four retained policy inputs. Preserve all
   failed/raw cells and expose collection errors in the command log. Refuse old
   manifests or partial corpora without relabeling them.
3. Add cheap local v2 controls to the existing Gradle check contract. Add exactly
   three fixed replica jobs and one bounded replay job to the existing CI
   workflow, gated only by the new branch's first PR-opened event, attempt 1,
   repository/head-repository and fixed job identity. Preserve the old workflow
   prefix, the five existing CI authorities and the maximum of two workflows.
4. Validate real malformed GC inputs, duplicate/missing/relabelled manifests,
   raw/log/receipt tampering, unauthorized launches before any process starts,
   retained errors, zero-control-noise and no-eligible/no-selection outcomes.
   The existing real jar and its independent v2 identity review remain admission
   evidence only. No Java, Gradle or benchmark launch is needed for these Python
   controls; coordinate any focused Maven or Gradle verification separately.
5. Independently review the fixed complete source, exact policy hashes and
   workflow bindings. Export the exact tree for coordinator publication. A new
   measurement starts only after that review and the explicit opened event.

The seven protocols, three orders, 29 benchmarks, JMH 1.36/Java 25 settings,
thresholds, family separation, precision/ratchet logic and semantic claim
boundaries remain unchanged. Budgets remain 1809 nominal iteration seconds and
3600 total seconds per replica, 900 seconds for build/maximum cell, zero retries,
75 minutes per GitHub replica job and 5 minutes for analysis. Adoption retains
the positive-control-noise condition, at-most-half LOW_PRECISION count, no
family increase, lower median/p90 relative error, all-passed candidate ratchets,
at-most-3.5-times median protocol cost, work parity and manual ranking review.
Incomplete evidence, zero baseline LOW_PRECISION and no eligible protocol keep
their distinct existing null outcomes; no protocol is automatically selected.
