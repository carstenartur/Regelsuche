# Original CI JMH captures

Each directory contains unmodified result.json and the corresponding producer decision, report.json. The replay checks SHA-256 of both members, all 29 result rows, the complete decision document and the process-level success or failure. The only machine-local report fields are the two absolute input policy paths.

| CI run, attempt 1 | Source revision | Archive ID and SHA-256 | Gate |
| --- | --- | --- | --- |
| [44712656489](https://github.com/carstenartur/Regelsuche/actions/runs/44712656489) | f10e4c0875e1e76288b2332191bba5b78b2f53dc | 14621363258, f46c71950dfd711affa26ec8bc6e92ffcb17e3735b1a9e501134489aef5b3416 | Six warmups, PASSED |
| [35417755959](https://github.com/carstenartur/Regelsuche/actions/runs/35417755959/attempts/1) | 4ac122f3b012d12529dd10be907e5236d7615d84 | 10576283229, 5984e31f78dbe03514ecc2a36ad9df494ec92fc6a58465500c7864b7a209c1ca | Two warmups, INCONCLUSIVE |

Archive members are app/build/reports/jmh/result.json and build/reports/quality/jmh-regression-report.json, with a files/ prefix in the quality-acceptance archive. Replay uses each run's original threshold and decision policies. A fresh run is measured independently.

Run 44712656489 also supplies the original factorization matrix and its Python-produced v2 summary. The raw member `files/regelsuche-math-sympy/build/reports/jmh/sympy-factorization.json` has SHA-256 `649dc833236d10ca9da371431f455a5afa8c0e4fcbcdc231068d55b10abbd65d`. The corresponding `files/public/dev/bench/sympy-factorization.json` has SHA-256 `983e40decaca7f7924023a009c107ebe55f374268019cae66aef3962c7e2589b`.
