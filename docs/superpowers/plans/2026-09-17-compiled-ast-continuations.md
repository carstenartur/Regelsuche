# Compiled AST continuation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry actual ASTs through all sources of an existing compiled linear rewrite program.

**Architecture:** The existing `CompiledLinearRewriteEngine.compileAst()` explicitly creates a typed executor from its already validated Source/Sequence plan. Stage transports reuse each prepared source's rules and bounds. Typed candidate histories retain every intermediate state, source-node ID and primitive metadata; replay regenerates the complete program under the same bounds. The legacy entry point is untouched.

**Tech Stack:** Java 25, existing Expr, AstRewriteTransport, RewriteProgram, TransformationWorkMetrics and JUnit. No dependencies.

**Spec:** Issue #1010 and `docs/typed-primitive-ast-transport.md`, Remaining work section. This is the compiled-continuation slice, not the complete search/serialization migration.

## Global constraints

One through eight prepared primitive sources, original per-source growth/candidate limits, compiled candidate bound 1 through 128. Reject unsupported engines before invoking any source. Do not fall back to a string round trip. Preserve exact numeric nodes, scoped symbols, grouped trees and per-step assumptions. No change to old policy identities, defaults, frozen references or acceptance gates.

## Task 1: typed compiled execution

Files: `regelsuche-core/src/main/java/de/regelsuche/transform/PreparedAstRewriteTransformationEngine.java`; `regelsuche-search/src/main/java/de/regelsuche/search/program/CompiledLinearRewriteEngine.java`; new sibling `CompiledAstRewriteProgram.java`; new test `regelsuche-search/src/test/java/de/regelsuche/search/program/CompiledAstRewriteProgramTest.java`.

- [x] Write producer-grouping and rational-leaf tests through an actual two-source compiled program; demonstrate failure of a compiling text-delegating scaffold.
- [x] Add a prepared-source transport factory preserving original rules and bounds.
- [x] Implement typed stage traversal, immutable candidate histories and structural full-path deduplication. Use deterministic source emission order, versioned separately from legacy text sorting.
- [x] Count actual source invocations, emitted primitive candidates, path compositions and duplicate removals using the existing mechanical ledger; separately count emitted primitive work. This is not per-node/CPU/allocation accounting or a new hard work-limit contract.
- [x] Replay by regenerating the entire program result and requiring full candidate equality. Report replay generation work separately, do not return an unverified partial path.

## Task 2: integration and boundaries

New test: `regelsuche-learning/src/test/java/de/regelsuche/evolution/CompiledAstBindingTransportTest.java`.

- [x] Use the existing evolution compiler and its real inventoried three-source plan, not a manually coded replacement tactic.
- [x] Learn shared bindings from actual typed compiled runs; test grouped, rational, scoped and wrong-occurrence application paths.
- [x] Test candidate/growth bounds, unsupported source rejection, independent convergent histories, exact ledger counts, immutable histories, metadata/condition tampering and unchanged legacy execution.
- [x] Run focused and existing suites from freshly compiled changed modules.
- [ ] Publish the verified diff and request review. Ordinary complete CI remains required for merge.

## Explicit deferred work

General search, live dispatch collection and persisted structural replay still need typed state integration. No closure of #1010 and no speedup claim from this slice. Typed histories certify replay relative to supplied primitive rules, not arbitrary-rule mathematical validity, discharged assumptions or occurrence positions.
