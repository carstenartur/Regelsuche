# GitHub merge governance

Regelsuche keeps test semantics in the checkout, but GitHub still has one
important platform responsibility: the default branch must not accept an
ordinary merge unless the checkout-owned verification result for that pull
request is green.

The intended platform policy is versioned in
[`config/github-merge-governance-policy.json`](../config/github-merge-governance-policy.json).
The checkout verifies the parts that can be derived from repository content via
`./gradlew verifyWorkflowSemantics`.

## Required default-branch policy

For `main`, the GitHub ruleset must satisfy all of the following invariants:

- required status context: `Checkout-local ciCheck`;
- required-check provider: GitHub Actions integration id `15368`;
- required status checks use the strict/up-to-date policy;
- routine merges have no bypass actors;
- `current_user_can_bypass` is `never` in ordinary operation;
- zero approving reviews are required for the single-maintainer workflow;
- unresolved review threads still block merge.

The repository may additionally require coverage, CodeQL and code-quality gates.
Those platform checks complement `ciCheck`; they do not replace or redefine its
checkout-owned test semantics.

## Check-name isolation

The central workflow also receives the historical `create` event used by the
one-attempt proof-carrying showcase authority. A `create` event must never
publish a skipped or authority-specific check under the required merge context.

Therefore `.github/workflows/gradle.yml` deliberately names the job differently
by event:

- ordinary push, pull request and manual CI: `Checkout-local ciCheck`;
- branch-create authority path: `Showcase train-freeze authority v1`.

This distinction is checked from the checkout. It prevents an unrelated branch
creation from replacing a successful required check on the same commit with a
later skipped check of the same name.

The v1 showcase authority is already consumed. Keeping the historical create
adapter does not authorize a rerun; the Java authority contract still rejects a
recreated or repeated authority attempt. A future showcase version requires a
separately reviewed authority contract.

## Remote ruleset audit

GitHub rulesets are mutable platform state and cannot be proven solely from a
plain checkout. After creating or changing the default-branch ruleset, verify
in GitHub that:

1. the required context is exactly `Checkout-local ciCheck`;
2. the provider is GitHub Actions integration `15368`;
3. strict/up-to-date required checks are enabled;
4. no routine repository role or automation identity has an always-bypass;
5. a pending or failed `Checkout-local ciCheck` blocks merge;
6. moving `main` makes an out-of-date pull request update before merge;
7. a green, current pull request can still be merged normally.

Do not add another workflow merely to manufacture a required status name. The
authoritative verification command remains:

```bash
./gradlew --no-configuration-cache ciCheck
```

GitHub Actions is only the platform adapter around that command.
