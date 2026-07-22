#!/usr/bin/env python3
"""Independently verify the frozen rational-assumption benchmark adapter."""

from __future__ import annotations

import argparse
import copy
from collections import deque
from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
from typing import Any, Callable, Iterable

import jsonschema

RUN_SCHEMA = "regelsuche.candidate-independent-rational-assumption-adapter-run/v1"
VERIFY_SCHEMA = "regelsuche.candidate-independent-rational-assumption-adapter-verification/v1"
BENCHMARK_ID = "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
CHALLENGE = "rational-assumption-rewrites"
PROFILE_ID = "rational-assumption-primitives/v1"
ADAPTER_STATUS = "POST_FREEZE_ASSUMPTION_AWARE_CANCELLATION_EXECUTION"
CANDIDATE_FORM = "ASSUMPTION_SENSITIVE_FACTOR_CANCELLATION"
OPERATOR_ID = "hypothesis_rational_normalization"
ALLOWED_RULES = ["ast_divide_one", "ast_square_difference_factor", OPERATOR_ID]
EXPECTED_CASES = [f"case-{index:02d}" for index in range(1, 7)]
EXPECTED_TASKS = [
    f"case-{case_index:02d}-task-{task_index}"
    for case_index in range(1, 7)
    for task_index in range(1, 3)
]
EXPECTED_CAMPAIGNS = [f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)]
TRAIN_CASES = ["case-01", "case-02"]
TRAIN_SEEDS = [
    "case-01-seed-1",
    "case-01-seed-2",
    "case-02-seed-1",
    "case-02-seed-2",
]
MAX_DEPTH = 5


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular non-symbolic JSON file: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"expected JSON object in {path}")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def exact_hash(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def require_hash(value: dict[str, Any], context: str) -> None:
    retained = value.get("contentHash")
    if not isinstance(retained, str):
        fail(f"{context} has no contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = semantic_hash(material)
    if retained != expected:
        fail(f"{context} contentHash mismatch: {retained} != {expected}")


def require_nested_hashes(run: dict[str, Any]) -> None:
    campaigns = run.get("campaigns")
    if not isinstance(campaigns, list):
        fail("campaigns is not an array")
    for campaign_index, campaign in enumerate(campaigns):
        if not isinstance(campaign, dict):
            fail(f"campaign {campaign_index} is not an object")
        candidate = campaign.get("candidate")
        if not isinstance(candidate, dict):
            fail(f"campaign {campaign_index} candidate is missing")
        require_hash(candidate, f"campaign {campaign_index} candidate")
        formation = campaign.get("formationEvidence")
        if not isinstance(formation, list):
            fail(f"campaign {campaign_index} formation evidence is missing")
        for item_index, item in enumerate(formation):
            if not isinstance(item, dict):
                fail(f"campaign {campaign_index} formation {item_index} is not an object")
            require_hash(item, f"campaign {campaign_index} formation {item_index}")
        evaluations = campaign.get("taskEvaluations")
        if not isinstance(evaluations, list):
            fail(f"campaign {campaign_index} task evaluations are missing")
        for evaluation_index, evaluation in enumerate(evaluations):
            if not isinstance(evaluation, dict):
                fail(f"campaign {campaign_index} evaluation {evaluation_index} is not an object")
            steps = evaluation.get("steps")
            if not isinstance(steps, list):
                fail(f"campaign {campaign_index} evaluation {evaluation_index} steps are missing")
            for step_index, step in enumerate(steps):
                if not isinstance(step, dict):
                    fail("search step is not an object")
                require_hash(step, f"campaign {campaign_index} evaluation {evaluation_index} step {step_index}")
            require_hash(evaluation, f"campaign {campaign_index} evaluation {evaluation_index}")
        require_hash(campaign, f"campaign {campaign_index}")
    require_hash(run, "rational adapter run")


def index_by(items: Any, field: str, context: str) -> dict[str, dict[str, Any]]:
    if not isinstance(items, list):
        fail(f"{context} is not an array")
    result: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            fail(f"{context} item {index} is not an object")
        identity = item.get(field)
        if not isinstance(identity, str) or not identity:
            fail(f"{context} item {index} has no {field}")
        if identity in result:
            fail(f"duplicate {context} identity {identity}")
        result[identity] = item
    return result


# ---------------------------------------------------------------------------
# Independent expression parser
# ---------------------------------------------------------------------------

Token = tuple[str, str]
Ast = tuple[Any, ...]

TOKEN_PATTERN = re.compile(
    r"\s*(?:(?P<number>[0-9]+(?:\.[0-9]+)?)|(?P<ident>[A-Za-z_][A-Za-z0-9_]*)|(?P<op>[+\-*/^(),]))"
)


class Parser:
    def __init__(self, source: str):
        self.source = source
        self.tokens: list[Token] = []
        position = 0
        while position < len(source):
            match = TOKEN_PATTERN.match(source, position)
            if not match:
                fail(f"unsupported expression syntax near {source[position:]!r}")
            if match.lastgroup is None:
                fail("tokenizer returned no group")
            self.tokens.append((match.lastgroup, match.group(match.lastgroup)))
            position = match.end()
        self.tokens.append(("eof", ""))
        self.index = 0

    def parse(self) -> Ast:
        value = self.parse_expression()
        if self.peek()[0] != "eof":
            fail(f"unexpected token {self.peek()[1]!r} in {self.source!r}")
        return value

    def peek(self) -> Token:
        return self.tokens[self.index]

    def take(self) -> Token:
        value = self.peek()
        self.index += 1
        return value

    def accept(self, text: str) -> bool:
        if self.peek()[1] == text:
            self.index += 1
            return True
        return False

    def expect(self, text: str) -> None:
        if not self.accept(text):
            fail(f"expected {text!r} in {self.source!r}")

    def parse_expression(self) -> Ast:
        value = self.parse_term()
        while self.peek()[1] in ("+", "-"):
            operator = self.take()[1]
            value = (operator, value, self.parse_term())
        return value

    def parse_term(self) -> Ast:
        value = self.parse_power()
        while self.peek()[1] in ("*", "/"):
            operator = self.take()[1]
            value = (operator, value, self.parse_power())
        return value

    def parse_power(self) -> Ast:
        value = self.parse_unary()
        if self.accept("^"):
            value = ("^", value, self.parse_power())
        return value

    def parse_unary(self) -> Ast:
        if self.accept("-"):
            return ("neg", self.parse_unary())
        return self.parse_primary()

    def parse_primary(self) -> Ast:
        kind, text = self.take()
        if kind == "number":
            return ("num", Fraction(text))
        if kind == "ident":
            if self.accept("("):
                arguments: list[Ast] = []
                if not self.accept(")"):
                    while True:
                        arguments.append(self.parse_expression())
                        if self.accept(")"):
                            break
                        self.expect(",")
                return ("func", text, tuple(arguments))
            return ("var", text)
        if text == "(":
            value = self.parse_expression()
            self.expect(")")
            return value
        fail(f"unexpected token {text!r} in {self.source!r}")


def parse_expression(source: str) -> Ast:
    return Parser(source).parse()


def ast_key(value: Ast) -> str:
    kind = value[0]
    if kind == "num":
        number: Fraction = value[1]
        return f"n:{number.numerator}/{number.denominator}"
    if kind == "var":
        return f"v:{value[1]}"
    if kind == "neg":
        return f"neg({ast_key(value[1])})"
    if kind == "func":
        return f"f:{value[1]}({','.join(ast_key(item) for item in value[2])})"
    return f"{kind}({ast_key(value[1])},{ast_key(value[2])})"


def node_count(value: Ast) -> int:
    if value[0] in ("num", "var"):
        return 1
    if value[0] == "neg":
        return 1 + node_count(value[1])
    if value[0] == "func":
        return 1 + sum(node_count(item) for item in value[2])
    return 1 + node_count(value[1]) + node_count(value[2])


# ---------------------------------------------------------------------------
# Exact polynomial / rational-function arithmetic
# ---------------------------------------------------------------------------

Monomial = tuple[tuple[str, int], ...]
Polynomial = dict[Monomial, Fraction]


def poly_clean(value: Polynomial) -> Polynomial:
    return {monomial: coefficient for monomial, coefficient in value.items() if coefficient != 0}


def poly_constant(value: Fraction) -> Polynomial:
    return {} if value == 0 else {(): value}


def poly_variable(name: str) -> Polynomial:
    return {((name, 1),): Fraction(1)}


def poly_add(left: Polynomial, right: Polynomial) -> Polynomial:
    result = dict(left)
    for monomial, coefficient in right.items():
        result[monomial] = result.get(monomial, Fraction(0)) + coefficient
    return poly_clean(result)


def poly_negate(value: Polynomial) -> Polynomial:
    return {monomial: -coefficient for monomial, coefficient in value.items()}


def poly_subtract(left: Polynomial, right: Polynomial) -> Polynomial:
    return poly_add(left, poly_negate(right))


def multiply_monomials(left: Monomial, right: Monomial) -> Monomial:
    powers: dict[str, int] = {}
    for variable, exponent in left + right:
        powers[variable] = powers.get(variable, 0) + exponent
    return tuple(sorted((variable, exponent) for variable, exponent in powers.items() if exponent))


def poly_multiply(left: Polynomial, right: Polynomial) -> Polynomial:
    result: Polynomial = {}
    for left_monomial, left_coefficient in left.items():
        for right_monomial, right_coefficient in right.items():
            monomial = multiply_monomials(left_monomial, right_monomial)
            result[monomial] = result.get(monomial, Fraction(0)) + left_coefficient * right_coefficient
    return poly_clean(result)


def poly_pow(value: Polynomial, exponent: int) -> Polynomial:
    if exponent < 0:
        fail("negative polynomial exponent")
    result = poly_constant(Fraction(1))
    base = value
    remaining = exponent
    while remaining:
        if remaining & 1:
            result = poly_multiply(result, base)
        remaining //= 2
        if remaining:
            base = poly_multiply(base, base)
    return result


def monomial_degree(monomial: Monomial) -> int:
    return sum(exponent for _, exponent in monomial)


def ordered_terms(value: Polynomial) -> list[tuple[Monomial, Fraction]]:
    return sorted(
        value.items(),
        key=lambda item: (
            -monomial_degree(item[0]),
            tuple((variable, -exponent) for variable, exponent in item[0]),
        ),
    )


def poly_key(value: Polynomial) -> str:
    value = poly_clean(value)
    if not value:
        return "0"
    return ";".join(
        f"{coefficient.numerator}/{coefficient.denominator}:"
        + "*".join(f"{variable}^{exponent}" for variable, exponent in monomial)
        for monomial, coefficient in ordered_terms(value)
    )


def monic_key(value: Polynomial) -> str:
    terms = ordered_terms(poly_clean(value))
    if not terms:
        fail("zero polynomial cannot be a nonzero factor")
    leading = terms[0][1]
    return poly_key({monomial: coefficient / leading for monomial, coefficient in terms})


@dataclass(frozen=True)
class RationalFunction:
    numerator: Polynomial
    denominator: Polynomial
    denominator_factors: tuple[Polynomial, ...]


def rational_from_ast(value: Ast) -> RationalFunction:
    kind = value[0]
    if kind == "num":
        return RationalFunction(poly_constant(value[1]), poly_constant(Fraction(1)), ())
    if kind == "var":
        return RationalFunction(poly_variable(value[1]), poly_constant(Fraction(1)), ())
    if kind == "func":
        fail(f"unsupported function in rational verifier: {value[1]}")
    if kind == "neg":
        inner = rational_from_ast(value[1])
        return RationalFunction(poly_negate(inner.numerator), inner.denominator, inner.denominator_factors)
    if kind == "^":
        base = rational_from_ast(value[1])
        exponent_ast = value[2]
        if exponent_ast[0] != "num" or exponent_ast[1].denominator != 1:
            fail("non-integral exponent in rational verifier")
        exponent = exponent_ast[1].numerator
        if exponent < 0 or exponent > 12:
            fail("exponent outside verifier bound")
        factors = base.denominator_factors * exponent
        return RationalFunction(poly_pow(base.numerator, exponent), poly_pow(base.denominator, exponent), factors)
    left = rational_from_ast(value[1])
    right = rational_from_ast(value[2])
    if kind == "+":
        return RationalFunction(
            poly_add(poly_multiply(left.numerator, right.denominator), poly_multiply(right.numerator, left.denominator)),
            poly_multiply(left.denominator, right.denominator),
            left.denominator_factors + right.denominator_factors,
        )
    if kind == "-":
        return RationalFunction(
            poly_subtract(poly_multiply(left.numerator, right.denominator), poly_multiply(right.numerator, left.denominator)),
            poly_multiply(left.denominator, right.denominator),
            left.denominator_factors + right.denominator_factors,
        )
    if kind == "*":
        return RationalFunction(
            poly_multiply(left.numerator, right.numerator),
            poly_multiply(left.denominator, right.denominator),
            left.denominator_factors + right.denominator_factors,
        )
    if kind == "/":
        if not right.numerator:
            fail("division by identically zero rational expression")
        factors = left.denominator_factors + right.denominator_factors + tuple(nonzero_value_factors(value[2]))
        return RationalFunction(
            poly_multiply(left.numerator, right.denominator),
            poly_multiply(left.denominator, right.numerator),
            factors,
        )
    fail(f"unsupported AST operator {kind}")


def nonzero_value_factors(value: Ast) -> list[Polynomial]:
    kind = value[0]
    if kind == "num":
        return [poly_constant(value[1])] if value[1] == 0 else []
    if kind == "*":
        return nonzero_value_factors(value[1]) + nonzero_value_factors(value[2])
    if kind == "/":
        return nonzero_value_factors(value[1])
    if kind == "^":
        exponent_ast = value[2]
        if exponent_ast[0] != "num" or exponent_ast[1].denominator != 1:
            fail("non-integral exponent in denominator factor")
        return [] if exponent_ast[1].numerator == 0 else nonzero_value_factors(value[1])
    rational = rational_from_ast(value)
    if not rational.numerator:
        return [rational.numerator]
    return [] if all(monomial == () for monomial in rational.numerator) else [rational.numerator]


def required_factor_keys(*expressions: Ast) -> list[str]:
    factors: set[str] = set()
    for expression in expressions:
        rational = rational_from_ast(expression)
        for factor in rational.denominator_factors:
            if not factor:
                fail("identically zero denominator factor")
            if any(monomial != () for monomial in factor):
                factors.add(monic_key(factor))
    return sorted(factors)


def parse_assumption(assumption: str) -> Polynomial | None:
    if assumption.count("!=") != 1:
        return None
    left_text, right_text = (part.strip() for part in assumption.split("!=", 1))
    if not left_text or not right_text:
        return None
    left = rational_from_ast(parse_expression(left_text))
    right = rational_from_ast(parse_expression(right_text))
    if left.denominator != poly_constant(Fraction(1)) or right.denominator != poly_constant(Fraction(1)):
        return None
    difference = poly_subtract(left.numerator, right.numerator)
    return difference or None


def audit_assumptions(assumptions: list[str], required: list[str]) -> tuple[list[str], list[str], list[str]]:
    provided: set[str] = set()
    unsupported: list[str] = []
    for assumption in sorted(set(assumptions)):
        try:
            factor = parse_assumption(assumption)
        except VerificationError:
            factor = None
        if factor is None:
            unsupported.append(assumption)
        elif any(monomial != () for monomial in factor):
            provided.add(monic_key(factor))
    return sorted(provided), [item for item in required if item not in provided], unsupported


def confirm_equivalence(left_text: str, right_text: str, assumptions: list[str]) -> tuple[str, str, list[str], list[str]]:
    left_ast = parse_expression(left_text)
    right_ast = parse_expression(right_text)
    left = rational_from_ast(left_ast)
    right = rational_from_ast(right_ast)
    required = required_factor_keys(left_ast, right_ast)
    provided, missing, unsupported = audit_assumptions(assumptions, required)
    if unsupported:
        fail(f"unsupported assumptions {unsupported} for {left_text} -> {right_text}")
    if missing:
        fail(f"missing assumptions {missing} for {left_text} -> {right_text}")
    cross_left = poly_multiply(left.numerator, right.denominator)
    cross_right = poly_multiply(right.numerator, left.denominator)
    if cross_left != cross_right:
        fail(f"rational identity is false: {left_text} -> {right_text}")
    return poly_key(cross_left), poly_key(cross_right), required, provided


# ---------------------------------------------------------------------------
# Independent bounded rewrite search
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Transition:
    rule_id: str
    source: Ast
    target: Ast


def flatten_multiply(value: Ast) -> list[Ast]:
    if value[0] == "*":
        return flatten_multiply(value[1]) + flatten_multiply(value[2])
    return [value]


def build_product(factors: list[Ast]) -> Ast:
    if not factors:
        return ("num", Fraction(1))
    value = factors[0]
    for factor in factors[1:]:
        value = ("*", value, factor)
    return value


def cancel_root(value: Ast) -> Ast | None:
    if value[0] != "/":
        return None
    numerator = flatten_multiply(value[1])
    denominator = flatten_multiply(value[2])
    right_keys = {ast_key(factor): factor for factor in denominator if factor[0] != "num"}
    common: Ast | None = None
    for factor in numerator:
        if factor[0] != "num" and ast_key(factor) in right_keys:
            common = factor
            break
    if common is None:
        return None
    common_key = ast_key(common)
    removed = False
    remaining_numerator: list[Ast] = []
    for factor in numerator:
        if not removed and ast_key(factor) == common_key:
            removed = True
        else:
            remaining_numerator.append(factor)
    removed = False
    remaining_denominator: list[Ast] = []
    for factor in denominator:
        if not removed and ast_key(factor) == common_key:
            removed = True
        else:
            remaining_denominator.append(factor)
    return ("/", build_product(remaining_numerator), build_product(remaining_denominator))


def rewrite_at_root(value: Ast) -> list[tuple[str, Ast]]:
    result: list[tuple[str, Ast]] = []
    if value[0] == "/" and value[2] == ("num", Fraction(1)):
        result.append(("ast_divide_one", value[1]))
    if (
        value[0] == "-"
        and value[1][0] == "^"
        and value[2][0] == "^"
        and value[1][2] == ("num", Fraction(2))
        and value[2][2] == ("num", Fraction(2))
    ):
        left_base = value[1][1]
        right_base = value[2][1]
        result.append(
            (
                "ast_square_difference_factor",
                ("*", ("-", left_base, right_base), ("+", left_base, right_base)),
            )
        )
    cancelled = cancel_root(value)
    if cancelled is not None and cancelled != value:
        result.append((OPERATOR_ID, cancelled))
    return result


def base_rewrites_everywhere(value: Ast) -> list[Transition]:
    result: list[Transition] = []
    for rule_id, target in rewrite_at_root(value):
        if rule_id != OPERATOR_ID:
            result.append(Transition(rule_id, value, target))
    kind = value[0]
    if kind in ("+", "-", "*", "/", "^"):
        for child_index in (1, 2):
            child = value[child_index]
            for transition in base_rewrites_everywhere(child):
                rebuilt = list(value)
                rebuilt[child_index] = transition.target
                result.append(Transition(transition.rule_id, value, tuple(rebuilt)))
    elif kind == "neg":
        for transition in base_rewrites_everywhere(value[1]):
            result.append(Transition(transition.rule_id, value, ("neg", transition.target)))
    return result


def all_rewrites(value: Ast) -> list[Transition]:
    result = base_rewrites_everywhere(value)
    cancelled = cancel_root(value)
    if cancelled is not None and cancelled != value:
        result.append(Transition(OPERATOR_ID, value, cancelled))
    unique: dict[tuple[str, str], Transition] = {}
    for transition in result:
        unique[(transition.rule_id, ast_key(transition.target))] = transition
    return sorted(unique.values(), key=lambda item: (item.rule_id, ast_key(item.target)))


def reachable(source_text: str, target_text: str, assumptions: list[str]) -> tuple[bool, list[str]]:
    source = parse_expression(source_text)
    target = parse_expression(target_text)
    target_key = rational_canonical_key(target)
    queue: deque[tuple[Ast, int, list[str]]] = deque([(source, 0, [])])
    visited = {ast_key(source)}
    while queue:
        current, depth, path = queue.popleft()
        if rational_canonical_key(current) == target_key and current_equivalent_to_target(current, target, assumptions):
            return True, path
        if depth >= MAX_DEPTH:
            continue
        for transition in all_rewrites(current):
            confirm_equivalence_ast(current, transition.target, assumptions)
            key = ast_key(transition.target)
            if key in visited:
                continue
            visited.add(key)
            queue.append((transition.target, depth + 1, path + [transition.rule_id]))
    return False, []


def rational_canonical_key(value: Ast) -> str:
    rational = rational_from_ast(value)
    return f"{poly_key(rational.numerator)}//{poly_key(rational.denominator)}"


def current_equivalent_to_target(current: Ast, target: Ast, assumptions: list[str]) -> bool:
    try:
        confirm_equivalence_ast(current, target, assumptions)
        return True
    except VerificationError:
        return False


def confirm_equivalence_ast(left_ast: Ast, right_ast: Ast, assumptions: list[str]) -> None:
    left = rational_from_ast(left_ast)
    right = rational_from_ast(right_ast)
    required = required_factor_keys(left_ast, right_ast)
    _, missing, unsupported = audit_assumptions(assumptions, required)
    if missing or unsupported:
        fail(f"rewrite has incomplete assumptions: missing={missing} unsupported={unsupported}")
    if poly_multiply(left.numerator, right.denominator) != poly_multiply(right.numerator, left.denominator):
        fail("rewrite step is not rationally equivalent")


# ---------------------------------------------------------------------------
# Artifact verification
# ---------------------------------------------------------------------------

def configured_seed(benchmark_id: str, campaign_id: str, index: int) -> str:
    return semantic_hash({
        "benchmarkId": benchmark_id,
        "campaignId": campaign_id,
        "challengeId": CHALLENGE,
        "index": index,
    })


def validate_frozen_inputs(
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    require_hash(benchmark, "benchmark source")
    require_hash(corpus, "case corpus")
    require_hash(profile, "rational profile")
    require_hash(receipt, "freeze receipt")
    if benchmark.get("contentHash") != receipt.get("benchmarkSourceContentHash"):
        fail("benchmark source is not bound by freeze receipt")
    if corpus.get("contentHash") != receipt.get("caseCorpusContentHash"):
        fail("case corpus is not bound by freeze receipt")
    roots = receipt.get("formationInventoryContentHashes")
    if not isinstance(roots, dict) or roots.get(PROFILE_ID) != profile.get("contentHash"):
        fail("rational profile is not bound by freeze receipt")
    if benchmark.get("executionStatus") != "NOT_STARTED":
        fail("benchmark source was not frozen before execution")
    if receipt.get("executionStatusAtFreeze") != "NOT_STARTED":
        fail("receipt does not retain NOT_STARTED state")
    if receipt.get("allowedNextStep") != "IMPLEMENT_EXECUTION_ADAPTERS_WITHOUT_MODIFYING_FROZEN_CASE_PAYLOADS":
        fail("freeze receipt does not authorize adapter implementation")
    if receipt.get("publicationAuthorized") is not False:
        fail("freeze receipt authorizes publication")
    budgets = benchmark.get("budgets")
    if budgets != {
        "campaignsPerChallenge": 4,
        "maxCandidateEvaluations": 600,
        "maxProofAttempts": 100,
        "maxStatesPerCampaign": 3000,
    }:
        fail(f"frozen budget changed: {budgets}")
    operations = index_by(profile.get("operations"), "operationId", "rational operations")
    if operations["DIFFERENCE_OF_SQUARES_FACTORING"].get("implementationStatus") != "AVAILABLE":
        fail("frozen square-difference primitive is unavailable")
    for operation_id in (
        "AFFINE_FACTOR_CANCELLATION",
        "COMMON_FACTOR_CANCELLATION",
        "NESTED_DIVISION_NORMALIZATION",
        "PARTIAL_FRACTION_DECOMPOSITION",
    ):
        if operations[operation_id].get("implementationStatus") != "ADAPTER_REQUIRED":
            fail(f"frozen operation status changed: {operation_id}")
    cases = index_by(
        [item for item in corpus.get("cases", []) if isinstance(item, dict) and item.get("challengeId") == CHALLENGE],
        "caseId",
        "rational cases",
    )
    if list(cases) != EXPECTED_CASES:
        fail(f"rational case identities or ordering changed: {list(cases)}")
    tasks: dict[str, dict[str, Any]] = {}
    for case_id, item in cases.items():
        require_hash(item, f"frozen case {case_id}")
        split = item.get("split")
        policy = item.get("exposurePolicy")
        if not isinstance(policy, dict):
            fail(f"case {case_id} has no exposure policy")
        if policy.get("candidateFormationMustNotRead") != ["evaluationInput"]:
            fail(f"case {case_id} does not prohibit evaluation input")
        if split == "TRAIN":
            if policy.get("candidateFormationMayRead") != ["formationInput"]:
                fail(f"TRAIN case {case_id} formation surface changed")
            if not isinstance(item.get("formationInput"), dict):
                fail(f"TRAIN case {case_id} has no formation input")
        else:
            if policy.get("candidateFormationMayRead") != [] or item.get("formationInput") is not None:
                fail(f"held-out case {case_id} exposes formation input")
        evaluation = item.get("evaluationInput")
        if not isinstance(evaluation, dict) or evaluation.get("expectedReferencesVisibleDuringFormation") is not False:
            fail(f"case {case_id} evaluation exposure changed")
        for task in evaluation.get("tasks", []):
            if not isinstance(task, dict):
                fail(f"case {case_id} task is not an object")
            task_id = task.get("taskId")
            if not isinstance(task_id, str) or task_id in tasks:
                fail(f"invalid or duplicate task {task_id}")
            tasks[task_id] = task
    if list(tasks) != EXPECTED_TASKS:
        fail(f"rational task identities or ordering changed: {list(tasks)}")
    return cases, tasks


def validate_formation(
    campaign: dict[str, Any],
    cases: dict[str, dict[str, Any]],
) -> None:
    if campaign.get("formationCaseIds") != TRAIN_CASES:
        fail("formation case set changed")
    if campaign.get("formationSeedIds") != TRAIN_SEEDS:
        fail("formation seed set changed")
    candidate = campaign.get("candidate")
    if not isinstance(candidate, dict):
        fail("candidate is missing")
    if candidate.get("candidateFormId") != CANDIDATE_FORM:
        fail("candidate form changed")
    if candidate.get("operatorId") != OPERATOR_ID:
        fail("candidate operator changed")
    if candidate.get("supportSeedIds") != TRAIN_SEEDS:
        fail("candidate support seeds changed")
    if candidate.get("frozenPrimitiveRuleIds") != ["ast_divide_one", "ast_square_difference_factor"]:
        fail("frozen primitive rule set changed")
    frozen_seeds: dict[str, tuple[str, list[str], str]] = {}
    for case_id in TRAIN_CASES:
        formation = cases[case_id]["formationInput"]
        if formation.get("targetExpressionsVisible") is not False:
            fail(f"TRAIN case {case_id} exposes targets")
        for seed in formation.get("seedExpressions", []):
            frozen_seeds[seed["seedId"]] = (
                seed["expression"],
                seed["assumptions"],
                case_id,
            )
    evidence = index_by(campaign.get("formationEvidence"), "seedId", "formation evidence")
    if list(evidence) != TRAIN_SEEDS:
        fail("formation evidence identities changed")
    for seed_id in TRAIN_SEEDS:
        item = evidence[seed_id]
        expression, assumptions, case_id = frozen_seeds[seed_id]
        if item.get("inputExpression") != expression:
            fail(f"formation input changed for {seed_id}")
        if item.get("declaredAssumptions") != assumptions:
            fail(f"formation assumptions changed for {seed_id}")
        if item.get("evaluationInputRead") is not False or item.get("targetVisible") is not False:
            fail(f"formation leakage for {seed_id}")
        selected = item.get("selectedExpression")
        if not isinstance(selected, str):
            fail(f"formation selected expression missing for {seed_id}")
        left_nf, right_nf, required, provided = confirm_equivalence(expression, selected, assumptions)
        if item.get("leftCrossNormalForm") != left_nf or item.get("rightCrossNormalForm") != right_nf:
            fail(f"formation normal form mismatch for {seed_id}")
        if item.get("requiredNonZeroFactors") != required:
            fail(f"formation required-factor mismatch for {seed_id}")
        if item.get("providedNonZeroFactors") != provided:
            fail(f"formation provided-factor mismatch for {seed_id}")
        if not isinstance(item.get("candidateAssumptions"), list) or not item["candidateAssumptions"]:
            fail(f"formation candidate lost assumptions for {seed_id}")
        if item.get("selectedAstNodes", 0) >= item.get("inputAstNodes", 0):
            fail(f"formation candidate is not simpler for {seed_id}")
        if not item.get("sourceReference", "").startswith(f"candidate-independent-frozen-formation/{case_id}/"):
            fail(f"formation source reference changed for {seed_id}")


def validate_resource_use(value: Any, context: str) -> tuple[int, int]:
    if not isinstance(value, dict):
        fail(f"{context} resource use is not an object")
    configured_states = value.get("configuredStates")
    executed_states = value.get("executedStates")
    remaining_states = value.get("remainingStates")
    configured_candidates = value.get("configuredCandidateEvaluations")
    executed_candidates = value.get("executedCandidateEvaluations")
    remaining_candidates = value.get("remainingCandidateEvaluations")
    if any(type(item) is not int or item < 0 for item in (
        configured_states,
        executed_states,
        remaining_states,
        configured_candidates,
        executed_candidates,
        remaining_candidates,
    )):
        fail(f"{context} resource values are invalid")
    if executed_states + remaining_states != configured_states:
        fail(f"{context} state accounting is unbalanced")
    if executed_candidates + remaining_candidates != configured_candidates:
        fail(f"{context} candidate accounting is unbalanced")
    return executed_states, executed_candidates


def validate_task_evaluation(
    evaluation: dict[str, Any],
    case: dict[str, Any],
    task: dict[str, Any],
) -> str:
    task_id = task["taskId"]
    if evaluation.get("caseId") != case.get("caseId"):
        fail(f"case identity mismatch for {task_id}")
    if evaluation.get("caseContentHash") != case.get("contentHash"):
        fail(f"case hash mismatch for {task_id}")
    if evaluation.get("split") != case.get("split"):
        fail(f"split mismatch for {task_id}")
    expected_visibility = "ALLOWED" if case.get("split") == "TRAIN" else "PROHIBITED"
    if evaluation.get("formationVisibility") != expected_visibility:
        fail(f"formation visibility mismatch for {task_id}")
    if evaluation.get("targetReadStage") != "EVALUATION_ONLY":
        fail(f"target read stage changed for {task_id}")
    for field in ("source", "target", "assumptions"):
        if evaluation.get(field) != task.get(field):
            fail(f"frozen task field {field} changed for {task_id}")
    source = task["source"]
    target = task["target"]
    assumptions = task["assumptions"]
    independently_reached, independent_rules = reachable(source, target, assumptions)
    expected_outcome = "REACHED_AND_CONFIRMED" if independently_reached else "NO_RESULT"
    if evaluation.get("outcome") != expected_outcome:
        fail(
            f"outcome mismatch for {task_id}: "
            f"{evaluation.get('outcome')} != {expected_outcome}"
        )
    expected_reason = (
        "TARGET_REACHED_BY_SELECTED_FORM_AND_FROZEN_PRIMITIVES"
        if independently_reached
        else "NO_PATH_WITH_SELECTED_FORM_AND_FROZEN_PRIMITIVES"
    )
    if evaluation.get("reasonCode") != expected_reason:
        fail(f"reason code mismatch for {task_id}")
    steps = evaluation.get("steps")
    if not isinstance(steps, list):
        fail(f"steps missing for {task_id}")
    if independently_reached:
        if not steps:
            fail(f"reached task has no path: {task_id}")
        current = parse_expression(source)
        seen_rules: list[str] = []
        for sequence, step in enumerate(steps, start=1):
            if step.get("sequence") != sequence:
                fail(f"step sequence changed for {task_id}")
            rule_id = step.get("ruleId")
            if rule_id not in ALLOWED_RULES:
                fail(f"undeclared rule {rule_id} in {task_id}")
            if parse_expression(step.get("source")) != current:
                fail(f"step source chain mismatch for {task_id}")
            target_ast = parse_expression(step.get("target"))
            valid_targets = {
                (transition.rule_id, ast_key(transition.target))
                for transition in all_rewrites(current)
            }
            if (rule_id, ast_key(target_ast)) not in valid_targets:
                fail(f"invalid {rule_id} transition in {task_id}")
            confirm_equivalence_ast(current, target_ast, assumptions)
            left_nf, right_nf, required, provided = confirm_equivalence(
                step["source"], step["target"], assumptions
            )
            if step.get("leftCrossNormalForm") != left_nf or step.get("rightCrossNormalForm") != right_nf:
                fail(f"step normal form mismatch for {task_id}")
            if step.get("requiredNonZeroFactors") != required:
                fail(f"step required-factor mismatch for {task_id}")
            if step.get("providedNonZeroFactors") != provided:
                fail(f"step provided-factor mismatch for {task_id}")
            if not isinstance(step.get("generatedAssumptions"), list):
                fail(f"step assumptions missing for {task_id}")
            current = target_ast
            seen_rules.append(rule_id)
        if not current_equivalent_to_target(current, parse_expression(target), assumptions):
            fail(f"path does not end at target for {task_id}")
        if not set(seen_rules).issubset(set(independent_rules)):
            fail(f"path uses rules outside independent reachable witness for {task_id}")
    elif steps:
        fail(f"no-result task retained a hidden path: {task_id}")
    validate_resource_use(evaluation.get("resourceUse"), f"task {task_id}")
    if evaluation.get("correctnessRegression") is not False:
        fail(f"task {task_id} reports a correctness regression")
    if evaluation.get("formalProofStatus") != "NOT_EVALUATED":
        fail(f"task {task_id} overstates formal proof")
    if evaluation.get("externalNoveltyStatus") != "NOT_EVALUATED":
        fail(f"task {task_id} overstates external novelty")
    if evaluation.get("publicationEligible") is not False:
        fail(f"task {task_id} authorizes publication")
    return expected_outcome


def validate_run(
    run: dict[str, Any],
    schema: dict[str, Any],
    benchmark: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    cases: dict[str, dict[str, Any]],
    tasks: dict[str, dict[str, Any]],
) -> tuple[int, int]:
    try:
        jsonschema.Draft202012Validator(schema).validate(run)
    except jsonschema.ValidationError as error:
        fail(f"run schema validation failed: {error.message}")
    require_nested_hashes(run)
    bindings = {
        "schema": RUN_SCHEMA,
        "benchmarkId": BENCHMARK_ID,
        "challengeId": CHALLENGE,
        "benchmarkSourceContentHash": benchmark["contentHash"],
        "caseCorpusContentHash": corpus["contentHash"],
        "formationProfileId": PROFILE_ID,
        "formationProfileContentHash": profile["contentHash"],
        "freezeReceiptContentHash": receipt["contentHash"],
        "combinedPreregistrationHash": receipt["combinedPreregistrationHash"],
        "adapterStatus": ADAPTER_STATUS,
        "candidateForm": CANDIDATE_FORM,
        "frozenImplementationStatus": "ADAPTER_REQUIRED",
        "runtimeImplementationStatus": "AVAILABLE_AFTER_FREEZE",
        "frozenProfileModified": False,
        "configuredCampaigns": 4,
        "executedCampaigns": 4,
        "configuredTaskEvaluations": 48,
        "executedTaskEvaluations": 48,
        "configuredStatesPerCampaign": 3000,
        "configuredCandidateEvaluationsPerCampaign": 600,
        "configuredProofAttemptsPerCampaign": 100,
        "executedProofAttempts": 0,
        "uniqueGeneralRuleClaimAuthorized": False,
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    }
    for field, expected in bindings.items():
        if run.get(field) != expected:
            fail(f"run binding {field} changed: {run.get(field)!r} != {expected!r}")
    campaigns = index_by(run.get("campaigns"), "campaignId", "campaigns")
    if list(campaigns) != EXPECTED_CAMPAIGNS:
        fail(f"campaign identities or ordering changed: {list(campaigns)}")
    reached_total = 0
    no_result_total = 0
    evaluation_roots: set[str] = set()
    for campaign_index, campaign_id in enumerate(EXPECTED_CAMPAIGNS, start=1):
        campaign = campaigns[campaign_id]
        if campaign.get("configuredSeed") != configured_seed(BENCHMARK_ID, campaign_id, campaign_index):
            fail(f"configured seed mismatch for {campaign_id}")
        if campaign.get("formationVisibility") != "TRAIN_ONLY":
            fail(f"formation visibility changed in {campaign_id}")
        if campaign.get("heldOutTargetAccess") != "EVALUATION_ONLY":
            fail(f"held-out target access changed in {campaign_id}")
        validate_formation(campaign, cases)
        evaluations = index_by(campaign.get("taskEvaluations"), "taskId", f"{campaign_id} tasks")
        if list(evaluations) != EXPECTED_TASKS:
            fail(f"task matrix changed in {campaign_id}")
        reached = 0
        no_result = 0
        executed_states = 0
        executed_candidates = 0
        for task_id in EXPECTED_TASKS:
            case_id = task_id[:7]
            outcome = validate_task_evaluation(evaluations[task_id], cases[case_id], tasks[task_id])
            if outcome == "REACHED_AND_CONFIRMED":
                reached += 1
            else:
                no_result += 1
            task_states, task_candidates = validate_resource_use(
                evaluations[task_id].get("resourceUse"), f"{campaign_id}/{task_id}"
            )
            executed_states += task_states
            executed_candidates += task_candidates
            root = evaluations[task_id].get("contentHash")
            if root in evaluation_roots:
                fail(f"duplicate task evaluation root {root}")
            evaluation_roots.add(root)
        if reached != 6 or no_result != 6:
            fail(f"unexpected campaign frontier in {campaign_id}: {reached}/{no_result}")
        if campaign.get("reachedAndConfirmedTasks") != reached:
            fail(f"campaign reached count mismatch in {campaign_id}")
        if campaign.get("noResultTasks") != no_result:
            fail(f"campaign no-result count mismatch in {campaign_id}")
        if campaign.get("budgetExhaustedTasks") != 0 or campaign.get("correctnessRegressions") != 0:
            fail(f"campaign terminal accounting changed in {campaign_id}")
        campaign_states, campaign_candidates = validate_resource_use(
            campaign.get("resourceUse"), f"campaign {campaign_id}"
        )
        if campaign_states < executed_states or campaign_candidates < executed_candidates:
            fail(f"campaign resource total is smaller than task usage in {campaign_id}")
        resource = campaign["resourceUse"]
        if resource.get("configuredProofAttempts") != 100 or resource.get("executedProofAttempts") != 0 or resource.get("remainingProofAttempts") != 100:
            fail(f"proof-attempt accounting changed in {campaign_id}")
        reached_total += reached
        no_result_total += no_result
    if reached_total != 24 or no_result_total != 24:
        fail(f"aggregate frontier changed: reached={reached_total}, noResult={no_result_total}")
    if run.get("reachedAndConfirmedTaskEvaluations") != reached_total:
        fail("top-level reached count does not match raw tasks")
    if run.get("noResultTaskEvaluations") != no_result_total:
        fail("top-level no-result count does not match raw tasks")
    for field in (
        "budgetExhaustedTaskEvaluations",
        "candidateNotFormedTaskEvaluations",
        "targetRefutedTaskEvaluations",
        "correctnessRegressions",
    ):
        if run.get(field) != 0:
            fail(f"unexpected nonzero terminal field {field}")
    return reached_total, no_result_total


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def rehash_run(run: dict[str, Any]) -> None:
    for campaign in run.get("campaigns", []):
        candidate = campaign.get("candidate")
        if isinstance(candidate, dict):
            rehash(candidate)
        for formation in campaign.get("formationEvidence", []):
            rehash(formation)
        for evaluation in campaign.get("taskEvaluations", []):
            for step in evaluation.get("steps", []):
                rehash(step)
            rehash(evaluation)
        rehash(campaign)
    rehash(run)


def require_rejected(
    name: str,
    baseline: dict[str, Any],
    mutate: Callable[[dict[str, Any]], None],
    validate: Callable[[dict[str, Any]], None],
) -> str:
    candidate = copy.deepcopy(baseline)
    mutate(candidate)
    rehash_run(candidate)
    try:
        validate(candidate)
    except VerificationError as error:
        return f"{name}: {error}"
    fail(f"negative case {name} was accepted")


def write_verification(
    directory: Path,
    run: dict[str, Any],
    run_path: Path,
    reached: int,
    no_result: int,
    negatives: list[str],
) -> None:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    report = {
        "schema": VERIFY_SCHEMA,
        "status": "VERIFIED_RATIONAL_ASSUMPTION_ADAPTER_FRONTIER",
        "runContentHash": run["contentHash"],
        "runFileSha256": exact_hash(run_path),
        "campaignsVerified": 4,
        "taskEvaluationsVerified": 48,
        "reachedAndConfirmedTaskEvaluations": reached,
        "noResultTaskEvaluations": no_result,
        "budgetExhaustedTaskEvaluations": 0,
        "correctnessRegressions": 0,
        "candidateForm": CANDIDATE_FORM,
        "allowedRuleIds": ALLOWED_RULES,
        "formationVisibility": "TRAIN_ONLY",
        "heldOutTargetAccess": "EVALUATION_ONLY",
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
        "negativeCases": negatives,
    }
    report["contentHash"] = semantic_hash(report)
    output = directory / "verification.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"rationalAssumptionAdapterVerification={output}")
    print(f"contentHash={report['contentHash']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    root = args.repository_root.resolve()
    first_path = args.first.resolve()
    second_path = args.second.resolve()
    try:
        schema = load_json(root / "docs/schemas/regelsuche-candidate-independent-rational-assumption-adapter-run-v1.schema.json")
        benchmark = load_json(root / "research/benchmarks/candidate-independent/benchmark-source.json")
        corpus = load_json(root / "research/benchmarks/candidate-independent/case-corpus.json")
        profile = load_json(root / "research/benchmarks/candidate-independent/rational-assumption-primitives.json")
        receipt = load_json(root / "research/benchmarks/candidate-independent/corpus-freeze-receipt.json")
        cases, tasks = validate_frozen_inputs(benchmark, corpus, profile, receipt)
        if first_path.read_bytes() != second_path.read_bytes():
            fail("two clean rational adapter runs are not byte-identical")
        first = load_json(first_path)
        second = load_json(second_path)
        validate = lambda value: validate_run(
            value, schema, benchmark, corpus, profile, receipt, cases, tasks
        )
        reached, no_result = validate(first)
        validate(second)
        negatives = [
            require_rejected(
                "heldout-formation-leakage",
                first,
                lambda value: value["campaigns"][0]["formationEvidence"][0].update({"evaluationInputRead": True}),
                validate,
            ),
            require_rejected(
                "hidden-no-result-as-success",
                first,
                lambda value: value["campaigns"][0]["taskEvaluations"][4].update({
                    "outcome": "REACHED_AND_CONFIRMED",
                    "reasonCode": "TARGET_REACHED_BY_SELECTED_FORM_AND_FROZEN_PRIMITIVES",
                }),
                validate,
            ),
            require_rejected(
                "undeclared-path-rule",
                first,
                lambda value: value["campaigns"][0]["taskEvaluations"][0]["steps"][0].update({"ruleId": "rational_partial_fraction"}),
                validate,
            ),
            require_rejected(
                "missing-task-row",
                first,
                lambda value: value["campaigns"][0]["taskEvaluations"].pop(),
                validate,
            ),
            require_rejected(
                "resource-imbalance",
                first,
                lambda value: value["campaigns"][0]["resourceUse"].update({"remainingStates": 3000}),
                validate,
            ),
            require_rejected(
                "publication-overclaim",
                first,
                lambda value: value.update({"publicationAuthorized": True, "formalProofStatus": "FORMALLY_PROVED"}),
                validate,
            ),
        ]
        write_verification(args.report_directory.resolve(), first, first_path, reached, no_result, negatives)
    except (VerificationError, OSError, jsonschema.SchemaError, KeyError, TypeError) as error:
        print(f"rational-assumption adapter verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
