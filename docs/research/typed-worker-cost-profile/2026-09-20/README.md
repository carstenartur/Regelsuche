# Uncached typed worker cost attribution

The unmodified profiling report, counting scripts, sampling plan, runtime
inventory, manifest and per-process metadata are retained here. The full JFR,
compressed worker responses, samples and actual executable snapshot are in the
separately delivered `regelsuche-effizienz-v2-rohdaten.zip`, under
`typed-worker-profile-baseline/`. The manifest records their original hashes.

This observation predates the scoped codec cache and checked schemas. It is
not an equal-query speed comparison and does not attribute an architecture
gain to learning. See `report.md` for the exact limitations and attribution.
The scripts retain their actual executed scratch paths as historical evidence;
when reproducing, supply the equivalent checkout, Java 25 and snapshot locations.

The independently paid comparison of the new implementation uses the separate
frozen `config/benchmarks/learned-schema-efficiency-v2.json` protocol and
`python -m external_polynomial_comparison.run_schema`. Its runtime is identified
independently of the Git source revision.
