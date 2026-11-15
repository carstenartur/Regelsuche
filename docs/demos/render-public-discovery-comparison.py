#!/usr/bin/env python3
"""Present two pinned public benchmark recordings without running any searches."""
import argparse
import gzip
import hashlib
import html
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from paper_track_comparisons import SOURCES, qualification_summary  # noqa: E402

OUTPUT = ROOT / 'app/src/main/resources/web/public-discovery-comparison.html'
GITHUB = 'https://github.com/carstenartur/Regelsuche/blob/'
RECORDING_REVISION = '6b42812b71ab47c86c837467ab57de44e9275468'


def load(key):
    path, expected_hash, _ = SOURCES[key]
    raw = (ROOT / path).read_bytes()
    raw = gzip.decompress(raw) if path.endswith('.gz') else raw
    if hashlib.sha256(raw).hexdigest() != expected_hash:
        raise ValueError(f'public comparison evidence has changed: {path}')
    return json.loads(raw)


def link(key, text):
    path, _, revision = SOURCES[key]
    return f'<a href="{GITHUB}{revision or RECORDING_REVISION}/{path}">{text}</a>'


def esc(value):
    return html.escape(str(value))


def code(value):
    return f'<code>{esc(value)}</code>'


def expression(label, value):
    return f'<p><b>{label}</b> {code(value)}</p>'


def qualification_cases(qualification, kind):
    suite = qualification['suite']['positives' if kind == 'positive' else 'negatives']
    evaluations = qualification['evaluation'][f'{kind}Results']
    utility = {case['id']: case for case in qualification['utility']['cases']}
    cases = []
    for task, check in zip(suite, evaluations, strict=True):
        if task['id'] != check['id']:
            raise ValueError(f'{kind} evaluation refers to a different qualification case')
        passed = 'Passed' if check['passed'] else 'Failed'
        content = expression('Input', task['inputExpression'])
        if kind == 'positive':
            pair = utility[task['id']]
            before, after = pair['baselineExploredStates'], pair['candidateExploredStates']
            label = f'{before} → {after} explored states'
            content += expression('Target', task['targetExpression'])
            content += f"""<dl class="metrics">
<dt>Search status, baseline → rewrite</dt><dd>{code(pair['baselineStatus'])} → {code(pair['candidateStatus'])}</dd>
<dt>Path length, baseline → rewrite</dt><dd>{pair['baselinePathLength']} → {pair['candidatePathLength']}</dd>
</dl>"""
        else:
            label = f'{passed} · {check["candidateCount"]} candidates'
        outputs = ', '.join(map(code, check['candidateExpressions'])) or 'None emitted'
        content += f'<p><b>Qualification check</b> {passed} · {check["candidateCount"]} candidates · {outputs}</p>'
        cases.append(f'<details><summary>{esc(task["id"])} <span>{label}</span></summary>{content}</details>')
    return '\n'.join(cases)


def path_run(label, run):
    steps = [f'<li>{code(run["path"][0])}</li>']
    for rule, state in zip(run['rules'], run['path'][1:], strict=True):
        steps.append(f'<li>{code(state)}<small>Applied rule {code(rule)}</small></li>')
    return f"""<div class="arm">
<h5>{label}</h5>
<p>{code(run['status'])} · {run['work']['exploredStates']} explored states</p>
<ol class="retained-path">{''.join(steps)}</ol>
</div>"""


def bridge_calls(run):
    return sum(expansion['bridgeInvoked'] for expansion in run['expansions'])


def transfer_cases(families):
    cards = []
    for family in families:
        role = 'Held-out family' if family['role'] == 'HELD_OUT' else 'Formation family'
        tasks = []
        for kind, label in [('positive', 'Positive target'), ('negative', 'Negative control')]:
            task = family[kind]
            before, after = task['without'], task['with']
            calls, applications = bridge_calls(after), len(after['bridgeApplications'])
            call_label = 'matcher call' if calls == 1 else 'matcher calls'
            tasks.append(f"""<div class="task">
<h4>{label}</h4>
{expression('Input', task['source'])}
{expression('Requested target', task['target'])}
<p>{calls} {call_label} · {applications} applications in the bridge-enabled arm</p>
<div class="arms">{path_run('Baseline', before)}{path_run('Bridge enabled', after)}</div>
</div>""")
        validation, proof = family['validation'], family['proof']
        cards.append(f"""<details id="transfer-{esc(family['family'])}">
<summary>{esc(family['family'])} <span>{role}</span></summary>
{''.join(tasks)}
<p><b>Bridge validation</b> {code(validation['status'])} · <b>Proof gate</b> {code(proof['eligibility'])}</p>
</details>""")
    return '\n'.join(cards)


def render():
    companion = load('qualification')
    coverage, _ = qualification_summary(load('controller'), companion)
    qualification = companion['qualification']
    utility, evaluation, evidence = (qualification[key] for key in ('utility', 'evaluation', 'evidence'))
    cases = utility['cases']
    baseline = sum(case['baselineExploredStates'] for case in cases)
    candidate = sum(case['candidateExploredStates'] for case in cases)
    positive, negative = (f'{sum(row["passed"] for row in evaluation[kind + "Results"])}/'
                          f'{evaluation["configured" + kind.title() + "Holdouts"]}'
                          for kind in ('positive', 'negative'))
    linked, formed = (sum(int(value.split('/')[field]) for value in coverage.values()) for field in (0, 1))

    (bridge,) = load('transfer')['bridges']
    families = bridge['familyRuns']
    positive_tasks = [family['positive'] for family in families]
    baseline_successes, bridge_successes = (sum(task[arm]['status'] == 'REACHED' for task in positive_tasks)
                                           for arm in ('without', 'with'))
    enabled = [family[kind]['with'] for family in families for kind in ('positive', 'negative')]
    calls = sum(bridge_calls(run) for run in enabled)
    applications = sum(len(run['bridgeApplications']) for run in enabled)
    reached_controls = sum(family['negative'][arm]['status'] == 'REACHED'
                           for family in families for arm in ('without', 'with'))
    fixture = ('regelsuche-release/src/main/java/de/regelsuche/release/'
               'ProductionCandidateQualificationCatalog.java')
    return f"""<!doctype html>
<!-- Generated by docs/demos/render-public-discovery-comparison.py -->
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Public discovery comparison · Regelsuche Workbench</title>
<style>
:root {{ color-scheme: light; --ink: #152e36; --muted: #445e63; --line: #cddbdc; --surface: #edf4f2; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; background: #f5f8f5; color: var(--ink); font: 1rem/1.5 system-ui, sans-serif; }}
main {{ max-width: 78rem; margin: auto; padding: 1.5rem clamp(1rem, 3vw, 2.5rem) 3rem; }}
nav {{ display: flex; gap: 0.5rem 1.5rem; flex-wrap: wrap; }}
h1 {{ font-size: clamp(1.9rem, 4vw, 3rem); line-height: 1.15; margin: 1.1rem 0 0.55rem; }}
h2 {{ font-size: 1.25rem; line-height: 1.25; margin-top: 0; }}
.case-group + .case-group {{ margin-top: 0.6rem; }}
.case-group > div {{ margin-left: 0.8rem; }}
h3 {{ font-size: 1.05rem; margin: 1.25rem 0 0.4rem; }}
h4, h5 {{ font-size: 1rem; margin: 0; }}
a {{ color: #075d78; }}
:focus-visible {{ outline: 3px solid #aa5700; outline-offset: 3px; }}
.intro {{ max-width: 82ch; }}
.comparisons, .arms {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.8rem; }}
.comparisons {{ align-items: start; }}
.panel {{ background: #fff; border: 1px solid var(--line); border-radius: 0.65rem;
          padding: clamp(0.9rem, 2vw, 1.4rem); min-width: 0; }}
.metric {{ display: block; margin-top: 0.75rem; font-size: clamp(1.3rem, 2.8vw, 2rem);
           font-weight: 700; color: #126162; line-height: 1.18; }}
dl, dd {{ margin: 0; }}
.metrics dt {{ font-weight: 650; }}
.metrics dd + dt {{ margin-top: 0.4rem; }}
details {{ border-top: 1px solid var(--line); margin: 0; overflow-wrap: anywhere; }}
summary {{ cursor: pointer; font-weight: 650; padding: 0.6rem 0.25rem; }}
summary span {{ display: block; margin: 0.11rem 0 0 1.15rem; color: var(--muted); font-weight: 500; }}
code {{ font-size: 0.9em; overflow-wrap: anywhere; }}
.task {{ margin: 0.7rem 0; border: 1px solid var(--line); padding: 0.7rem; border-radius: 0.4rem; }}
.arm {{ background: var(--surface); border-radius: 0.4rem; padding: 0.65rem; min-width: 0; }}
.arm p {{ margin: 0.3rem 0; }}
.retained-path {{ margin: 0.5rem 0 0; padding-left: 1.15rem; }}
.retained-path li + li {{ margin-top: 0.35rem; }}
.retained-path small {{ display: block; color: var(--muted); }}
footer {{ margin-top: 1.4rem; overflow-wrap: anywhere; }}
@media (max-width: 900px) {{ .comparisons {{ grid-template-columns: 1fr; }} }}
@media (max-width: 590px) {{ .arms {{ grid-template-columns: 1fr; }} }}
</style>
</head>
<body>
<main>
<header>
<nav aria-label="Regelsuche"><a href="{GITHUB}main/docs/start-here.md">English introduction</a>
<a href="#qualification">Composite-factor cases</a><a href="#transfer">Transfer cases</a></nav>
<h1>When does a learned rewrite help?</h1>
<p class="intro">Inspect two recorded public development benchmarks. Each used its own inputs, candidate and cost model.
Opening a case displays retained evidence. All figures come from the recorded runs.</p>
</header>
<div class="comparisons">
<section class="panel" id="qualification" aria-labelledby="qualificationTitle">
<h2 id="qualificationTitle">Composite-factor qualification
<span class="metric">{baseline} → {candidate} explored states</span></h2>
<p>Across {len(cases)} paired search tasks, {utility['materialGainCount']}/{len(cases)} showed a material gain and
the recording found {utility['correctnessRegressionCount']} correctness regressions.
The rewrite passed {positive} positive holdouts. All {negative} negative controls passed
by emitting zero candidates.</p>
{expression('Retained rewrite', evidence['leftPattern'] + ' → ' + evidence['rightPattern'])}
<p>One qualification run of the shared rewrite is linked to {linked} of {formed} formed lineages.
The tests cover one public held-out composite-factor cluster. Handoffs retain a symbolic polynomial-normal-form check.
Formal proof status is {code(evaluation['proofStatus'])}. Explored states measure search work on these tasks.</p>
<h3>Qualification cases</h3>
<p>The companion retains candidate outputs, search statuses, explored states and path lengths.</p>
<details class="case-group">
<summary>{evaluation['configuredPositiveHoldouts']} positive holdouts and paired search results</summary>
<div id="qualificationPositives">{qualification_cases(qualification, 'positive')}</div>
</details>
<details class="case-group">
<summary>{evaluation['configuredNegativeHoldouts']} negative controls</summary>
<div id="qualificationControls">{qualification_cases(qualification, 'negative')}</div>
</details>
<p>{link('qualification', 'Original qualification evidence (JSON.gz)')} ·
<a href="{GITHUB}{RECORDING_REVISION}/{fixture}">Registered fixture source</a></p>
</section>
<section class="panel" id="transfer" aria-labelledby="transferTitle">
<h2 id="transferTitle">Cross-family transfer
<span class="metric">{calls} matcher calls · {applications} applications</span></h2>
<p>The coefficient-gap-two bridge emitted no moves. Native factorization reached
{baseline_successes}/{len(positive_tasks)} positive targets in the baseline and
{bridge_successes}/{len(positive_tasks)} with the bridge enabled. Both arms ran every negative control, and {reached_controls} of {len(families) * 2}
control runs reached their requested target.
The recorded comparison found no held-out work reduction.</p>
<p>The matcher-call count includes both target types in all four families.
Two families supplied untargeted formation cases and two families were held out.
The paired searches used registered targets, including deliberately inequivalent negative targets.
Transfer status is {code(bridge['transfer']['status'])}, with failed positive bridge validation
in every family. Proof status is {code(bridge['transfer']['proofStatus'])}.</p>
<h3>Paired search paths by family</h3>
<p>For an unreached target, the list shows the path the search retained before stopping.
Open a family to read the expressions, rule IDs and stopping conditions of both arms.</p>
{transfer_cases(families)}
<p>{link('transfer', 'Original transfer evidence (JSON)')} ·
<a href="{GITHUB}{RECORDING_REVISION}/research/benchmarks/track-d-public-transfer/evaluation.json">Registered targets</a></p>
</section>
</div>
<footer>
<p>These recordings cover the named public tasks. The search counts do not measure elapsed time or establish a general learning or transfer advantage.</p>
<p><a href="{GITHUB}{RECORDING_REVISION}/paper/generated/public-discovery-comparisons.md">Benchmark comparison and methods</a></p>
</footer>
</main>
</body>
</html>
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject drift without writing')
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding='utf-8') != expected:
            raise SystemExit('Public comparison drift: rerun docs/demos/render-public-discovery-comparison.py')
        print('Public comparison matches the pinned benchmark evidence.')
    else:
        OUTPUT.write_text(expected, encoding='utf-8')
        print(OUTPUT.relative_to(ROOT))


if __name__ == '__main__':
    main()
