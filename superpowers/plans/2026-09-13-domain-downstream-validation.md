# #224: bounded downstream validation of retained domain exports

Approved bounded design, implemented by the slice documented in
`docs/domain-downstream-finite-validation.md`. Source base:
`22eac9b68705be13dca3a2378c161d8a988b2329` (published PR #1003).
Own branch: `codex/issue224-domain-lifecycle-consumer`.

## Verified source findings

- `DiscoveryDomain<S,C,K>` already separates generation, candidate formation,
  counterexample search, evaluation and certificate rendering. Its
  `CanonicalCodec` only encodes; it does not decode a candidate from a hash.
- `DomainDiscoveryExportVerifier.VerifiedDomainExport` already owns defensive
  exact byte snapshots. Its receipt explicitly verifies identity and leaves
  mathematical validation `NOT_EVALUATED`.
- `DomainExportWorkspace.fromVerified` already validates the complete built-in
  finite-difference descriptor, reconstructs typed evidence, checks canonical
  equality, derives the original handoff and validates all five resource roles.
  Its repository and HTTP routes already import, list, load and download the
  exact original export. Do not recreate those adapters.
- `DomainExportWorkspace.replay` already runs the same original domain, seed and
  budget and compares the entire canonical evidence. The HTTP replay response
  has `IDENTICAL_CANONICAL_EVIDENCE`. It does not run a mathematically independent
  checker and does not persist a downstream validation decision.
- The workspace dispatch currently admits only
  `integer-sequence-finite-difference@v1`. `LinearRecurrenceSequenceDomain` is
  already implemented and has exact observed-prefix formation, counterexample
  search, held-out evaluation, codecs and finite witness rendering. Its
  existing frozen-corpus Release adapter is not a reusable verified-export
  consumer; do not rerun or replace that campaign.
- Both domains' counterexample generator and evaluator call their respective
  shared `generate` routine. A second invocation of that evaluator is not an
  independent mathematical implementation.
- `DomainDiscoveryRunner.RunResult<C,K>` returns the selected typed candidate
  and certificate. `CandidateAttempt` retains only hashes/statuses/metrics, not
  failed candidate objects. Therefore a consumer must reproduce and bind the
  whole run before using its selected typed objects. It must not reconstruct
  a missing candidate from ad hoc metric strings.
- `DiscoveryLifecycleHandoff/v1` admits only `GENERATION` and
  `DISCOVERY_VALIDATION`; it mandates `NOT_EVALUATED` for proof, external
  novelty, promotion and Public Evidence. `AutonomousProductionLifecycleRunner`
  still consumes algebraic `MiningRun`/`OpenTargetConjecture` objects rather than
  verified generic exports. Changing its candidate language is outside this
  first slice.

## Recommended bounded implementation

1. Reuse the existing verified workspace boundary. Factor its built-in domain
   dispatch just enough to also admit the exact existing linear-recurrence
   descriptor. Keep original finite-difference workspace bytes and APIs
   identical. Do not enable arbitrary provider classes or add another importer.
2. Retain the typed `RunResult` internally after the existing full canonical
   replay comparison. A privately issued observation binds the original export
   manifest, exact artifact bytes, workspace, descriptor, seed, evidence and
   handoff. Recompute selected candidate, certificate-object and rendered
   certificate hashes through the original codecs. No caller-supplied `PASS`
   flag or mutable decoded JSON authorizes validation.
3. Add independent finite-data checkers in the existing discovery/domain
   module, using core's `ExactRational`/`BigInteger` authority:
   - Finite difference: evaluate `sum_j binomial(n,j) * delta_j` independently
     of the domain's advancing-difference implementation. Check every retained
     observed and holdout term and the existing witness's terms/counts.
   - Linear recurrence: evaluate exact residuals
     `a_n - sum_j c_j*a_(n-j)` over the retained observed/holdout windows, using
     core rational arithmetic rather than the domain's generator/RREF helper.
     Check seed terms, coefficient/order binding and the complete witness.
   No new inference or candidate search is added after the source binding.
4. Emit an additive domain-neutral downstream receipt, e.g.
   `regelsuche.domain-downstream-validation/v1`. It binds the unchanged source
   handoff and original identities to a separate, schema-specific check
   artifact and checker contract. Preserve source outcome independently from
   replay status and the new check's status. Reuse the existing domain
   evaluation/counterexample semantics; do not coerce sequences into
   `RuleValidationReport`'s expression examples or R1/R2 representation inputs.
5. Expose this as an explicit bounded `POST /{runDigest}/validate` operation on
   the existing domain-export API, requiring exact expected workspace/evidence
   identities. The returned immutable receipt carries real validation and
   finite counterexample-check roles. Missing proof obligations/universal proof
   artifacts have no invented target hash. Source proof/novelty/promotion/Public
   Evidence fields remain `NOT_EVALUATED`; new universal proof artifact status
   remains `NOT_PRODUCED`.

The first receipt is an explicit downloadable API result, not a modification
of the saved source or its two-entry repository membership. A server-side
downstream receipt catalogue, UI redesign and release aggregation are later
slices. This keeps the implementation small while adding a real productive
validation consumer instead of another source/status projection.

## Negative and resource boundaries

- Original `REFUTED`, `INCONCLUSIVE`, `UNSUPPORTED` and missing-candidate cases
  remain visible. A successfully repeated negative source has no selected typed
  candidate; independent selected-candidate validation is explicitly not
  performed. Do not invent failed candidate bodies from hashes.
- Failed complete-evidence replay blocks mathematical validation. A mismatching
  term produces an exact finite counterexample. Unsupported inputs, arithmetic
  or work refusal and technical failures remain distinct from confirmation.
- Keep original source resources unchanged. Retain additional replay resources
  and a separate bounded check ledger with configured/executed/skipped/remaining
  semantics; do not sum unlike roles into one universal score.
- Apply a conservative downstream input admission before invoking the existing
  domain runner, as well as term-count, order, coefficient-bit and operation
  bounds before independent arithmetic. Preserve the source budget exactly;
  oversized historical sources remain readable but cannot claim this check.
- Exact operation/term counts describe this checker only. Existing replay and
  domain arithmetic internals are not magically measured. No total-work or
  comparative gain claim follows.
- Confirmation means agreement with all retained finite data. It does not prove
  a unique infinite continuation, minimal recurrence order, formal theorem,
  external novelty, promotion or Public Evidence.

## Meaningful ordinary controls after design approval

- Existing finite-difference workspace/export canonical bytes remain unchanged.
- Both original built-in domains pass small public positive examples through
  actual export, verified snapshot, complete replay and independent checks.
- Changed witness terms, coefficients/order, candidate/source hashes and
  rehashed false source evidence cannot acquire validation authority.
- Actual checker negative cases expose exact mismatching term/residual data.
- Refuted/incomplete sources do not acquire a selected candidate or proof.
- Partial term/operation budgets retain their consumed prefix and cannot
  authorize full validation; source counters stay unchanged.
- Real HTTP source-binding mismatch rejects before replay/check; unsupported
  domains and revisions remain closed. No browser or protected study is needed.
- Focused Java 25 Maven controls only; no Gradle, GitHub writes, frozen-corpus
  execution or new qualification campaign.

This proposal addresses one downstream validation/evidence boundary of #224.
It deliberately leaves generic novelty, formal proof, promotion, release,
Public Evidence and multi-domain comparative qualification unfinished.
