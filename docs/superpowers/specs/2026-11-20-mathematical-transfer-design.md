# Mathematical transfer into Regelsuche and Java consumers

Status: implementation authorized by the user on 2026-11-20 after the research assessment.

## Goal

Turn the demonstrated Montgomery dot-product transformations and the transferable methods from OpenAI math families 266 and 116 into bounded, reusable mathematical capabilities. Mathematical reasoning belongs in `regelsuche-math-algorithms`; the SDK delegates. Sandbox is responsible for binding a certificate to Java source and observable semantics. Bouncy Castle changes need complete cryptographic regression tests and measured performance.

## Contracts

1. A Montgomery schedule certificate binds odd modulus, radix, signed low-word reduction semantics, input intervals, every intermediate range and an exact congruence. Congruence alone never authorizes an arbitrary Java replacement: the consumer must establish a common canonical observation and preserve exceptions, memory effects and aliases. Intended low-word overflow is distinct from accidental accumulator overflow.
2. Modular linear discovery retains source row identities and pivot columns, combines compatible modular candidates with CRT and reconstructs rationals. Exact verification of the selected rows certifies a substitution; exact verification against every original row plus the free-coordinate identity certifies a complete kernel. These claims remain distinct. A bad prime or exhausted budget cannot certify a stronger claim.
3. The experimental noncommutative identity checker applies only to division-free formula trees over characteristic zero. It uses the family-116 triangular rational operators with explicit tree-size, dimension, arithmetic and coefficient-bit budgets. It does not claim Java, floating-point or fixed-dimensional matrix equivalence.
4. All three capabilities have typed requests/results, cancellation, explicit failure states and independent negative tests. Existing exact solvers and primitive equality checkers retain their behavior.

## Consumer changes

Start Bouncy Castle with accumulated ML-DSA Montgomery products and then ML-KEM vector base products. Preserve the existing reduction/serialization boundary, constant loop structure, and all supported parameter sets. Compare full key generation, encapsulation/decapsulation or signing/verification, recording allocations and environment where available. Choose a measured winning schedule, not merely the fewest reductions.

Sandbox receives a conservative typed adapter and an actual cleanup path where source ranges, reduction shape, array effects and the normalization boundary can be established. Unsupported source shapes fail closed. The integration must compile transformed Java and compare observable outputs. Dependency updates must consume the exact validated Regelsuche revision.

## Scope limits

No claim to resolve the Primachsenraum universal deadline. No implementation of impractical asymptotic algorithms merely because they appear in the reference collection. No silent relaxation of the existing strict Java equivalence gate. General bilinear-rank discovery remains nonlinear and is not solved by the new linear backend.

## Sources

- OpenAI math revision `4eaee87548250b2682affda7d05d947c05554bae`, families 266 and 116.
- Bouncy Castle upstream revision `bb81bfa75c9fa81c7fde467a6314374d22715472`.
- Regelsuche baseline `f10e4c0875e1e76288b2332191bba5b78b2f53dc`.
- Sandbox baseline `18694d92537c1008fcb35f7cddb63b565da3d601`.
