# Auditable shared modular powers

The approved goal is to reproduce useful transformations such as bcgit/bc-java#2455,
not compiler constant folding, and to explain them to an upstream reviewer.

This first implementation slice removes redundant unit powers under a matching
modular product and exposes a structured, independently reverified derivation.
An unreduced base is admissible as a factor because the parent reduces it; it
must never replace a normalized standalone output. Java dispatch, magnitude,
exception and floating-point obligations remain unchanged.

Explanations contain original outputs, the actual replacement DAG and shared
values, immutable assumptions, safety policy, proof receipt and operation counts.
They cannot be created successfully from an unverified or stale candidate.
Sandbox renders these terms using actual Java names, optionally as short comments.
No free-form text is taken as proof; no benchmark or constant-time claims are made.

Next slices (not claimed complete by this change): extracting constructor field
relationships; proving odd-part helper-method contracts; distinguishing conditional
review proposals from automatically applicable edits; a separately profiled SWT kernel.
The full historical Bouncy Castle constructor must remain an end-to-end acceptance
case, not be replaced with an artificially simplified or name-matched input.
