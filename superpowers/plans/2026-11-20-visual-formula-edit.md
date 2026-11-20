# Visual formula editing implementation plan

**Goal:** Make the existing formula preview directly editable through the same authoritative parser for #669 E.

**Architecture:** Parser-issued occurrence ranges travel through the existing preview JSON to an accessible MathML selection/editor. Validated source edits update the one ordinary search input.

**Tech stack:** Java 25, existing exact parser, vanilla JavaScript/CSS/MathML, OpenAPI, JUnit and Java Playwright.

**Spec:** [Source-bound visual formula editing](../specs/2026-11-20-visual-formula-edit-design.md).

## Global constraints

Reuse the existing parser and AST. Preserve unchanged source spelling, occurrence identity and operator precedence. Synthetic nodes without source ranges are not editable. Commit an edit only after the existing server inspection endpoint accepts the complete proposal. Source, input type and runtime profile changes invalidate pending responses. Preserve current source-length and profile constraints. No Node/npm runtime or new editor library. Work only in this isolated branch; no remote writes. #749 and its automation remain outside this work.

## Review focus

Check source range authority and equation offsets; exact occurrence and precedence preservation; asynchronous invalidation; native keyboard accessibility and responsive layout; real parser/search integration and honest docs. Test failure paths as well as a successful edit and avoid tests that merely mirror implementation.

### Task 1: Add source-bound editing to the formula preview

- [x] Read root `AGENTS.md`, the spec, the existing parser/preview/frontend and browser tests. Use existing parsed source provenance; extend the parser's typed companion API only if equation range support requires it.
- [x] Add focused regression/API/browser tests for missing source spans and rendered-subexpression edits. Observe the intended RED behavior before production changes and record the command/failure.
- [x] Extend preview JSON and OpenAPI with optional parser-issued half-open UTF-16 ranges, including correct equation offsets and absence for synthetic unary zero.
- [x] Implement selectable MathML plus an equivalent labeled native keyboard selector/editor. Reparse the complete grouped source replacement before committing; retain original text on invalid replacement and ignore stale responses after source/type/profile changes.
- [x] Verify editing the second `x` in `f(x, x) = -(0.50)^2`, exact AST/source spelling, actual search payload, text-to-preview refresh, invalid replacement and delayed-response races. Exercise a long formula and narrow viewport in the browser.
- [x] Run the affected unit/API tests, existing landing-page/browser flows and appropriate quality gates. Record exact commands, counts, skips and screenshot paths. Use the restored local toolchain and actual Chromium.
- [x] Update the Workbench guide and relevant preview documentation. If a genuinely useful actual screenshot is captured, integrate it into the existing gallery conventions; do not generate mock screenshots or reintroduce trivial algebra as a capability claim.
- [x] Self-review, commit the implementation and docs, and write the report with commits, RED/GREEN evidence and remaining limits. Do not publish remotely or spawn another agent.
