# Quellstand der korrigierten JDK-Kontrolle

Der öffentliche Kontrolllauf wurde mit Commit `a81b9b72b20b421c8dce43699b6e4f1128730cbc` erzeugt. Sein Git-Bundle bewahrt diesen Commit und seine Quellhistorie ab der öffentlichen Voraussetzung `d49594a351b7651c7c44cf8c24c4821dafbfa6f0`. Die Messung und die Archividentität haben getrennte Belege. [Kontrolllauf](review-control-receipt.json) · [Bundle-Prüfsumme und Git-Objekte](review-source.json) · [Früherer Kontrolllauf](earlier-receipt.json)

Das folgende Beispiel aus diesem Verzeichnis prüft das Bundle und importiert den ursprünglichen Commit in ein neues Repository.

```bash
(
  set -eu
  bundle="$PWD/review-source.bundle"
  repo=$(mktemp -d)
  base=d49594a351b7651c7c44cf8c24c4821dafbfa6f0
  ref=refs/heads/historical/748-strict-jdk-producer
  echo "02d0cff9bd035a2962254c1c8585a89e44761d4210d2e0e617097df346f4e3ba  $bundle" | sha256sum --check
  git init --quiet "$repo"
  cd "$repo"
  git fetch --no-tags https://github.com/carstenartur/Regelsuche.git "$base:refs/heads/public-base"
  git bundle verify "$bundle"
  git fetch --no-tags "$bundle" "$ref:$ref"
  test "$(git rev-parse "$ref")" = a81b9b72b20b421c8dce43699b6e4f1128730cbc
  test "$(git rev-parse "$ref^{tree}")" = f9bd02419c6ebb448e38e319aa29392853fb5cd9
  git fsck --full --no-dangling
)
```
