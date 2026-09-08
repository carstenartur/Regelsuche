# Generated capability and claim status

> This file is generated from retained canonical evidence and hash-bound implementation contracts.
> It reports only the bounded claim named in each row.

- Policy: `EVIDENCE_DERIVED_FAIL_CLOSED`
- Repository revision mode: `WORKTREE`
- Status content hash: `sha256:1985ba6843f05a99998d714f18c6d7a6c15219efccea4f41b4820816d54da11a`
- Release run: `sha256:e549f6ada671c13fc38f84377a07638244d6ff037142d46a4955929e6b8c4326`
- Domain-generic run: `sha256:7ed14645710b106744376191f8a6426a6ed53bcb813f68a92c92810d799f3282`

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
| `SEARCH_REPRODUCIBILITY` | `QUALIFIED` | reproducible target-free search under pinned inputs |

## Interpretation

- `IMPLEMENTED` means that the named software contracts, schemas and checkout-local validators are present and hash-bound; it is not a qualification of a wider service.
- `QUALIFIED` means that the named evidence profile is ready for exactly its recorded claim.
- `BLOCKED` and `NOT_EVALUATED` remain visible and must not be paraphrased as success.
- Project novelty, external novelty, symbolic validation, formal proof, promotion and Public Evidence remain distinct.
