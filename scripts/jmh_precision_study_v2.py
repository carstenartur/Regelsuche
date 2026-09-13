"""Explicit successor identity and typed collection checks; v1 statistics remain frozen."""

from __future__ import annotations

import copy

import jmh_precision_study_v1 as predecessor
from jmh_precision_study_jar_v2 import SCHEMA as JAR_SCHEMA
from jmh_precision_study_v1 import (FAMILIES, canonical, cells, finite, jmh_command,
                                    load_json, require, sha256, summarize, verify_launch, worst_status)


POLICY_PATH = "config/quality/jmh-precision-study-policy-v2.json"
STUDY_POLICY_SHA256 = "cd296629d75a37e1a4266550093e8a81573a4450e79be1dadebd72e24233693f"
FROZEN_INPUTS = predecessor.FROZEN_INPUTS | {
    predecessor.POLICY_PATH: predecessor.STUDY_POLICY_SHA256}


def load_policy(root):
    require(sha256(root / POLICY_PATH) == STUDY_POLICY_SHA256,
            "preregistration content differs; a changed contract needs a new study revision")
    policy = load_json(root / POLICY_PATH)
    old, inventory = predecessor.load_policy(root)
    expected = copy.deepcopy(old)
    expected.update(schema="regelsuche.quality.jmh-precision-study-policy/v2",
                    studyId="issue-981-shared-runner-precision-v2", jarIdentitySchema=JAR_SCHEMA,
                    predecessorStudy=dict(studyId=old["studyId"], runId=34729477401, runAttempt=1,
                                          policySha256=predecessor.STUDY_POLICY_SHA256,
                                          collectionStatus="ERROR",
                                          adoptionOutcome="INCOMPLETE_EVIDENCE_NO_SELECTION"))
    expected["sharedRunnerLaunch"].update(headBranch="codex/issue-981-jmh-precision-study-v2",
                                          job="jmh-precision-study-v2")
    require(policy == expected, "successor preregistration changed the frozen statistical or execution contract")
    return policy, inventory


def evaluate_result(payload, cell, inventory):
    """Check GC object shape before the unchanged numerical/decision verifier."""
    if cell["profiler"] == "gc" and isinstance(payload, list):
        for row in payload:
            secondary = row.get("secondaryMetrics") if isinstance(row, dict) else None
            if isinstance(secondary, dict):
                for key, metric in secondary.items():
                    if isinstance(key, str) and key.lstrip("·") == "gc.alloc.rate.norm":
                        require(isinstance(metric, dict), "allocation metric must be an object")
    return predecessor.evaluate_result(payload, cell, inventory)
