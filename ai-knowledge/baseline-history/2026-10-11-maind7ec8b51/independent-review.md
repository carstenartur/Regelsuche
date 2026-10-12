# Independent review of the accepted-main baseline

Review date: 2026-10-11. Reviewer: root implementation agent, independently of
the agent that prepared and executed the predecessor measurement. This review
uses read-only Git, artifact, inventory and CI inspection; it does not attribute
the measurement agent's historical observations to the reviewer.

**Decision: approve byte-for-byte promotion of the generated predecessor
snapshot with SHA-256
`d2146a9f97686f724e49d5e710f46470674b39038b8595eae71dd2983c35c12b`.**
This decision applies only to the baseline process in `docs/ai-knowledge.md`.
It does not qualify PR #1088, a new SDK binary, performance, or a merge.

## Independently verified evidence

- Accepted main is `d7ec8b51d164dc58dbb3e9e328af8bb356425b93`, tree
  `2e9dd708951e02d68a786aa7871dee7b4a4a6347`. The current clean source checkout
  matches both identities. GitHub's commit and workflow APIs independently
  bind successful main run `38134140137` to this commit. All seven mandatory
  authorities, `Checkout-local ciCheck`, and report publication succeeded;
  optional JMH studies were skipped. The source PR's matching tree and prior
  successful CI are additionally retained in the preparer's provenance.
- The clean extractor checkout is the pinned v0.1.10 commit
  `b409bed957c31d63ce7b6ef37205890f0f0ebd9a`, tree
  `0cb8e41b91c831705f9c86633580181372da6e14`. All 12 recorded extractor bindings
  and all 15 repository bindings match their actual bytes. The repository
  bindings also match feature source `2452f1d6` before promotion. The actual
  plugin policy is distinguished from lifecycle wiring in the provenance.
- The full native predecessor log and receipt record exit zero, all 12
  actionable tasks executed, all eight coverage controls successful, and
  17 hotspot checks with no violation or active exception. The check and trend
  pass, with six existing nonfatal warnings and no claim failures. The launcher
  supplies transport and runtime settings; the proposed cache initializer was
  never applied. The existing Java 25 initializer retains extractor release 17.
- All 56 recorded artifact hashes match the retained files. The proposed
  snapshot is byte-identical to the actual generated predecessor output.
  Every reported class (1697), test (1191), document (427), and module build
  file (67) belongs to the pinned Git tree. Module `buildFile` fields, rather
  than module directory names, were used for the build-file comparison.
- The independently queried main artifact `11701386604`,
  `quality-acceptance-evidence`, is 29,172,778 bytes. Its downloaded ZIP matches
  the API digest `d65b2b2063839367183d4d66b050ff24259e6ba846367e3df3ab2c6897a7d0fd`.
  Reading the ZIP directly confirms that its original `check.json` and
  `complexity.json` are byte-identical to the new native measurement. Their
  hashes are respectively `0076917437d17d653814cbdf28183505774dfa0ff8465bd1a36590cc42150118`
  and `2a9afa3300014e94ed890ac5eb6ab32b3901bda0614701e1f78df94ae1cc4d48`.

## Promotion boundary

The accepted predecessor measures 696505 context tokens, +13670 against the
existing 682835-token baseline, within the unchanged +15000 limit. Its normalized
context debt is 15.05. The rejected feature head measures 698325 tokens: +15490
against the older baseline, but only +1820 against this independently qualified
predecessor. These values are diagnostic comparisons, not edited baseline input.

Retain the original measurement provenance, previous baseline, rejected feature
reports, complete raw predecessor reports, native log, and CI identities. The
raw report archive reviewed here has 39 safe relative entries, 1,431,548 bytes,
and SHA-256 `d6b9c747affd6a9c201c2e39b228c7466d37eef79db8dd3af17d76104888904b`.
Check the approved snapshot hash after copying. Thresholds, selectors, claims,
exceptions, source math, runtime SDK bytes and build authorities must remain
unchanged. The resulting feature head still requires its own native gate and
hosted CI; predecessor acceptance cannot substitute for those results.
