# Verifizierte Faktorisierung unter den eingefrorenen Budgets

Zwei neue Ausführungsvarianten verwenden dieselben öffentlichen Fälle, sechs
Checkpoints und unveränderten primitiven, mechanischen und faktorisierenden
Arbeitsgrenzen des [ursprünglichen Vergleichs](polynomial-theory-runtime/public-profile-comparison.tsv).
Jede Variante prüfte 120 Zeilen mit 144 ausgewählten Vorkommen.

| Varianten | Vollendete Übergänge | Cache-Einfügungen | Erneut geprüfte Cache-Übergänge |
| --- | ---: | ---: | ---: |
| Historische direkte Faktorisierung | 0 | 0 | 0 |
| Neue direkte Faktorisierung mit quadratischen Polynomen | 7 | 0 | 0 |
| Neue direkte Faktorisierung mit quadratischen Polynomen in einer Potenz | 14 | 0 | 0 |
| Historischer Cache | 0 | 0 | 0 |
| Quadratische Polynome mit Sitzungs-Cache | 8 | 6 | 2 |
| Quadratische Polynome in einer Potenz mit Sitzungs-Cache | 15 | 13 | 2 |

Die Faktorisierung von `x^2-1` benötigt im neuen direkten Profil 248 der 256
mechanischen Einheiten und 19 der 64 Faktorisierungseinheiten. Im neuen
Sitzungs-Cache erfolgt die erste Einfügung im selben Fall bei 230 und 19
Einheiten. Die beiden erneuten Anwendungen im Fall
`four-identical-occurrences` bei vollem Budget kosten kumulativ 516 und 995 von
1.024 mechanischen Einheiten. Der Faktoraufwand der Zeile beträgt null.
Jede Anwendung wiederholt die quellengebundene Prüfung und die
Ersetzungsprüfung. Das direkte Profil schließt in dieser Zeile einen
Übergang ab und benötigt dafür 678 mechanische und 19 Faktorisierungseinheiten.

Die Erweiterung auf quadratische Polynome in einer Potenz erreicht bei
`x^10-1` die Darstellung `(x^5+1)*(x^5-1)` schon bei halbem Budget. Bei
vollem Budget sinkt die Arbeit im direkten Profil von 1.396 auf 608
mechanische Einheiten und von 37 auf 21 Faktorisierungseinheiten. Auch
`x^8-17*x^4+16` und `x^4-2*x^2+1` liefern erstmals verifizierte Übergänge.
Die Werte vor der Erweiterung sind im Commit `f56c521` archiviert.

Bei einem vollständig ausgewählten, vom Parser ausgestellten Eingabeterm
verwendet die neue Projektion bereits vorhandene AST-Bereiche und
Literalbelege. Sie prüft weiterhin Quellbindung, erwarteten Formattertext und
Größenlimits. Für ausgewählte Teilbäume und äußere Leerzeichen bleibt die
reguläre Projektion erforderlich. Die rationale Engine verwendet die exakte
Diskriminante für quadratische Polynome einschließlich der Form
`a*(x^k)^2+b*x^k+c`. Sie prüft dazu die Exponenten aller vorhandenen Terme.
Die synthetische Division verwendet nachgewiesene Wurzeln bei `1` oder
`-1` in anderen höhergradigen Polynomen. Der unabhängige
Produktprüfer bestätigt jeden Vorschlag. Ein gültiges Produkt kann noch
zusammengesetzte Faktoren enthalten. Ungeklärte Fälle können mit dem
verbliebenen Budget an die allgemeine Engine weitergereicht werden.

Der Sitzungs-Cache hält die bereits ausgestellte Autorisierung in einem
begrenzten FIFO-Index. Ein Schlüssel aus dem ausgewählten Quelltext dient
als Vorschlag für die erneute Prüfung. Der Cache erzeugt keinen zweiten
Zertifikatsgraphen. Stattdessen verwendet der Prüfer die ursprüngliche
Autorisierung und prüft die neue AST-Position frisch. Drei weitere
Cache-Treffer scheiterten an der mechanischen Arbeitsgrenze. Der
Vergleichsexport weist die neuen Sitzungsoperationen und Pipeline-Zertifikate
aus. Er dient als Entwicklungsmessung neben dem historischen Exportvertrag.

## Reproduktion

Die beiden TSV-Dateien enthalten auch die nicht vollendeten Vorkommen.
Die Arbeitswerte sind innerhalb derselben Zeile kumulativ. Pro
Checkpoint beginnt der Cache leer.

- [Direkte Variante](polynomial-fast-path-2026-11-24/direct.tsv)
- [Variante mit Sitzungs-Cache](polynomial-fast-path-2026-11-24/cache.tsv)

```sh
output=$(mktemp -d)
mvn -B -ntp -pl regelsuche-experiments -am \
  -Dtest=PolynomialTheoryUtilityFastPathComparisonTest,PolynomialTheoryUtilityPublicProfileComparisonTest,PolynomialTheoryUtilityMeasuredCacheTest,ExactParsedSubtermProjectorTest,ExpressionParserNodeRangeTest,NativeUnivariateFactorizationEngineTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DpolynomialFastPathComparisonDirectory="$output" test
cmp "$output/direct.tsv" docs/research/polynomial-fast-path-2026-11-24/direct.tsv
cmp "$output/cache.tsv" docs/research/polynomial-fast-path-2026-11-24/cache.tsv
```

## Grenze des Befunds

Die öffentlichen Fälle dienten der Entwicklung. Die Arbeitswerte messen
logische Operationen. Laufzeit, CPU und RSS wurden hier nicht gemessen.
Das externe GraalPy/SymPy-Profil fehlt weiterhin in 120 Zeilen. Für dieses
Profil fehlen eine vollständige quellengebundene Arbeitsrechnung und eine
zum eingefrorenen Plan passende Runtime-Sperre. Die Messung belegt deshalb
eine Verbesserung gegenüber der bisherigen nativen Ausführung auf diesen
öffentlichen Fällen. Externe Parität und die geschützte Qualifikation
stehen aus. [Diagnose](polynomial-profile-diagnosis-2026-11-24/README.md)
