# Native scalar AC e-matching slice

Source audit at `1388aeec853b60c0e9f9b1a47fc5781693aad570` confirms that
`ENode.ExprValueAdapter` emits binary chains, `EGraphPatternMatcher` matches
ordered children, and `EqualitySaturation` has no proof DAG. The existing
independent exact authority available without a dependency cycle is core's
final `PolynomialNormalizer` (exact rationals and bounded expansion/coefficients).

1. Add a versioned compiled scalar-polynomial matching plan and native matcher
   over actual e-classes/e-nodes. Flatten AC alternatives into multiplicity-aware
   multisets; join repeated placeholders by canonical e-class identity. Root AC
   submultisets preserve their unmatched context. Placeholders bind one atomic
   e-class, not arbitrary groups. Ordered operators retain positions.
2. Add a separately invoked consumer at `EqualitySaturation.saturateNativeAc`.
   It owns a fresh graph, declares the scalar Q-polynomial fragment, excludes
   guarded/custom/unsupported rules, and executes independent exact normal-form
   equality before every new union and again for final extraction. Retain all
   exclusions, checks, matching completeness and cumulative logical work.
3. Differentially test the complete finite reference space using independent
   brute-force operand permutations, repeated variables and multiplicities;
   test non-AC order, assumption retention, budgets/cycles, false rules, actual
   new reach and rejected extraction/normalization outcomes.
4. Document contracts, retained v1/default identities, NOT_PRODUCED formal DAG,
   unmeasured normalizer internals, later n-ary storage/general grouping and
   study/reproduction limits. Focused Java 25 Maven only, then reviewable commit.

Root explicitly accepted this bounded source correction. No old proof path is
assumed. No default change, unchecked union, API write, Gradle, browser, study,
held-out inspection or peer worktree edit is authorized here. #661's arena work
does not edit EGraph/ENode; #696 matching/cursor files are outside this slice.
