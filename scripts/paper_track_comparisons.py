"""Generate the public discovery, held-out transfer and controller results used in the manuscript."""
import gzip
import hashlib
import json
from collections import Counter
from pathlib import Path
from xml.sax.saxutils import escape

from paper_controller_figure import table

SOURCES = {
    "audit": ("research/benchmarks/candidate-independent/track-c-project-inventory/audit-result.json.gz",
              "6fd69dc66f4ce982dc3314c3082104cea2543b5cfc30ba68831fe4836a503af7", "e5a3c7919128335691bcb1b3f0de04e58ef63e10"),
    "transfer": ("research/benchmarks/track-d-public-transfer/transfer-result.json",
                 "9c10cf1e8a36659204183674e0c94e820c18424862dc62e06d647883e1343605", "0d8c6545478cbb8eca50e539124c8118939055aa"),
    "controller": ("research/benchmarks/track-f-stage-allocation/measured.json.gz",
                   "5c3a00f1db80ae5632ffe3ce734badbcde93575be1786094eb443c76c7801df0", "ab12f340e7e6f894ff32088adbc2df1dfa4f11b8"),
    "historicalHidden": ("research/benchmarks/comparative-v2/hidden-rule-historical.json.gz",
                         "46605224e9851071df8522c9d74ed70bf09ff1911d3d54448888f09a8449ff7e", "df5d2a28d9ad0184b3d80d1723ba23aca9b92a7b"),
    "exactHidden": ("research/benchmarks/comparative-v2/hidden-rule-current.json.gz",
                    "eea493b38abcc45719d694f4ef4c58e29f5e2a4b7732ad24d9cec5b5cd2bdf08", "df5d2a28d9ad0184b3d80d1723ba23aca9b92a7b"),
    "correctedHidden": ("research/benchmarks/comparative-v2/hidden-rule-constant-denominators.json.gz",
                        "1af25ae8e84e3d0e88d7fe1d3a8547b99bf35afb877e0318bfa95f0511801fdc", "afc8018d45138b2cabff2462e9bb4cd48700cf13"),
    "exactComponents": ("docs/evidence/discovery-component-panel-exact-v1.json.gz",
                        "fbeb2b16f1a61ff4281c22a9d31996e693e3fcbce709b63f91cdcb4dd971176a", "5ddc10da51282fd6e23a01c3b6c7de1dd3bfa3cb"),
    "components": ("docs/evidence/discovery-component-panel-v1.json.gz",
                   "33300f2dd7f34a53d36ab36d5d241c453a289ab12d1b325310964618a8d169b7", "a0abfcbf8b136047c52e51d87a13b0933433f9d7"),
    "fanIn": ("docs/evidence/open-target-fan-in-v1.json.gz",
              "0f509de1739a0916ba8e6f13b9739214fc02f90d98be64206e83acdf8899986a", "5c34abe7b98d4ea508cceec282c9f304de6144c9"),
}
FORMS = [("recurrence", "Recurrence", "CONFIRMED"), ("finiteDifferences", "Newton", "CONFIRMED"),
         ("rational", "Rational", "REACHED_AND_CONFIRMED"), ("macros", "Macro", "IMPROVED")]
POLICIES = {"NATIVE_RECEIPT_DRIVEN": "Receipt-driven", "UNIFORM_ALL_STAGES": "Uniform", "STATIC_NO_FEEDBACK": "Static"}
PANELS = {"null-then-open": "Zero then one", "competing-continuations": "One then one"}


def source(key):
    path, _, revision = SOURCES[key]
    return f"https://github.com/carstenartur/Regelsuche/blob/{revision}/{path}"


def fraction(numerator, denominator):
    return f"{numerator}/{denominator}"


def formation_tables(forms):
    rows, audit = [], []
    for key, form, positive in FORMS:
        result = forms[key]
        for prefix, name in (("PRODUCTION", "Production"), ("ENUMERATE", "Enumeration"), ("SEEDED_RANDOM", "Seeded random")):
            cohort = [row for row in result["lanes"] if row["arm"].startswith(prefix)]
            if len(cohort) != 4:
                raise ValueError("expected four campaigns per form and arm")
            splits = []
            for split in ("TRAIN", "VALIDATION", "TEST"):
                counts = Counter()
                for row in cohort:
                    counts.update(row["evaluationOutcomes"][split])
                splits.append(fraction(counts[positive], counts.total()))
            rows.append([form, name, sum(len(row["candidates"]) for row in cohort),
                         sum(not row["candidates"] for row in cohort), *splits])
        labels = {"EXACT_MODEL_MATCH": "same model", "EXACT_PROGRAM_MATCH": "same program",
                  "PROPER_SUBSET_OF_HISTORICAL_PROGRAM": "program subset", "PATTERN_MATCH_ONLY": "pattern only",
                  "NO_MATCH_IN_NAMED_INVENTORY": "no match"}
        detail = ", ".join(f"{value} {labels[status]}" for status, value in result["occurrenceMatchCounts"].items())
        audit.append([form, result["candidateOccurrences"], result["distinctSemanticKeys"], detail])
    return [table(["Form", "Arm", "Emitted", "Empty", "TRAIN", "VALID.", "TEST"], rows),
            table(["Form", "Emissions", "Distinct keys", "Catalogue matches by emission"], audit)]


def controller_figure(rows, output):
    # Fourteen rectangles per lane encode all configured tickets, even when a policy stops early.
    colors = {"executed": "#17566b", "skipped": "#cf8f3d", "remaining": "#dce4e5"}
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 660 236" width="660" height="236">',
             '<title>Fourteen dispatch opportunities per panel and policy</title>',
             '<rect width="660" height="236" fill="#ffffff"/>',
             '<g font-family="sans-serif" font-size="12" fill="#25303c">']
    for i, row in enumerate(rows):
        panel, policy, _, executed, skipped, remaining = row
        y = 9 + i * 30
        parts.append(f'<text x="3" y="{y+13}">{escape(panel)} · {escape(policy)}</text>')
        slots = ["executed"] * executed + ["skipped"] * skipped + ["remaining"] * remaining
        if len(slots) != 14:
            raise ValueError("unbalanced ticket figure")
        for j, slot in enumerate(slots):
            parts.append(f'<rect x="{228+j*29}" y="{y}" width="26" height="17" fill="{colors[slot]}"/>')
    for i, (status, color) in enumerate(colors.items()):
        x = 228 + i * 130
        parts.extend([f'<rect x="{x}" y="208" width="14" height="13" fill="{color}"/>',
                      f'<text x="{x+20}" y="219">{status.title()}</text>'])
    parts.append('</g></svg>')
    (output / "public-stage-dispatch.svg").write_text("\n".join(parts) + "\n")


def component_rows(panel):
    arms = panel["arms"]
    production = arms["PRODUCTION"]
    rows = []
    base_scores = {e["inputHypothesis"]["id"]: e["productionScore"]["total"] for e in production["frozenSchedule"]}
    labels = {"PRODUCTION": "Production", "NO_FAN_IN": "No fan-in", "NO_REFINEMENT": "No refinement",
              "NO_NOVELTY_SCHEDULING": "No novelty scheduling"}
    for key, label in labels.items():
        arm = arms[key]
        schedule = arm["frozenSchedule"]
        challenges = arm["challengesInFrozenOrder"]
        scores = sum(row["productionScore"]["total"] != base_scores[row["inputHypothesis"]["id"]] for row in schedule)
        changed = sum(a["selectedCandidateIds"] != b["selectedCandidateIds"]
                      for a, b in zip(arm["selectionAtSlotCeiling"], production["selectionAtSlotCeiling"], strict=True))
        refuted = sum(c["terminalRecheck"]["counterexample"] is not None for c in challenges)
        revisions = sum(r["revision"] > 0 for c in challenges for r in c["revisions"])
        rows.append([label, len(schedule), scores, changed, refuted, revisions])
    return rows


def validator_table(historical, exact):
    rows = []
    names = {"PRODUCTION": "Production", "NO_FAN_IN": "No fan-in", "NO_REFINEMENT": "No refinement",
             "NO_NOVELTY_SCHEDULING": "No novelty scheduling"}
    for key, name in names.items():
        previous = historical["arms"][key]
        current = exact["arms"][key]
        if previous["frozenSchedule"] != current["frozenSchedule"]:
            raise ValueError("component ordering changed across the registered validator runs")
        old_checks = previous["challengesInFrozenOrder"]
        new_checks = current["challengesInFrozenOrder"]
        if [c["candidateId"] for c in old_checks] != [c["candidateId"] for c in new_checks]:
            raise ValueError("mismatched component candidates")
        outcomes = [Counter(c["terminalRecheck"]["status"] for c in checks) for checks in (old_checks, new_checks)]
        rows.append([name, len(new_checks), outcomes[0]["NO_COUNTEREXAMPLE_FOUND"], outcomes[0]["INCONCLUSIVE"],
                     outcomes[1]["NO_COUNTEREXAMPLE_FOUND"], outcomes[1]["INCONCLUSIVE"]])
    return table(["Policy", "Mined checks", "Earlier: no witness", "Earlier inconclusive",
                  "Exact: no witness", "Exact inconclusive"], rows)


def rediscovery_table(historical, exact, corrected):
    if historical["tasks"] != exact["tasks"] or historical["tasks"] != corrected["tasks"]:
        raise ValueError("hidden-rule tasks changed across validator versions")
    rows = []
    for version, panel in (("Historical", historical), ("Exact (initial)", exact), ("Exact (corrected)", corrected)):
        for key, name in (("best-first", "Best-first"), ("project-a-star", "Project A*")):
            data = panel["profiles"][key]["summary"]
            rows.append([version, name, fraction(data["rediscoveredCases"], data["cases"]),
                         fraction(data["evaluatedNegativeHoldouts"], data["negativeHoldouts"]),
                         data["skippedNegativeHoldouts"], data["falsePositiveHoldouts"]])
    return table(["Validator", "Search", "Recovered", "Negative executed", "Negative skipped", "False positives"], rows)


def write_track_reports(root, output, require_input):
    reports = {}
    for key, (relative, sha, _) in SOURCES.items():
        path = require_input(root / relative, relative)
        data = path.read_bytes()
        if relative.endswith(".gz"):
            data = gzip.decompress(data)
        if hashlib.sha256(data).hexdigest() != sha:
            raise ValueError("public comparison evidence has changed: " + relative)
        reports[key] = json.loads(data)
    formation, audit = formation_tables(reports["audit"]["forms"])
    bridge = reports["transfer"]["bridges"][0]
    paired = []
    for family in bridge["familyRuns"]:
        executions = [(k, family[k]["without"], family[k]["with"]) for k in ("positive", "negative")]
        before = [a["status"] == "REACHED" for _, a, _ in executions]
        after = [b["status"] == "REACHED" for _, _, b in executions]
        calls = sum(e["bridgeInvoked"] for _, _, b in executions for e in b["expansions"])
        emitted = sum(len(b["bridgeApplications"]) for _, _, b in executions)
        paired.append([family["family"], "Held-out" if family["role"] == "HELD_OUT" else "Formation",
                       f"{int(before[0])} / {int(after[0])}", f"{int(before[1])} / {int(after[1])}", calls, emitted])
    downstream = []
    for panel, panel_label in PANELS.items():
        for policy, label in POLICIES.items():
            lane = next(l for l in reports["controller"]["lanes"] if (l["panel"], l["arm"]) == (panel, policy))
            branches = lane["candidates"]
            tickets = Counter(e["status"] for e in lane["dispatch"])
            downstream.append([panel_label, label, fraction(sum(b["status"] == "COMPLETED" for b in branches), len(branches)),
                               tickets["EXECUTED"], tickets["SKIPPED"], lane["remainingTickets"]])
    controller_figure(downstream, output)
    fan = []
    for key, name in (("production", "Production"), ("noFanIn", "No fan-in")):
        branch = reports["fanIn"][key]
        fan.append([name, len(branch), sum(len(b["receipt"]["outputs"]) for b in branch),
                    sum(len(b["receipt"]["rejectedClusters"]) for b in branch)])
    sections = [
        "# Open-target formation and reuse", "Each cell gives successes over registered slots across four campaigns. "
        "Sequence successes require a frozen model to match the observed prefix and continuation. Rational successes "
        "require guarded target reachability. Macro successes require paired utility improvement. Empty counts campaign-arm "
        "slots without an emitted candidate. Campaign seeds repeat the same public tasks.", formation,
        "## Historical project inventories", audit,
        "Each recurrence had one supporting TRAIN case. The Newton model came from one square-sequence case. Rational "
        "programs received four witnesses from two TRAIN clusters, with exact field identities under the supplied nonzero "
        "premises. Each macro pattern drew support from one cluster and had a symbolic polynomial-normal-form witness. "
        "Both native macro patterns received NEW from the seven-rule checker; one matched an earlier macro pattern "
        "with different operations. Inventory matches cover the registered project revision. "
        f"[All 48 slots and frozen input identities]({source('audit')}).",
        "## Paired public cross-family transfer", "Eight untargeted formation searches yielded a bridge for coefficient pairs "
        "separated by two. Each family below supplied one positive target and one negative control. Target entries show "
        "baseline / bridge-enabled successes. Matcher calls and applications cover both targets.",
        table(["Family", "Role", "Positive", "Negative", "Calls", "Applications"], paired),
        "Native factorization reached all four positive targets. No negative control was reached. The formed bridge "
        "emitted no move, its proof gate was ineligible and no held-out work reduction was measured. "
        f"[All 16 native search runs]({source('transfer')}).",
        "## Seven-stage allocation", "Both panels ran the same registered twelve seeds and two six-slot formation batches. "
        "Completed counts eligible lineages that reached conservative lifecycle handoff. The two batches in the second "
        "panel formed different lineages for the same conjecture.",
        table(["Batch outputs", "Policy", "Completed / formed", "Executed", "Skipped", "Remaining"], downstream),
        "![Dispatch opportunities grouped by disposition](public-stage-dispatch.svg)",
        "Every lane had fourteen stage dispatch opportunities. The evaluator uses one validation and one counterexample "
        "ticket in the same call. Receipt-driven and uniform allocation completed the same branches using identical native "
        "work. The static policy reserved both candidate chains for the first batch. Tickets do not establish CPU parity. "
        f"[All six dispatch traces and native resource ledgers]({source('controller')}).",
        "## Cross-observation formation", table(["Policy", "Batches", "Candidates", "Rejected clusters"], fan),
        "On the original public production campaign, the same miner formed a candidate only when it could pool observations. "
        "The control preserves all twelve seeds, capacity and native rejection records. "
        f"[All batch receipts]({source('fanIn')}).",
        "## Historical matched discovery-component ablations",
        "The original component run used the pre-#1245 validator on historical input/output observations and a fixed challenge. Each control disables "
        "one production mechanism under the same slot and validation ceilings. Score changes compare the candidates "
        "present in both arms. Changed prefixes count budgets whose selected IDs differ from production.",
        table(["Policy", "Candidates", "Score changes", "Changed prefixes", "Refuted", "Revisions"],
              component_rows(reports["components"])),
        "The production miner pooled 23 observations and formed five candidates. Four known candidates gained two "
        "score points each when project novelty was disabled. Every budget from zero to 46 selected the same ordered "
        "prefix as production. The five terminal checks found no counterexample and issued no revised statement. "
        "The historical log-product control, counted separately in every arm, also produced no counterexample. It inferred "
        "three positivity guards. This panel provides no evidence about how often refinement repairs a refuted conjecture. "
        f"[Matched inputs, schedules, challenges and control]({source('components')}).",
    ]
    sections.extend([
        "## Validator-version comparison",
        "The same public component protocol ran with the exactness revision. No witness means the sampled search "
        "returned NO_COUNTEREXAMPLE_FOUND. Inconclusive means the validator could not decide within its exact arithmetic "
        "bounds. The separate log control is excluded from the mined-check denominator.",
        validator_table(reports["components"], reports["exactComponents"]),
        "One distinct rational cancellation candidate changed to inconclusive in every arm that formed it. Its exact "
        "search found no defined scalar pair within the bounds. The four other mined candidates retained their bounded "
        "no-witness status. Twelve polynomial source pairs now carry exact "
        "univariate normal-form provenance. Both versions of the separate log control retained three positivity guards "
        "and found no refuting witness. "
        f"[Historical component execution]({source('components')}) · [Exact execution]({source('exactComponents')}).",
        "The separate hidden-rule replay used the same twenty tasks under all three validator revisions. "
        "The corrected exact polynomial normalizer handles quotients whose denominator simplifies exactly to a "
        "nonzero constant. A missing admitted candidate can leave negative holdouts unexecuted. The false-positive "
        "count covers the executed holdouts only.",
        rediscovery_table(reports["historicalHidden"], reports["exactHidden"], reports["correctedHidden"]),
        f"[Historical hidden-rule execution]({source('historicalHidden')}) · "
        f"[Initial exact execution]({source('exactHidden')}) · [Corrected exact execution]({source('correctedHidden')}).",
    ])
    (output / "public-discovery-comparisons.md").write_text("\n\n".join(sections) + "\n")
