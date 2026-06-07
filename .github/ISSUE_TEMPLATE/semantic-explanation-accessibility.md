---
name: Semantic Explanation & Non-Visual Navigation
description: Architecture issue for semantic descriptions, accessibility, and non-visual math navigation
title: "Semantic Explanation Layer and Non-Visual Navigation"
labels: []
assignees: []
---

## Context

Regelsuche already models mathematics as a transformation search space: nodes are expressions, edges are transformations, and paths are replayable calculation routes. The README explicitly positions the project this way: "Knoten sind Ausdrücke, Kanten sind Umformungen, Pfade sind Rechenwege — mit Replay, Proof-Bridge und einem klickbaren Web-Workbench."

The current architecture is a Gradle multi-project with clear module boundaries. `regelsuche-core` contains the mathematical kernel, `regelsuche-egraph` depends only on core, `regelsuche-search` contains search profiles and strategies, `regelsuche-discovery` contains portable discovery path DTOs, and `app` remains the runtime shell for web/CLI/adapters. The architecture docs also state important guardrails: the mathematical core should remain technology-agnostic, infrastructure belongs in app or adapter modules, and large new components should start with stable interfaces.

The current web workbench already has first accessibility-related pieces. In `app/src/main/resources/web/app.js`, `renderMathLayout` can inject `layout.aria` as an `aria-label`, and several UI comments mention screen-reader/fallback behavior. That is valuable, but it is still too close to the UI/rendering layer. We should avoid growing accessibility through scattered `aria-label`, `alt`, or `getAltText()` logic in the web frontend.

## Problem

Alternative text on graphs and formulas would help, but it is not enough.

For blind and visually impaired learners, the hard part is not only reading a single formula. The hard part is understanding the structure of a transformation:

- What is the current expression?
- Which rules are applicable?
- Why is a rule applicable?
- What changes between before and after?
- Which path is shortest, safest, or most instructive?
- Which alternatives exist?
- Where did a student make an invalid transformation?

If those explanations are generated inside the web UI, the architecture gets worse:

- exports must duplicate explanation logic,
- future tutor features must duplicate explanation logic,
- LLM prompts must duplicate explanation logic,
- screen-reader support becomes a UI afterthought,
- graph rendering and domain semantics become coupled,
- and tests cannot easily verify the meaning independently from HTML/SVG/Cytoscape/Mermaid rendering.

## Goal

Introduce a semantic explanation layer that describes Regelsuche objects independently of any visual representation.

The goal is **not** merely to add image alt text.

The goal is:

> Every relevant mathematical object in the transformation search space should have a stable, testable, non-visual semantic description that can be consumed by the web UI, screen readers, exports, tutor flows, APIs, and LLM prompts.

Accessibility should become one consumer of the semantic layer, not a separate UI patch.

## Architectural requirement

Do not degrade the architecture by sprinkling accessibility/rendering logic through existing modules.

It is acceptable to change the architecture because there are no external users and no backwards-compatibility constraints. Prefer a clean redesign over preserving accidental API shapes.

Suggested direction:

```text
Domain/search/discovery objects
        |
        v
Semantic explanation layer
        |
        +--> Web UI aria/alt text
        +--> screen-reader navigation
        +--> Markdown/HTML/LaTeX/JSON exports
        +--> tutor mode
        +--> LLM prompts
        +--> tests and documentation
```

The semantic layer must not depend on:

- Spring/Web,
- HTML/SVG/Cytoscape/Mermaid,
- Hibernate/PostgreSQL/Neo4j,
- browser APIs,
- or testcontainers/Docker.

## Possible module shape

Add a module such as:

- `regelsuche-explanation`, or
- `regelsuche-semantic`, or
- `regelsuche-accessibility` only if it stays semantic and not UI-specific.

Preferred: `regelsuche-explanation`, because the value is broader than accessibility.

Possible dependencies:

```text
regelsuche-explanation -> regelsuche-core
regelsuche-explanation -> regelsuche-search      (only if path/search result types require it)
regelsuche-explanation -> regelsuche-discovery   (only if discovery DTOs are the stable path representation)
app -> regelsuche-explanation
```

The exact dependency direction should be chosen after inspecting the current domain types. If a clean interface requires moving or stabilizing DTOs, do that instead of adding adapter glue.

## Candidate API

This is only a sketch; use better names if the code suggests them.

```java
public record SemanticDescription(
    String shortText,
    String detailedText,
    List<SemanticSection> sections,
    List<SemanticAction> actions
) {}
```

```java
public interface SemanticDescriber<T> {
    SemanticDescription describe(T object, DescriptionContext context);
}
```

```java
public record DescriptionContext(
    DescriptionAudience audience,
    DescriptionVerbosity verbosity,
    Locale locale
) {}
```

```java
public enum DescriptionAudience {
    SCREEN_READER,
    STUDENT,
    TEACHER,
    EXPORT,
    LLM_PROMPT,
    DEBUG
}
```

Do not over-engineer in the first implementation. A small, stable interface with tests is better than a large generic framework.

## Objects that should become describable

### Expression

Example expression:

```text
(x + 1)^2
```

Bad description:

```text
Expression: (x + 1)^2
```

Better description:

```text
A sum consisting of x and 1. The whole sum is squared.
```

The first implementation may start simple, but it should already distinguish important structures such as sum, product, power, fraction, equation, inequality, derivative, and matrix where the current AST supports them.

### Rewrite rule

Example:

```text
(a + b)^2 -> a^2 + 2ab + b^2
```

Description:

```text
First binomial formula. Expands the square of a sum into the sum of the squares plus twice the product.
```

### Rewrite step

Description should include:

- before expression,
- after expression,
- applied rule,
- the local change,
- assumptions or side conditions if present,
- whether the transformation is proven, validated, heuristic, or unverified.

Example:

```text
Step 3 applies the first binomial formula. The squared sum (x + 1)^2 is expanded to x^2 + 2x + 1.
```

### Path

Description should include:

- number of steps,
- start expression,
- end expression,
- rules used,
- proof/validation status,
- warnings,
- and a step-by-step list.

Example:

```text
This path has 4 steps. It starts with x^2 + 2x + 1 and ends with (x + 1)^2. The main operation is recognizing the first binomial formula in reverse.
```

### Search result / graph summary

Description should include:

- number of nodes and edges,
- number of discovered paths,
- selected/best path,
- target reached or not,
- dead ends if available,
- learned macro rules if involved,
- and alternative next steps.

Example:

```text
The search graph contains 154 expressions and 391 transformations. The target was reached. The shortest discovered path has 4 steps. Three alternative paths also reach the same target.
```

## Web UI integration

The web frontend should consume semantic descriptions from backend responses rather than constructing semantic explanations itself.

Possible API additions:

- include `description` blocks in existing `/api/demo/...`, `/api/paths`, `/api/explain/{id}`, `/api/graph` responses, or
- add dedicated endpoints such as `/api/describe/path/{id}` and `/api/describe/graph`.

Preferred direction: enrich existing DTOs if that keeps the UI simple and avoids extra round trips.

The web UI can then use the semantic data for:

- `aria-label`,
- visually hidden summaries,
- graph text alternatives,
- keyboard-focused node descriptions,
- path replay narration,
- and export previews.

Important: UI rendering may format the text, but it must not invent the mathematical meaning.

## Non-visual graph navigation

Add a text-first navigation model for graphs and paths:

- current node/expression,
- incoming transformations,
- outgoing transformations,
- recommended next transformation,
- alternative transformations,
- previous/next step in selected path,
- jump to shortest path,
- list all paths to target,
- announce proof/validation status.

This does not need to be a complete screen-reader UI in the first PR. But the backend/frontend contract should make such a UI possible.

## Export integration

Reuse the same semantic descriptions in exports:

- Markdown,
- HTML,
- JSON,
- LaTeX notes,
- Mermaid/GraphML sidecar summaries,
- report bundles.

This avoids a separate export-only explanation implementation.

## Tutor-mode follow-up enabled by this issue

Once steps and paths can be described semantically, later issues can implement:

- "Why is this step valid?"
- "Which rule did I use?"
- "Is my next step equivalent?"
- "This step is invalid because ..."
- "Here are three possible next moves."

## Accessibility follow-up enabled by this issue

Later issues can implement:

- WCAG-oriented keyboard-only graph navigation,
- NVDA/JAWS/VoiceOver smoke tests,
- MathML/speech/Braille output experiments,
- screen-reader regression tests,
- visible/non-visible synchronized replay,
- GitHub topics such as `accessibility`, `assistive-technology`, or `screen-reader` once a real workflow exists.

## Non-goals for the first implementation

- No complete Braille output.
- No full speech synthesis.
- No promise of WCAG conformance in the first PR.
- No visual redesign of the entire workbench.
- No special-case-only alt text for one graph renderer.
- No UI-only accessibility solution.
- No backwards compatibility requirement for experimental DTOs or endpoints.

## Suggested implementation phases

### Phase 1: Architecture decision and module boundary

- Decide whether to create `regelsuche-explanation` or another semantic module.
- Add the module to `settings.gradle`.
- Add dependency rules to `ArchitectureBoundariesTest`.
- Document the new module in `docs/architecture.md`, `docs/module-structure.md`, and `docs/dependency-rules.md`.
- Add or update an ADR under `docs/adr/`.

### Phase 2: Minimal semantic API

- Introduce `SemanticDescription` and `SemanticDescriber` or equivalent.
- Implement descriptions for at least:
  - expression,
  - rewrite rule,
  - rewrite step,
  - path.
- Add fast unit tests independent of web and persistence.

### Phase 3: Backend DTO integration

- Add description data to selected existing responses or dedicated describe endpoints.
- Cover at least one demo path end-to-end.
- Ensure descriptions are deterministic enough for snapshot tests.

### Phase 4: Web UI consumption

- Use backend-provided descriptions for:
  - path replay summaries,
  - graph summary text,
  - formula/step `aria-label`s where applicable,
  - visually hidden graph alternative text.
- Remove/avoid duplicated semantic construction in `app.js`.

### Phase 5: E2E and accessibility smoke test

- Add a Playwright/browser E2E test that verifies:
  - graph summary text exists,
  - selected path can be understood from text alone,
  - key controls are reachable by keyboard,
  - important math nodes have meaningful labels.

## Acceptance criteria

- A semantic explanation module or equivalent clean architecture boundary exists.
- The mathematical core remains technology-agnostic.
- The semantic layer has no Spring/HTML/SVG/Cytoscape/Mermaid/Hibernate/Neo4j dependencies.
- Expression, rule, step, and path have first semantic descriptions.
- At least one full demo exposes semantic descriptions from backend to web UI.
- The web UI consumes semantic descriptions instead of inventing them locally.
- A graph/path can be understood from a text-only representation.
- Architecture tests are updated so the new boundaries are enforced.
- Documentation explains why this is an explanation architecture, not merely an accessibility patch.

## Why this matters strategically

This issue supports multiple long-term product directions at once:

- blind and visually impaired learners,
- inclusive math education,
- teacher-facing explanations,
- student tutor mode,
- explainable symbolic computation,
- LLM-readable transformation traces,
- reproducible exports,
- and formal proof workflows.

The central product idea becomes stronger:

> Regelsuche does not only compute mathematical results. It makes mathematical transformations explicit, navigable, explainable, and reusable.
