window.BENCHMARK_DATA = {
  "lastUpdate": 1779489944308,
  "repoUrl": "https://github.com/carstenartur/Regelsuche",
  "entries": {
    "Regelsuche JMH Benchmarks": [
      {
        "commit": {
          "author": {
            "email": "198982749+Copilot@users.noreply.github.com",
            "name": "Copilot",
            "username": "Copilot"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "63054d349830764238552b5f92ea0014c7c13dd2",
          "message": "Stage 2: aligned \\begin{aligned} derivation block in replay tab (#16)",
          "timestamp": "2026-05-23T00:44:18+02:00",
          "tree_id": "c1995edef5a7b79d619fdf95d1e9e155b43cd56d",
          "url": "https://github.com/carstenartur/Regelsuche/commit/63054d349830764238552b5f92ea0014c7c13dd2"
        },
        "date": 1779489942937,
        "tool": "jmh",
        "benches": [
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.canonicalizeBinomial",
            "value": 1.1287697678005468,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.canonicalizeMedium",
            "value": 2.9534254977086696,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.egraphAddAndRebuildMedium",
            "value": 4.828669348705844,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.egraphRebuildSmall",
            "value": 1.579876755549429,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.rewriteApplyAllBinomial",
            "value": 80.10985517937773,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "de.regelsuche.benchmark.CoreBenchmarks.rewriteApplyAllMedium",
            "value": 160.49738473048862,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}