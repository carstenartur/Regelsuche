# Rule Validation

`RuleValidationService` validates a loaded knowledge-pack rule with explicit examples, deterministic generated instantiations, and counterexamples.

The rule quality dashboard renders rule, status, example counts, counterexample counts, and pass rate. CI gates fail when validated rules miss examples, include counterexamples, or miss provenance, status, or license metadata.
