# External Knowledge Packs

A knowledge pack is an optional bundle of rewrite rules with explicit provenance, license, status, category, and activation metadata. Core rules remain available by default; external packs are loaded only when selected by a rule profile or explicit pack enablement.

## Enabling and disabling packs

Use the core profile for default behavior. Select `core+sympy-polynomial`, `exploratory`, `all`, or a custom selection to enable external packs such as `sympy-polynomial-basic`. Custom selections can also disable a pack to ensure its rules are not registered.

## Provenance in replay and reports

Rules loaded from a knowledge pack keep their `packId`, origin project, license, derivation type, status, risk level, and categories. Transformations expose the rule id together with the pack id and license so replay/report views can show where an applied rule came from.

## SymPy-derived rules

The initial SymPy polynomial pack is disabled by default and contains reviewed declarative identities only. SymPy-derived identities are marked `REIMPLEMENTED_RULE` and carry the `BSD-3-Clause` license metadata.

## Copied code vs. reimplemented identities

`REIMPLEMENTED_RULE` means the rule is a declarative mathematical identity authored for Regelsuche. `TRANSLATED_CODE` must be used if code is directly translated from an external project, and such rules require explicit provenance and license metadata.
