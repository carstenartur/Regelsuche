# Held-out search-policy evidence

The experiment in `HeldOutSearchPolicyExperimentTest` treats the five merged
hidden-rule runtime tasks as frozen primitive-search problems.

- Runtime searches finish before split/family labels are attached.
- Hidden rule IDs and generalized templates are never policy inputs.
- Only `TRAIN` trajectories contribute model statistics or model identity.
- Validation and test families are evaluated with identical primitive engines,
  targets, and budgets for static, frequency, linear, and experience variants.
- A negative result is retained when all held-out rules fall back because the model
  has no rule-structural transfer features.
- This evidence is not a Gallery claim.
