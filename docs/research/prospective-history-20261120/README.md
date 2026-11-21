# Original source-only formation history for #750

The bundle preserves the original Git commits that produced the prospective formation matrix. The study locked its code at `4d932cb351`, committed TRAIN and the policy lock at `a68946394b`, and committed the candidate freeze at `37283d9b74`. The public prequalification receipt records 224 rows across 14 sources, eight policies and two catalog settings, with 2,688 retained path labels and 36 null rows. TRAIN accepted zero schemas. Total work is `UNMATCHED`. Qualification and correspondence were unevaluated at this freeze. [Commit and artifact identities](manifest.json)

## Verify and restore the original commits

Run from this repository in Bash with Git and sha256sum. The example creates a fresh directory and fetches the public prerequisite directly from GitHub. The bundle can also be imported into an existing normal clone that has that prerequisite.

```bash
bundle="$PWD/docs/research/prospective-history-20261120/formation-history.bundle"
base=e115dca89bba32bad516d66f408e26f99fbb6149
tip=37283d9b74a73ccca65c8eb89014b3d094463be4
tree=3a7ea1e2381f172e9a8927766b978e20d843fbfb
ref=refs/heads/prospective-formation-20261120
repo=$(mktemp -d)

(
  set -eu
  echo "6e0f667e5182163872a7e2f1f2f3379b4e1077cce724839b4988565bdfe64e2e  $bundle" | sha256sum -c -
  git init --quiet "$repo"
  cd "$repo"
  git fetch --no-tags https://github.com/carstenartur/Regelsuche.git "$base"
  git update-ref refs/heads/public-base "$base"
  if git cat-file -e "$tip^{commit}" 2>/dev/null; then exit 1; fi
  git bundle verify "$bundle"
  git fetch --no-tags "$bundle" "$ref:$ref"
  test "$(git rev-parse "$ref")" = "$tip"
  test "$(git rev-parse "$tip^{tree}")" = "$tree"
  git fsck --full --no-dangling
  git checkout --detach "$tip"
)
```

The preservation check used a fresh, non-shallow repository without object alternates. The public fetch lacked the freeze tip before import. Git verified the bundle, the imported tree matched the original, and full repository fsck succeeded.

## Source-only reproduction

The restored checkout contains the original protocol, implementation, freeze and production launchers. See `docs/research/prospective-representation-20261120/execution.md` in that checkout. Its replay wrapper builds two new normal clones. The Dockerfile builds a third runtime from the original history. Compare each freeze against the original canonical mathematics root in this directory’s manifest. Runtime measurements are separate. On 21 November 2026, hosted run 44871073533 reproduced the original root in two fresh checkouts and the network-disabled container. Each run retained all 224 configurations and 36 null rows. The independent audit verified the row and runtime bindings in artifact 14693674199. Total work remains `UNMATCHED`. The source-only runs ended before qualification. [Hosted source-only audit](hosted-source-replay-audit.json)

## Post-freeze host qualification

After the source-only replay, the original assessor qualified the committed freeze in two fresh checkouts on the same host. Each checkout built its production classes independently and replayed the whole source-only matrix before reading the committed qualification input. All 227 output files matched byte for byte. The 224 rows repeat 14 sources under 16 configurations. Among 160 positive source–configuration rows, 122 showed multi-step rediscovery and 108 ranked a hit in the top five. The 64 control rows had zero false-positive multi-step rediscoveries. TRAIN accepted zero schemas. Total work remains `UNMATCHED` and policy superiority is `NOT_ESTABLISHED`.

The third qualification run in the pinned offline container is pending. The completed container replay above covers source-only formation. [Aggregate results, input commitments and reproduction hashes](post-freeze-host-qualification.json)
