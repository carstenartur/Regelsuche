# Representation salience audit

## Purpose

A target-free search can fail in several different places. It may never reach an
interesting representation, or it may reach one and subsequently fail to form,
retain, recognize or rank it. Those outcomes require different architectural
changes and must not share one generic `NOT_FOUND` status.

`RepresentationSalienceAudit/v2` introduces a content-addressed diagnostic
contract for this separation. It is bound to an existing
`RepresentationDiscoveryRunWorkspace`; it does not create a second run identity
or replace the search graph, candidate freeze, recognition dossier or ranking
artifact.

## Stage model

The audit records the same stable correspondence identities at five successive
boundaries:

```text
reached -> formed -> retained -> recognized -> ranked
```

Each boundary is represented by a sorted, unique, content-addressed `StageSet`.
The sets must be nested. A later stage cannot invent a representation that is
absent from the preceding evidence.

The identities are correspondence identities, not display strings. A later
historical qualification may bind several syntactic expressions to one exact,
alpha, semantic, occurrence-local or capability-level correspondence. The
correspondence decision is disclosed only after the target-free search and
candidate freeze have been sealed.

Every case is localized to the first failing stage, including:

- reachable under the independent bounded closure but not reached by the policy;
- reached but not formed as a candidate;
- formed but discarded by the retained archive;
- retained but not recognized as material or capability-bearing;
- recognized but not surfaced within the frozen ranking cutoff;
- ranked but awaiting, failing or receiving blind expert consensus;
- negative and alias controls that are correctly rejected or falsely surfaced.

Complete-closure unreachability, reachability inconclusiveness and unsupported
inputs remain distinct.

## Conditional recall

The summary retains numerator, denominator, whether the rate is defined and the
integer permille value for every transition:

```text
policy reachability recall       = oracle-confirmed reached / oracle-reachable
formation recall | reached       = formed / reached
retention recall | formed        = retained / formed
recognition recall | retained    = recognized / retained
ranking recall | recognized      = ranked / recognized
automated end-to-end recall      = oracle-confirmed ranked / oracle-reachable
false-positive rate              = false-positive controls / controls
```

A zero denominator is represented as `defined=false`; it is not converted to a
perfect score or silently omitted. Counts of inconclusive and unsupported cases
are retained independently of these denominators. A representation that the
search reaches despite an inconclusive or unsupported oracle is retained in
`reachedWithoutOracleConfirmationCount`; it participates in downstream
formation/recognition diagnostics but cannot silently increase an
oracle-conditioned recall numerator.

## Information boundary

The intended execution order is:

1. freeze the source cases, rule inventory, policy and work limits;
2. run target-free search and freeze the complete bounded trace;
3. freeze candidate formation and archive decisions;
4. disclose historical references or post-freeze known-structure catalogs;
5. produce recognition decisions;
6. apply unchanged ranking profiles and cutoff;
7. freeze all automated evidence;
8. obtain genuine blind expert relevance review under issue #389;
9. perform external novelty review separately under issue #391.

Historical names, expected answers and expert labels are never ranking inputs.
For an open-target candidate, `recognized` may be supported by intrinsic
multi-dimensional compression, repeated sharing, a concrete downstream
capability unlock or held-out transfer without any historical match. Such a
candidate remains `external novelty not evaluated` until the independent novelty
protocol is completed.

## First use

Issue #863 applies this contract first to the existing frozen historical corpus
as calibration evidence. The baseline must be frozen before any scorer or search
policy is repaired. The resulting matrix will therefore reveal candidates that
Regelsuche already reached but that the current representation assessor or ranker
failed to surface.

The first executable 6 × 4 × 6 calibration pilot begins with already retained
candidate sets. Its source artifact contains neither an independent bounded-
reachability receipt nor complete pre-retention stage sets. Observed positive
hits are therefore recorded as reached without oracle confirmation, while the
`reached`, `formed` and `retained` sets are explicitly retained-candidate
projections. This pilot can localize losses from retention to recognition and
from recognition to ranking. It cannot distinguish an earlier search miss from
a formation or retention loss until a trace-complete successor artifact supplies
those boundaries.

The subsequent held-out study in issue #750 must use disjoint TEST families and
report the same stage-conditioned recall and false-positive controls.

## Claim boundary

This audit can establish bounded target-free detection recall and identify the
first stage that lost a relevant representation under a frozen corpus,
information regime and work authority. It does not establish global
transformation-space completeness, universal interestingness, external novelty,
representation optimality or unrestricted theorem discovery.
