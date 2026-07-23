# Blinded interestingness-review packet export

## Purpose

`InterestingnessBlindReviewPacketExporter` turns a frozen independent-review study plan into reviewer-specific packets without exposing candidate identity, split assignment, ranking, system provenance, family labels or private reviewer assignments.

This is operational tooling for issue #389. It does **not** create expert reviews, consensus, qualification evidence or empirical interestingness results.

## Trust boundary

The exporter writes two deliberately separate trees:

```text
review-export/
  public/
    manifest.json
    packets/<packetId>.json
  private/
    assignment-manifest.json
```

Only `public/` is suitable for distribution to reviewers. `private/assignment-manifest.json` binds packet IDs to reviewer, case and candidate identities and must remain access controlled with the reviewer-hash salt and qualification/conflict records.

Public packets include only the frozen reviewer instructions, blinded presentation, declared scales and rationale codes. They explicitly retain `reviewCollectionStatus=NOT_COLLECTED`.

## Determinism and integrity

Assignments are canonicalized by assignment ID. Presentation bytes and reviewer instructions must match the hashes frozen in the study plan. Duplicate assignment IDs, duplicate reviewer/candidate pairs, unknown cases, missing presentations, unexpected materials and insufficient reviewer coverage fail closed.

Every packet and manifest is content-addressed. Map serialization is key-ordered so repeated exports are byte-identical. Directory export uses a staging tree and replaces the previous tree as one unit, preventing stale packets from surviving a new revision.

## Verification

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.mining.InterestingnessBlindReviewPacketExporterTest
```

The characterization requires deterministic output, public/private separation, absence of private and split identifiers from public packets, rejection of malformed assignments and removal of stale files.

## Claim boundary

Repository fixtures may verify software and leakage boundaries only. A counted study still requires real qualified independent reviewers, conflict checks, privacy governance, collection freeze, intake, consensus, CALIBRATION-only selection and one-time TEST evaluation under the existing #332/#389 protocol.
