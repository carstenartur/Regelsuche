# Module Structure (Teil 0)

Regelsuche wird schrittweise von einem physischen `app`-Modul auf eine
fachlich getrennte Zielarchitektur vorbereitet. Bis zur physischen Aufteilung
gelten folgende **logische Module** als verbindliche Arbeitsgrenzen.

| Zielmodul | Aktuelle Paketbasis (Auszug) | Verantwortung |
| --- | --- | --- |
| `regelsuche-core` | `de.regelsuche.ast`, `parse`, `canonical`, `rules`, `transform` | AST, Parser, kanonische Form, Rewrite-Regeln/Pattern-Ausdrücke |
| `regelsuche-search` | `de.regelsuche.search`, `scoring`, `jobs`, `paths` | Strategien, Suche, Kostenmodelle, Suchablauf |
| `regelsuche-egraph` | `de.regelsuche.egraph` | E-Graph, Equality Saturation, Pattern-Matching |
| `regelsuche-learning` | `de.regelsuche.learning`, `mining`, Teile von `discovery` | Makroregel-Lernen, Hypothesen, Anti-Unification-nahe Generalisierung |
| `regelsuche-validation` | `de.regelsuche.validation`, `equivalence`, `assumption` | Gegenbeispiele/Validierung, Äquivalenzchecks, Annahmen |
| `regelsuche-persistence` | `de.regelsuche.persistence`, `graph`, `inventory`, `checkpoint`, JSON/Neo4j-Repositories | Speichern/Laden, Trace-/Graph-/Rule-Store |
| `regelsuche-experiments` | `de.regelsuche.benchmark`, experimentnahe Pfade in `demo` | Benchmarks, reproduzierbare Auswertung, Seed-Corpora |
| `regelsuche-web` | `de.regelsuche.web`, `api`, `export`, `explain`, `didactic`, `proof` | REST, UI, Replay, Reports, didaktische und Proof-Endpunkte |
| `regelsuche-cli` | `de.regelsuche.cli`, `de.regelsuche.App` | CLI-Entry-Points und Runtime-Wiring |

Hinweis: Die Tabelle bildet die aktuelle Realität in einem Modul ab, damit
Features isoliert entwickelt und später mit geringem Risiko physisch getrennt
werden können.
