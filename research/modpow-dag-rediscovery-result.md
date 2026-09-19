# Target-blind modular exponent DAG rediscovery

Frozen verdict: **GREEN**.

| case | original TREE | selected TREE | original DAG | selected DAG | complete | reuse |
|---|---:|---:|---:|---:|---|---|
| C31 | 46 | 46 | 46 | 31 | true | true |
| C61 | 91 | 91 | 91 | 61 | true | true |
| C93 | 139 | 139 | 139 | 93 | true | true |
| C123 | 184 | 184 | 184 | 123 | true | true |
| NEGATIVE_NO_SHARED_E | 139 | 139 | 139 | 139 | true | false |

TREE is the control that charges repeated calls repeatedly. DAG charges a structurally identical modpow call once.
The known factored Pocklington target is not supplied to the search; selection happens after bounded closure.

## Evidence

- hosted run: `35391450848`
- artifact: `10565652806`
- artifact SHA-256: `d6039fdceb94115cbb390375ae10bd2a07cf3dcbd6b167ca6a34cdabd9f86e53`
- frozen plan commit: `15d34b5cea99855d0980251e917261dac895cace`
- result-producing source head: `8badc4c13785a74ff6356dfba5c709b5bdc2aa63`

Every TEST row reports `BOUNDED_EXHAUSTED`, a complete bounded successor relation, 3 reached states, 2 generated successors, 2 accepted proof receipts, total search work 64 and verification work 46.

The GREEN result is deliberately narrower than “Regelsuche discovered a new theorem”: the generic modular-exponent composition law is part of the grammar. What Regelsuche did without a supplied target was exhaust the bounded alternatives and select the one that exposes reusable computation under a predeclared DAG cost. The negative control shows that the DAG score does not reward factorization when no identical residue can actually be reused.
