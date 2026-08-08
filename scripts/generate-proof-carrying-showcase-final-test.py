#!/usr/bin/env python3
"""Canonical entry point for the deterministic showcase FINAL TEST generator.

The implementation module retains the common contract, hashing and family
machinery. This entry point installs the production-evaluator-complete
multi-stage family definition before delegating to that module.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

sys.dont_write_bytecode = True


def load_implementation():
    path = Path(__file__).with_name("generate-proof-carrying-showcase-cases.py")
    spec = importlib.util.spec_from_file_location("showcase_case_generator", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load showcase generator implementation: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def install_exact_multi_stage_family(module) -> None:
    def exact_multi_stage_case(case_ordinal, difficulty, variant, data):
        coefficients = module.coefficient_vector(data, difficulty, case_ordinal)
        blocks: list[str] = []
        numerators: list[str] = []
        denominators: list[str] = []
        assumptions: list[str] = []
        block_kinds: list[str] = []

        for index, coefficient in enumerate(coefficients):
            suffix = f"{case_ordinal}_{index}"
            if index % 2 == 0:
                a = f"r{suffix}"
                b = f"s{suffix}"
                x = f"x{suffix}"
                y = f"y{suffix}"
                if variant == 0:
                    numerator = f"({coefficient}*(({a}/{x})-({a}/{y})))"
                else:
                    numerator = (
                        f"((({coefficient}*{a})/{x})"
                        f"-(({coefficient}*{a})/{y}))"
                    )
                denominator = f"(({b}/{x})-({b}/{y}))"
                blocks.append(f"({numerator})/({denominator})")
                numerators.append(f"({coefficient}*{a})")
                denominators.append(b)
                delta = f"({y}-{x})"
                assumptions.extend(
                    (
                        f"{x} != 0",
                        f"{y} != 0",
                        f"{b} != 0",
                        f"{delta} != 0",
                        f"({b}*{delta}) != 0",
                    )
                )
                if index > 0:
                    assumptions.extend(
                        (
                            f"{a} != 0",
                            f"({a}*{delta}) != 0",
                        )
                    )
                block_kinds.append("MIXED_DENOMINATOR_RATIO")
            else:
                p = f"u{suffix}"
                q = f"v{suffix}"
                difference = f"({p}^2-{q}^2)"
                divisor = f"({p}-{q})"
                block = (
                    f"({coefficient}*(({difference})/{divisor}))"
                    if variant == 0
                    else f"(({coefficient}*{difference})/{divisor})"
                )
                blocks.append(block)
                numerators.append(f"({coefficient}*({p}+{q}))")
                denominators.append("1")
                assumptions.extend(
                    (
                        f"({p}-{q}) != 0",
                        f"({p}+{q}) != 0",
                        f"{difference} != 0",
                    )
                )
                block_kinds.append("DIFFERENCE_OF_SQUARES_QUOTIENT")

        target_numerator = module.parenthesized_product(
            [
                numerators[0],
                *[value for value in denominators[1:] if value != "1"],
            ]
        )
        target_denominator = module.parenthesized_product(
            [denominators[0], *numerators[1:]]
        )
        return module.GeneratedMaterial(
            input_expression=module.left_division(blocks),
            target_expression=f"({target_numerator})/({target_denominator})",
            assumptions=module.normalized_assumptions(assumptions),
            coefficient_vector=coefficients,
            block_kinds=block_kinds,
        )

    module.GENERATORS["multi-stage-rational-polynomial"] = exact_multi_stage_case


def main() -> None:
    implementation = load_implementation()
    install_exact_multi_stage_family(implementation)
    implementation.main()


if __name__ == "__main__":
    main()
