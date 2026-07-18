# Artifact appendix contract

## Purpose

The archival artifact must allow a reviewer to reconstruct the evaluated revision, execute the supported workflows, validate schemas and roots, regenerate all paper tables and figures, and distinguish exact-byte from semantic reproduction.

## Required archival components

- immutable source revision and release tag;
- source archive digest;
- pinned container definition and image digest;
- Java, Gradle, operating-system, solver, and external-backend revisions;
- candidate-independent benchmark plan and corpus manifest;
- campaign, qualification, comparison, ablation, and amortization evidence;
- capability/claim status source;
- schemas and independent verification scripts;
- manuscript source, generated tables and figures;
- license, citation, authorship, contribution, and automated-tool disclosures;
- independent reproduction receipt when available.

## Reproduction levels

`EXACT_BYTE_REPRODUCED` requires all designated portable files to match exactly.

`SEMANTICALLY_REPRODUCED` requires every authoritative canonical root and scientific status to match while explicitly permitted non-semantic metadata may differ.

`NOT_REPRODUCED` retains every missing or differing required root. It must not be converted into success by manually replacing output files.

## Review sequence

1. verify the archive and `paper-artifact-manifest.json`;
2. verify all frozen input roots before execution;
3. execute the supported reproduction command in the pinned environment;
4. validate every schema and cross-artifact relation;
5. regenerate manuscript tables and figures from raw evidence;
6. compare expected and observed semantic and exact-byte outputs;
7. retain a machine-readable reproduction receipt and all diagnostics.

## Claim boundary

Artifact reproduction verifies execution and evidence relationships. It does not establish external mathematical novelty, importance, or correctness beyond the strength of the included validation and proof artifacts.
