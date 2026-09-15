# Development references for scoring revision v2

These two references retain the actual deterministic results from Java 25 CI
run 34882256190, commit e67250101d66eed361b9ae4f2558cb5a2c5efc14.
The previous references remain unchanged in the parent directory. The accompanying
provenance manifest lists every difference: only content hashes change because
replay now binds the scoring revision. All inputs, results, costs, negative
outcomes, profiles and learning work are identical, including embedded JSON.
The existing tests still compare the complete new canonical output, verify paths
and negative controls, and compare the unchanged Markdown. This is not a new
performance baseline, a new holdout, or a retroactive relabelling of old evidence.
