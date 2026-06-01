# External Knowledge Packs

A knowledge pack is an optional bundle of rewrite rules with explicit provenance, license, status, category, activation metadata, source references, and validation examples. Core rules remain available by default; external packs are loaded only when selected by `enabledByDefault`, a rule profile, or explicit pack enablement.

## Enabling and disabling packs

Use the core profile for default behavior. Packs with `enabledByDefault: true` are included unless explicitly disabled. Select `core+sympy-polynomial`, `exploratory`, `all`, or a custom selection to enable additional external packs such as `sympy-polynomial-basic`. Custom selections can also disable a pack to ensure its rules are not registered.

## Provenance in replay and reports

Rules loaded from a knowledge pack keep their `packId`, origin project, license, source version, source reference, derivation type, status, risk level, categories, and validation examples. Transformations expose the rule id together with the pack id and license so replay/report views can show where an applied rule came from.

## SymPy-derived rules

The initial SymPy polynomial pack is disabled by default and contains reviewed declarative identities only. It is manually reviewed and independently reimplemented for Regelsuche; it is not generated from SymPy source code and does not claim a direct code-level origin for the identities. SymPy-referenced identities are marked `REIMPLEMENTED_RULE`, carry `BSD-3-Clause` license metadata, and include YAML-local `validation.examples` for each validated rule.

## Copied code vs. reimplemented identities

`REIMPLEMENTED_RULE` means the rule is a declarative mathematical identity authored for Regelsuche. `TRANSLATED_CODE` must be used if code is directly translated from an external project, and such rules require explicit provenance and license metadata.
