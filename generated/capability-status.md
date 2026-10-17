# Generated capability and claim status

> This file is generated from retained canonical evidence and hash-bound implementation contracts.
> It reports only the bounded claim named in each row.

- Policy: `EVIDENCE_DERIVED_FAIL_CLOSED`
- Repository revision mode: `WORKTREE`
- Status content hash: `sha256:5ec26434f828f0bb752dfce3f039003d451199077613562c119f7002dba6baac`
- Release run: `sha256:28883a5d20ef3f8f0f2ef579a12c1063c4c2ebbd89d53faeac49c5c5948a4b1d`
- Domain-generic run: `sha256:93a377c32e23404786abf9f605bbf14417d7233b74873537443d78ae36011800`

| Capability | Status | Bounded claim |
|---|---|---|
| `AUTONOMOUS_CAMPAIGN` | `QUALIFIED` | autonomous generation, validation and retention of mathematical conjectures |
| `DOMAIN_GENERIC_DISCOVERY` | `QUALIFIED` | reproducible generation, bounded search, counterexample search, validation, certificate rendering and evidence across distinct mathematical object types |
| `EXTERNAL_NOVELTY_REVIEW` | `BLOCKED` | externally reviewed mathematical novelty |
| `FORMAL_PROOF_OF_RETAINED_CANDIDATE` | `NOT_EVALUATED` | A formal theorem-prover proof of the retained production candidate. |
| `HIDDEN_RULE_REDISCOVERY` | `QUALIFIED` | rediscovery of withheld known rules without target leakage |
| `OPEN_TARGET_DISCOVERY` | `QUALIFIED` | formation and validation of an open-target conjecture |
| `PLUGIN_ARTIFACT_TRUST` | `IMPLEMENTED` | Local detached-signature verification, publisher trust policy and verified-byte staging are implemented. |
| `PLUGIN_INDEX_AUTHENTICATION` | `IMPLEMENTED` | Immutable local index revisions can be authenticated through curator Ed25519 signatures. |
| `PLUGIN_TRUST_STATE_REVISIONS` | `IMPLEMENTED` | Hash-chained trust-state revisions implement pinned-authority, replay, gap and fork checks. |
| `PROMOTION` | `NOT_EVALUATED` | Promotion of the retained production candidate into active reusable knowledge. |
| `PUBLIC_EVIDENCE` | `NOT_EVALUATED` | Publication-authorized public evidence for the retained production candidate. |
| `PUBLIC_PLUGIN_DISTRIBUTION` | `BLOCKED` | Hosted discovery, authenticated transport, download, installation, update, removal and rollback of published extensions. |
| `SAFE_PREPARATION_PRODUCT` | `EXPERIMENTAL` | Shared opt-in DIRECT_V1/SAFE_PREPARATION_V4 runtime with bounded public contract qualification; broader inventory and default qualification remain open. |
| `SEARCH_REPRODUCIBILITY` | `QUALIFIED` | reproducible target-free search under pinned inputs |

## Interpretation

- `IMPLEMENTED` means that the named software contracts, schemas and checkout-local validators are present and hash-bound; it is not a qualification of a wider service.
- `QUALIFIED` means that the named evidence profile is ready for exactly its recorded claim.
- `EXPERIMENTAL` retains an explicit opt-in boundary even when a separate bounded public contract comparison is green.
- `BLOCKED` and `NOT_EVALUATED` remain visible and must not be paraphrased as success.
- Project novelty, external novelty, symbolic validation, formal proof, promotion and Public Evidence remain distinct.
