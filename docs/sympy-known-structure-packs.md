# SymPy-derived known-structure packs

Regelsuche may reuse selected, manually reviewed mathematical knowledge from
SymPy without importing SymPy's runtime, global `simplify()` policy or internal
expression model.

The first pack-backed structure catalog uses the existing
`KnowledgePackRegistry` and `KnowledgePackSelection`. A pack contributes both
independently governed rewrite rules and provenance-rich known structures.
Nothing is visible to representation discovery until the pack is explicitly
selected.

## Boundary

The integration deliberately preserves these distinctions:

- a known-form match is classification, not an equivalence proof;
- a rewrite rule is independently governed from the structure that makes it
  applicable;
- candidate validation must meet the structure's `minimumEvidence` before a
  consequence is considered unlocked;
- source project, license, immutable source revision, source reference,
  translation notes, rule-pack dependencies and compatible backends are
  content-addressed with the catalog;
- experimental SymPy packs remain disabled by default;
- Python is optional QA infrastructure and is not a core runtime dependency.

The initial `sympy-trigonometry` tranche is pinned to SymPy 1.14.0 commit
`fe935ceb303891d1f8bea4c03b19fd9ec9464b02`. It adds only small, independently
reviewable identities from the Fu/trigonometric simplification family. The
assumption-sensitive powered and half-angle branches of `TR2i`, SymPy's global
simplification heuristics and automatic source extraction are intentionally not
ported.

## Information regimes

Pack selection is part of the experiment identity.

- In catalog-blind discovery, the SymPy-derived pack remains disabled while
  candidates are generated and is enabled only for post-hoc classification.
- In catalog-visible navigation, the pack is explicitly enabled before search.
- Hidden-structure rediscovery removes both the structure and its direct rule
  from the visible inventory.

This keeps imported knowledge useful without allowing it to masquerade as an
autonomous Regelsuche discovery.
