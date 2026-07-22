#!/usr/bin/env python3
"""Run the independent rational verifier with the public Java polynomial text format."""

from __future__ import annotations

from fractions import Fraction
from functools import cmp_to_key
import importlib.util
from pathlib import Path
import sys

MODULE_PATH = Path(__file__).with_name(
    "verify-candidate-independent-rational-assumption-adapter.py"
)
SPEC = importlib.util.spec_from_file_location(
    "candidate_independent_rational_verifier", MODULE_PATH
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load verifier module: {MODULE_PATH}")
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)

Monomial = tuple[tuple[str, int], ...]
Polynomial = dict[Monomial, Fraction]


def exponent_of(monomial: Monomial, variable: str) -> int:
    return dict(monomial).get(variable, 0)


def monomial_key(monomial: Monomial) -> str:
    return "*".join(
        variable if exponent == 1 else f"{variable}^{exponent}"
        for variable, exponent in sorted(monomial)
    )


def compare_monomials(left: Monomial, right: Monomial) -> int:
    left_degree = sum(exponent for _, exponent in left)
    right_degree = sum(exponent for _, exponent in right)
    if left_degree != right_degree:
        return -1 if left_degree > right_degree else 1
    variables = sorted(
        {variable for variable, _ in left}
        | {variable for variable, _ in right},
        reverse=True,
    )
    for variable in variables:
        left_exponent = exponent_of(left, variable)
        right_exponent = exponent_of(right, variable)
        if left_exponent != right_exponent:
            return -1 if left_exponent < right_exponent else 1
    left_key = monomial_key(left)
    right_key = monomial_key(right)
    return (left_key > right_key) - (left_key < right_key)


def ordered_terms(value: Polynomial) -> list[tuple[Monomial, Fraction]]:
    return sorted(
        VERIFIER.poly_clean(value).items(),
        key=cmp_to_key(
            lambda left, right: compare_monomials(left[0], right[0])
        ),
    )


def rational_text(value: Fraction) -> str:
    return (
        str(value.numerator)
        if value.denominator == 1
        else f"{value.numerator}/{value.denominator}"
    )


def polynomial_text(value: Polynomial) -> str:
    terms = ordered_terms(value)
    if not terms:
        return "0"
    parts: list[str] = []
    for monomial, coefficient in terms:
        negative = coefficient < 0
        absolute = abs(coefficient)
        key = monomial_key(monomial)
        write_coefficient = not key or absolute != 1
        body = rational_text(absolute) if write_coefficient else ""
        if key:
            if write_coefficient:
                body += "*"
            body += key
        if not parts:
            parts.append(("-" if negative else "") + body)
        else:
            parts.append((" - " if negative else " + ") + body)
    return "".join(parts)


def monic_text(value: Polynomial) -> str:
    terms = ordered_terms(value)
    if not terms:
        VERIFIER.fail("zero polynomial cannot be a nonzero factor")
    leading = terms[0][1]
    return polynomial_text(
        {
            monomial: coefficient / leading
            for monomial, coefficient in terms
        }
    )


# Python resolves these globals when the imported verifier functions execute.
# The exact arithmetic and search implementation remain the independent module's
# own implementation; only its public text rendering is aligned with the Java
# artifact contract.
VERIFIER.ordered_terms = ordered_terms
VERIFIER.poly_key = polynomial_text
VERIFIER.monic_key = monic_text

_original_validate_resource_use = VERIFIER.validate_resource_use


def validate_resource_use(value: object, context: str) -> tuple[int, int]:
    executed = _original_validate_resource_use(value, context)
    if not isinstance(value, dict):
        VERIFIER.fail(f"{context} resource use is not an object")
    if context.startswith("task ") or "/" in context:
        if value.get("configuredStates") != 245:
            VERIFIER.fail(f"{context} does not use the fixed 245-state contract")
        if value.get("configuredCandidateEvaluations") != 45:
            VERIFIER.fail(
                f"{context} does not use the fixed 45-evaluation contract"
            )
    elif context.startswith("campaign "):
        if value.get("configuredStates") != 3000:
            VERIFIER.fail(f"{context} does not retain the frozen state budget")
        if value.get("configuredCandidateEvaluations") != 600:
            VERIFIER.fail(
                f"{context} does not retain the frozen candidate budget"
            )
    return executed


VERIFIER.validate_resource_use = validate_resource_use

_original_validate_task_evaluation = VERIFIER.validate_task_evaluation


def validate_task_evaluation(
    evaluation: dict[str, object],
    case: dict[str, object],
    task: dict[str, object],
) -> str:
    outcome = _original_validate_task_evaluation(evaluation, case, task)
    assumptions = task.get("assumptions")
    if not isinstance(assumptions, list):
        VERIFIER.fail(f"task {task.get('taskId')} assumptions are missing")
    task_provided, _, task_unsupported = VERIFIER.audit_assumptions(
        assumptions, []
    )
    if task_unsupported:
        VERIFIER.fail(
            f"task {task.get('taskId')} has unsupported assumptions"
        )
    for step in evaluation.get("steps", []):
        if not isinstance(step, dict):
            VERIFIER.fail("search step is not an object")
        generated = step.get("generatedAssumptions")
        if not isinstance(generated, list):
            VERIFIER.fail("search-step generated assumptions are missing")
        if step.get("ruleId") == VERIFIER.OPERATOR_ID:
            if not generated:
                VERIFIER.fail(
                    f"selected cancellation step lost side conditions in "
                    f"{task.get('taskId')}"
                )
            generated_provided, _, generated_unsupported = (
                VERIFIER.audit_assumptions(generated, [])
            )
            if generated_unsupported:
                VERIFIER.fail(
                    f"generated side condition is unsupported in "
                    f"{task.get('taskId')}"
                )
            if not set(generated_provided).issubset(set(task_provided)):
                VERIFIER.fail(
                    f"generated side condition is not covered by frozen task "
                    f"assumptions in {task.get('taskId')}"
                )
        elif generated:
            VERIFIER.fail(
                f"assumption-free primitive emitted side conditions in "
                f"{task.get('taskId')}"
            )
    return outcome


VERIFIER.validate_task_evaluation = validate_task_evaluation

_original_validate_run = VERIFIER.validate_run


def validate_run(*args: object, **kwargs: object) -> tuple[int, int]:
    result = _original_validate_run(*args, **kwargs)
    run = args[0]
    if not isinstance(run, dict):
        VERIFIER.fail("run is not an object")
    for campaign in run.get("campaigns", []):
        if not isinstance(campaign, dict):
            VERIFIER.fail("campaign is not an object")
        task_states = 0
        task_candidates = 0
        for evaluation in campaign.get("taskEvaluations", []):
            if not isinstance(evaluation, dict):
                VERIFIER.fail("task evaluation is not an object")
            resource = evaluation.get("resourceUse")
            if not isinstance(resource, dict):
                VERIFIER.fail("task resource use is not an object")
            task_states += resource.get("executedStates", 0)
            task_candidates += resource.get(
                "executedCandidateEvaluations", 0
            )
        campaign_resource = campaign.get("resourceUse")
        if not isinstance(campaign_resource, dict):
            VERIFIER.fail("campaign resource use is not an object")
        formation_states = (
            campaign_resource.get("executedStates", 0) - task_states
        )
        formation_candidates = (
            campaign_resource.get("executedCandidateEvaluations", 0)
            - task_candidates
        )
        if not 0 <= formation_states <= 60:
            VERIFIER.fail(
                f"formation state use is outside the fixed 60-state contract: "
                f"{formation_states}"
            )
        if not 0 <= formation_candidates <= 60:
            VERIFIER.fail(
                f"formation candidate use is outside the fixed 60-evaluation "
                f"contract: {formation_candidates}"
            )
    return result


VERIFIER.validate_run = validate_run

raise SystemExit(VERIFIER.main())
