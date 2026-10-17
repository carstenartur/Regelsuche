# Qualified dependency integration — 2026-10-17

Binding scope: integrate the already reviewed, independently CI-green dependency
updates before resuming the work-replacement roadmap. This is no new search or
learning milestone. Default branch: main fcce00ed96425b96a9f8f884356d5a21c761a881.

| PR | Qualified head | Successful full CI |
| --- | --- | --- |
| #1094 | 2feb3b8cc22a304369acbdae968740c73c0fe0a2 | 38986788230 |
| #1097 | d938859bbfafaff3f8a1421a318eea0b8ac73b80 | 38988084970 |
| #1099 | 8d5f6ef65c7d69620a755b7172d3c86a8fd147d2 | 38988006942 |

The independent whole-batch review found no blocking code issue in these three
updates. Their current review-thread queries are empty. The separate wrapper,
Cytoscape and shared-additive PRs are not included.

## Integration steps

- [x] Merge the three exact qualified heads onto current main in isolation.
- [x] Resolve the adjacent POM-property conflict by preserving all three upgrades:
  Networknt3.0.8, PostgreSQL42.7.14 and Hibernate ORM7.4.13.Final. Existing parity
  JUnit tests remain authoritative; no version or quality check is disabled.
- [x] Run the complete Release/Build-contract dependency reactor on this combination.
- [ ] Publish a normal integration PR preserving all source heads as ancestors.
- [ ] Require exact-head hosted CI including aggregate ciCheck on current main,
  then use a regular merge (not squash) so the original heads remain integrated.

Ruling: combine only the three independently green maintenance changes in one
integration PR — the strict up-to-date-main rule requires fresh combined CI;
one combined run checks their actual interaction without repeated serial rebuilds
of each intermediate main — cost if wrong: a failing integration remains open
and the three source PRs can still be handled separately. No protection bypass.

Ruling: the original PR check receipts do not certify this combined tree — run
fresh local/hosted checks and keep their statuses separate — cost if wrong:
integration is blocked, never inferred from the earlier green heads.

P04 total qualification and P05–P12 remain subsequent work. No economic or
mathematical capability claim follows from dependency maintenance.

Fresh local verification on merged source5ab33fb9: Java25.0.2, Maven3.9.11,
`-Pproduct-reactor -pl regelsuche-release,maven-build-contract -am test` succeeds.
The13 tested modules report4260 tests, zero failures/errors, one pre-existing
distribution-profile skip. All87 release tests and116 build-contract tests run.
No failed attempt is relabeled as success, and hosted/container authority remains
required separately. The remaining local change is this integration record only.
