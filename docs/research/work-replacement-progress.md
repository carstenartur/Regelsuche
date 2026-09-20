# Work replacement implementation evidence

## M0 — integrated

Baseline: `7aec9ae0a1619dda98f859d1277ac8b287471423`. Its tree equals reviewed/tested `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`.

- #1047 head `ab55d6aff6cfd252733f2b519932e969c171008d`, CI 35513137346: all required authorities successful; merge `79f785c572dbc9e0afdb398b92e5fccd716b2872`.
- #1048 head `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`, CI 35514858944: all required authorities successful; merge is baseline above. Hosted artifact ID 10606910982, reported SHA256 `c28f72b376d96d73860a6b882edf9e5d288fc4c1f17fe857119e14c898f7bee7`.
- Fresh local Java 25.0.2: `./gradlew --no-daemon :regelsuche-search:test :regelsuche-learning:test` succeeded. JUnit XML: 471 search + 914 learning tests, zero failures/errors/skips.
- Independent code review found no blocking issue. The inherited extreme-score JSON aggregate overflow is assigned to P01; long work overflow must continue to fail explicitly.
- Existing negative learning results and all quality gates remain unchanged. P01–P12 are pending.
