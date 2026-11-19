# Captured native Z3 proof objects

These four objects were captured on 2026-11-19 from `Z3 version 4.8.12 - 64 bit`
using its default configuration and `z3 -in -smt2`. The executable came from
the Linux x86-64 wheel `z3_solver-4.8.12.0-py2.py3-none-manylinux1_x86_64.whl`
(SHA-256 `ffdbb89bc30befcef9b5cfb118d690b452320dccd44a6ae2f7b81caa369de042`).
The extracted executable's SHA-256 was
`5e07d54b632292a7bcc8528a82903323f7d74bd98844dea459ecaaebc4291b3a`.
No alternate solver tactic was selected for these files.

| File | Real declarations, in order | Premise | Goal | Expected independent replay |
| --- | --- | --- | --- | --- |
| [distributivity-4.8.12.txt](distributivity-4.8.12.txt) | `x`, `y`, `z` | None | `x*(y+z) = x*y+x*z` | Accept |
| [add-zero-4.8.12.txt](add-zero-4.8.12.txt) | `x` | None | `x+0 = x` | Accept |
| [decimal-collection-4.8.12.txt](decimal-collection-4.8.12.txt) | `x` | None | `0.5*x+0.25*x = 0.75*x` | Accept |
| [premise-eliminated-4.8.12.txt](premise-eliminated-4.8.12.txt) | `x`, `y`, `z` | `x = y` | `x*z = y*z` | Reject |

## Capture protocol

Each request enabled proof production, selected logic `ALL`, declared the
listed real constants, asserted its premise if present, asserted the negated
goal, then called `check-sat` and `get-proof`. For example:

```sh
z3 -in -smt2 <<'SMT'
(set-option :produce-proofs true)
(set-logic ALL)
(declare-const x Real)
(declare-const y Real)
(declare-const z Real)
(assert (not (= (* x (+ y z)) (+ (* x y) (* x z)))))
(check-sat)
(get-proof)
SMT
```

The remaining assertion blocks were:

```smt2
; add-zero
(assert (not (= (+ x 0) x)))

; decimal-collection
(assert (not (= (+ (* 0.5 x) (* 0.25 x)) (* 0.75 x))))

; premise-eliminated
(assert (= x y))
(assert (not (= (* x z) (* y z))))
```

All four invocations exited successfully and returned `unsat`. The files
retain the proof payload after the leading `unsat` line, with blank lines
removed and each remaining line trimmed and joined by `\n`, without a final
newline. This is the existing `Z3SmtSolverBackend.payloadAfterStatus` convention;
no proof rule or term was edited. Other Z3 versions may choose different proof
nodes or local binding names.

## What the objects establish

The positive objects exercise native term/formula/proof bindings, polynomial
`rewrite`, real and Boolean congruence, transitivity and `mp`. Integer coercion
appears as `to_real`; decimal coefficients appear as exact constant quotients.
The conditional object instead contains `rewrite (= (* x z) (* y z))` without
an asserted `x = y` dependency. Its rejection checks that external `unsat`
and a native rule label cannot authorize an unconditional polynomial identity
or invent a missing premise.

The [focused replay tests](../../java/de/regelsuche/solver/portfolio/Z3EqualityProofReplayTest.java)
use these captures and explicit tampering controls. The
[backend tests](../../java/de/regelsuche/solver/portfolio/Z3SmtSolverBackendTest.java)
also obtain fresh genuine objects through the retained-attempt API. See the
[proof bridge contract](../../../../../docs/proof-bridge.md#optional-independent-polynomial-proof-replay)
for bounds, unsupported proof rules and reproduction commands. These are
public development controls, not an independent held-out study or a solver
performance comparison.
