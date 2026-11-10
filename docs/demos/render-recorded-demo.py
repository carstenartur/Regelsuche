#!/usr/bin/env python3
"""Render a self-contained presentation of retained evidence; no mathematical work."""
from pathlib import Path
import argparse
import hashlib
import html
import json

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
EVIDENCE = ROOT / 'docs/generated/discovery/sophie-germain/evidence.json'
RECORDING = HERE / 'released-sdk-recording.json'


def render():
    evidence_bytes = EVIDENCE.read_bytes()
    evidence = json.loads(evidence_bytes)
    recording = json.loads(RECORDING.read_bytes())
    source = ROOT / recording['sourceFile']
    if hashlib.sha256(source.read_bytes()).hexdigest() != recording['sourceSha256']:
        raise ValueError('SDK example changed since the recorded execution; re-record it')
    payload = {'evidence': evidence, 'recording': recording,
               'evidenceFile': EVIDENCE.relative_to(ROOT).as_posix(),
               'evidenceFileSha256': hashlib.sha256(evidence_bytes).hexdigest()}
    # Keep JSON inert even if a retained label ever contains an HTML end tag.
    data = json.dumps(payload, ensure_ascii=False).replace('<', '\\u003c')
    template = '''<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Regelsuche — a recorded search, with evidence</title>
<style>
:root{color-scheme:light;--ink:#152e36;--muted:#4b656b;--line:#cddbdc;--teal:#076969;--paper:#f5f8f5}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:17px/1.6 system-ui,sans-serif}
main{max-width:1100px;margin:auto;padding:38px 24px 70px}header{padding-bottom:26px;border-bottom:1px solid var(--line)}
.eyebrow{font:700 12px/1.5 system-ui;letter-spacing:.13em;text-transform:uppercase;color:var(--teal)}
h1{font-size:clamp(2rem,5vw,3.5rem);line-height:1.13;max-width:900px;margin:12px 0 20px}h2{font-size:1.45rem;line-height:1.3;margin-top:0}
p{max-width:85ch}.lead{font-size:1.15rem;color:var(--muted)}a{color:#075d78}a:focus-visible,button:focus-visible,summary:focus-visible{outline:3px solid #aa5700;outline-offset:4px}
nav{display:flex;gap:12px;flex-wrap:wrap;margin-top:22px}nav a{padding:8px 13px;background:white;border:1px solid var(--line);border-radius:6px}
section{margin-top:28px;padding:25px;background:#fff;border:1px solid var(--line);border-radius:10px}.badge{display:inline-block;background:#e4f3ed;padding:3px 10px;border-radius:4px;font-size:13px;font-weight:700}
.notice{background:#fff3db;border-left:4px solid #b87816;padding:14px 18px;margin:20px 0}.controls{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:18px 0}
button{font:inherit;cursor:pointer;background:var(--teal);color:#fff;border:0;border-radius:6px;padding:10px 18px}button:disabled{opacity:.45;cursor:default}
#stepLabel{font-weight:700}.expression{font:clamp(15px,2.2vw,21px)/1.7 ui-monospace,monospace;overflow-wrap:anywhere;padding:22px;background:#f0f6f5;border-radius:6px;min-height:100px}
.rule{color:var(--muted);font:14px/1.6 ui-monospace,monospace;overflow-wrap:anywhere;margin-top:12px}table{border-collapse:collapse;width:100%;font-size:15px}th,td{padding:11px 12px;text-align:left;border-bottom:1px solid var(--line)}th{background:#edf4f2}
.table-wrap{overflow:auto}pre{overflow:auto;background:#142e35;color:#e6f4ee;padding:18px;border-radius:6px;font:14px/1.7 ui-monospace,monospace}code{overflow-wrap:anywhere}details{margin:18px 0}summary{cursor:pointer;font-weight:700}.small{font-size:14px;color:var(--muted)}footer{margin-top:25px;font-size:14px}
@media(max-width:600px){main{padding:24px 14px 45px}section{padding:18px}th,td{padding:9px 7px}}
</style>
<main>
<header>
<div class="eyebrow">Mathematik ist ein Spiel · Regelsuche</div>
<h1>A search you can inspect.<br>Evidence you can question.</h1>
<p class="lead">Step through a real retained factorisation trace, compare a reused macro,
and inspect the output of an external Java consumer running the released SDK.</p>
<span class="badge">Recorded evidence · works offline · no server needed</span>
<nav aria-label="Primary workflows">
<a href="../start-here.md">English introduction</a><a href="../user-workflows.md">Explore derivations</a>
<a href="../autonomous-discovery-walkthrough.md">Study learned reuse</a>
<a href="../../regelsuche-optimization-sdk/README.md">Derive computation plans</a>
</nav>
</header>
<section aria-labelledby="traceHeading">
<div class="eyebrow">01 · Retained search path</div>
<h2 id="traceHeading">Expose a difference of squares</h2>
<p>The historical Sophie-Germain gallery starts with <code>x^4 + 4*y^4</code>.
A preparation step exposes a familiar structure; a primitive rule factors it.
The expressions and rule IDs below come directly from the embedded evidence.</p>
<div class="controls"><button id="previous" type="button">Previous</button><span id="stepLabel"></span><button id="next" type="button">Next step</button></div>
<div id="step" aria-live="polite" aria-atomic="true"><div id="expression" class="expression"></div><div id="rule" class="rule"></div></div>
<noscript><p>JavaScript is disabled. The recorded baseline path is:</p><ol>__STATIC_PATH__</ol></noscript>
<p class="small">This is a historical development gallery with a supplied syntactic target.
It is not the newer target-free autonomous-discovery experiment.</p>
</section>
<section aria-labelledby="comparisonHeading">
<div class="eyebrow">02 · Reuse, with its limits</div>
<h2 id="comparisonHeading">Both paths reach the target</h2>
<div class="table-wrap"><table><thead><tr><th>Recorded run</th><th>Success</th><th>Explored states</th><th>Path edges</th></tr></thead><tbody id="comparison"></tbody></table></div>
<p>The macro expands the same retained preparation and factorisation path.
These counts describe this one known case. Acquisition, rejected candidates,
selection, loading and verification costs are not a full economic comparison.</p>
<div class="notice"><strong>Keep the original status visible.</strong>
The source records <code>validationStatus=PASS</code>, <code>oracleStatus=UNAVAILABLE</code>
and an ablation status of <code>DEGRADED</code>. A successful path and fewer explored
states do not establish an independent formal proof, measured speedup, general
learning advantage or external mathematical novelty.</div>
<details><summary>Inspect the reused macro path</summary><ol id="macroPath"></ol></details>
</section>
<section aria-labelledby="sdkHeading">
<div class="eyebrow">03 · Released binaries, independent consumer</div>
<h2 id="sdkHeading">One rule outside the source checkout</h2>
<p>On 10 November 2026, Java 25 compiled and ran the hello-rule consumer against
the actual four required runtime JARs from <strong>SDK 0.5.2</strong>. Its
ServiceLoader plugin removes zero, checks the expected output, rejects
<code>x + 1</code> and reruns the retained rule/input pair.</p>
<pre id="sdkOutput"></pre>
<p>Here the author supplies the identity <code>a + 0 = a</code>. Deterministic
replay does not prove arbitrary executable plugin code. No external proof backend ran.</p>
<p class="notice" id="mavenResult"></p>
<p><a href="../java-sdk-quickstart.md">Run the released SDK + Maven example</a> ·
<a href="../../examples/external-consumers/hello-rule-java25/README.md">Consumer source and commands</a></p>
</section>
<section aria-labelledby="evidenceHeading">
<div class="eyebrow">04 · Provenance and reproduction</div>
<h2 id="evidenceHeading">Keep the recording attached to its source</h2>
<p>The complete historical search JSON is embedded in this file. The SDK record
contains the archive hash, source hash, tool version, exact stdout and the
Maven limitation. Viewing this recording performs no new search or verification.</p>
<dl><dt>Retained source file</dt><dd><code id="evidenceFile"></code></dd>
<dt>Source file SHA-256</dt><dd><code id="fileHash"></code></dd>
<dt>Canonical evidence ID (as recorded)</dt><dd><code id="semanticId"></code></dd>
<dt>SDK archive SHA-256</dt><dd><code id="sdkHash"></code></dd></dl>
<div class="controls"><button id="download" type="button">Download embedded evidence</button></div>
<details><summary>Exact recording metadata</summary><pre id="metadata"></pre></details>
<p><a href="README.md">Reproduction and limitations</a> ·
<a href="../generated/discovery/sophie-germain/evidence.json">Original search JSON</a> ·
<a href="https://github.com/carstenartur/Regelsuche/releases/tag/v0.5.2">Published v0.5.2 release</a></p>
</section>
<footer>Research software with bounded capabilities. Human-DX evaluation has not yet been conducted.
<a href="../java-sdk-human-dx.md">Prepared evaluation tasks</a>.</footer>
</main>
<script id="recordedEvidence" type="application/json">__DATA__</script>
<script>
'use strict';
const recording = JSON.parse(document.getElementById('recordedEvidence').textContent);
const evidence = recording.evidence, sdk = recording.recording;
const byId = id => document.getElementById(id);
const baseline = evidence.withoutMacroRun, macro = evidence.withMacroRun;
let selected = 0;
function displayStep() {
  byId('expression').textContent = baseline.path[selected];
  byId('rule').textContent = selected === 0 ? 'Retained input' : 'Applied rule: ' + baseline.appliedRuleIds[selected - 1];
  byId('stepLabel').textContent = 'State ' + (selected + 1) + ' of ' + baseline.path.length;
  byId('previous').disabled = selected === 0;
  byId('next').disabled = selected === baseline.path.length - 1;
}
byId('previous').addEventListener('click', () => { if (selected > 0) selected--; displayStep(); });
byId('next').addEventListener('click', () => { if (selected < baseline.path.length - 1) selected++; displayStep(); });
for (const [label,run] of [['Primitive baseline',baseline],['With retained macro',macro]]) {
  const row = document.createElement('tr');
  for (const value of [label,run.success ? 'yes' : 'no',run.analytics.statesExplored,run.path.length - 1]) {
    const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
  }
  byId('comparison').append(row);
}
for (const expression of macro.path) { const item = document.createElement('li'); item.textContent = expression; byId('macroPath').append(item); }
byId('sdkOutput').textContent = sdk.stdout;
byId('mavenResult').textContent = sdk.mavenResult;
byId('evidenceFile').textContent = recording.evidenceFile;
byId('fileHash').textContent = recording.evidenceFileSha256;
byId('semanticId').textContent = evidence.canonicalEvidenceId;
byId('sdkHash').textContent = sdk.archiveSha256;
byId('metadata').textContent = JSON.stringify(sdk,null,2);
byId('download').addEventListener('click', () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(recording,null,2)+'\\n'],{type:'application/json'}));
  const link = document.createElement('a'); link.href = url; link.download = 'regelsuche-recorded-evidence.json'; link.click();
  setTimeout(() => URL.revokeObjectURL(url),1000);
});
displayStep();
</script>
</html>
'''
    static_path = ''.join('<li><code>' + html.escape(item) + '</code></li>'
                          for item in evidence['withoutMacroRun']['path'])
    links = {
        '../start-here.md': 'docs/start-here.md',
        '../user-workflows.md': 'docs/user-workflows.md',
        '../autonomous-discovery-walkthrough.md': 'docs/autonomous-discovery-walkthrough.md',
        '../../regelsuche-optimization-sdk/README.md': 'regelsuche-optimization-sdk/README.md',
        '../java-sdk-quickstart.md': 'docs/java-sdk-quickstart.md',
        '../../examples/external-consumers/hello-rule-java25/README.md': 'examples/external-consumers/hello-rule-java25/README.md',
        'README.md': 'docs/demos/README.md',
        '../generated/discovery/sophie-germain/evidence.json': 'docs/generated/discovery/sophie-germain/evidence.json',
        '../java-sdk-human-dx.md': 'docs/java-sdk-human-dx.md',
    }
    for relative, remote in links.items():
        template = template.replace('href="' + relative + '"',
                                    'href="https://github.com/carstenartur/Regelsuche/blob/main/' + remote + '"')
    return template.replace('__STATIC_PATH__', static_path).replace('__DATA__', data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject drift without writing')
    args = parser.parse_args()
    output = HERE / 'recorded-discovery.html'
    expected = render()
    if args.check:
        if not output.exists() or output.read_text() != expected:
            raise SystemExit('Recorded demo drift: rerun docs/demos/render-recorded-demo.py')
        print('Recorded demo matches retained evidence and recorded consumer source hash.')
    else:
        output.write_text(expected)
        print(output.relative_to(ROOT))


if __name__ == '__main__':
    main()
