# Third Party Notices

## Embedded GraalPy and SymPy factorization runtime

The optional `regelsuche-math-sympy` module embeds the following pinned runtime
components and Python packages:

- GraalPy / GraalPy Extensions 25.1.3 — Universal Permissive License 1.0;
- SymPy 1.14.0 — BSD 3-Clause (New BSD) License;
- mpmath 1.3.0 — BSD 3-Clause (New BSD) License.

Use: in-process execution of the optional SymPy factorization adapter through a
module-specific GraalPy virtual filesystem. The exact Python dependency closure
is retained in `regelsuche-math-sympy/graalpy.lock`.

The original license and third-party notice files distributed by these
components remain authoritative for their complete copyright and attribution
terms. Their inclusion does not transfer mathematical trust to the backend:
SymPy results remain untrusted proposals until Regelsuche independently
reconstructs the source polynomial.

## SymPy-derived knowledge packs

Source: SymPy project (https://www.sympy.org/)

License: BSD-3-Clause

Use: mathematical identities and reimplemented declarative rewrite patterns in the following knowledge packs:
- `sympy-polynomial-basic` — validated polynomial factoring rules, disabled by default
- `sympy-trig-basic` — candidate trigonometric identity rules, disabled by default
- `sympy-trigonometry-basic` — candidate trigonometric identity rules, disabled by default
- `sympy-log-basic` — candidate logarithmic identity rules, disabled by default
- `sympy-rational-basic` — candidate rational expression rules, disabled by default

Original code is not copied into Regelsuche unless a rule is explicitly marked `TRANSLATED_CODE`. The initial SymPy-derived rules are marked `REIMPLEMENTED_RULE`: they are declarative mathematical identities reviewed for this project, not verbatim SymPy source code.
