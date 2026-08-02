# Regel-Tiers und Ablation

Regelsuche unterscheidet drei Tiers von Regelbeiträgen. Das Tier entscheidet, ob ein Paket für
einen Baseline-Lauf abgeschaltet werden darf. Ablation ist damit eine **Deklaration** (Profil-ID
plus Manifest-Hash) und keine **Deployment-Entscheidung** (welche JARs im Verzeichnis liegen).

| Tier | ID | Herkunft | Abschaltbar |
|---|---|---|---|
| Kernel | `kernel` | eingebaute Core-Packs mit Soundness-/Terminierungsannahmen | nein |
| First-Party | `first-party` | eingebaute Core-Packs und Knowledge Packs mit Abkürzungen im Transformationsraum | ja |
| Plugin | `plugin` | Beiträge über die Plugin-SPI (`RegelsuchePlugin`) | ja, über bestehende Plugin-/Regel-Deaktivierung |

## Core-Packs

`CoreRuleCatalog` klassifiziert jede eingebaute Regel aus
`AstRewriteTransformationEngine.allBuiltInRules()` in genau ein Pack:

| Pack | Tier | Inhalt |
|---|---|---|
| `core-identities` | kernel | Neutrale und absorbierende Elemente (`x+0`, `x*1`, `x*0`, `x-0`, `x/1`) |
| `core-normalization` | kernel | Kanonische Normalisierung und numerisches Falten |
| `core-power-rules` | first-party | Potenz-/Produktumformungen und Exponentenarithmetik |
| `core-term-collection` | first-party | Zusammenfassen gleicher und linearer Terme |
| `core-distribution` | first-party | Distributive Expansion über Summen und Differenzen |
| `core-factorization` | first-party | Gemeinsame Faktoren und dritte binomische Formel |
| `core-polynomial-division` | first-party | Kürzen und exakte Polynomdivision |

Die Packs sind Markierungen über der kanonischen Regelreihenfolge, keine Container. Sind alle Packs
aktiv, liefert `defaultRules()` exakt dieselbe Liste in derselben Reihenfolge wie vor der
Tier-Aufteilung. Wird ein Pack abgeschaltet, entfallen genau dessen Regeln.

Eine neue eingebaute Regel muss einem Pack zugeordnet werden; andernfalls schlägt
`defaultRules()` mit `IllegalStateException` fehl.

## Profile

`RuleProfile` steuert, welche Packs standardmäßig aktiv sind:

- `core` – bisheriges Standardverhalten: Kernel plus alle First-Party-Packs.
- `full` – expliziter Alias für `core`.
- `minimal-kernel` – Baseline für Basisbeweise: nur Kernel-Packs, alle abschaltbaren Packs aus.
- `core+sympy-polynomial`, `exploratory`, `all` – bestehende Knowledge-Pack-Profile.

Einzelne Packs lassen sich zusätzlich über `KnowledgePackSelection#enablePack` und
`#disablePack` aktivieren beziehungsweise abschalten. Ein Kernel-Pack abzuschalten wird mit
`IllegalArgumentException` abgelehnt, damit ein Ablationslauf keine soundness-kritischen Regeln
stillschweigend entfernt.

## Regelinventar-Manifest

`RuleInventoryBuilder` erzeugt ein `RuleInventoryManifest` über alle drei Tiers: Core-Packs,
Knowledge Packs und Plugin-Beiträge. Das Manifest enthält die Profil-ID, pro Pack Tier, Quelle,
Aktivierungsstatus und Regelanzahl sowie die effektiven Regel-IDs und einen SHA-256-Hash über die
kanonische Serialisierung. Der Hash ist unabhängig von der Aufzählungsreihenfolge und beantwortet
die Reviewfrage „was war für diesen Beweis genau aktiv?".

`PluginRuntime#ruleInventoryManifest()` liefert das Manifest der Laufzeit.

## CLI

```
rules packs [--rule-profile minimal-kernel] [--enable-pack a,b] [--disable-pack c]
rules list  [--rule-profile minimal-kernel] [--enable-pack a,b] [--disable-pack c]
```

`rules packs` listet alle Core-Packs mit Tier, Aktivierungsstatus und Regelzuordnung.
`rules list` ergänzt Plugin- und Regeldatei-Beiträge und gibt am Ende Profil, Regelanzahl und
Manifest-Hash aus.

## Neue Transformationen ergänzen

Zusätzliche Transformationen werden als neues Pack ergänzt, nicht als neues Plugin:

1. Neue YAML-Datei unter `regelsuche-core/src/main/resources/rules/packs/`.
2. `tier: first-party`, `maturity: EXPERIMENTAL`, `enabledByDefault: false`.
3. Nach Benchmarks unter `full` und `minimal-kernel` auf `VALIDATED` und gegebenenfalls
   `enabledByDefault: true` hochstufen.

Rein Java-seitige Regeln werden stattdessen einem Core-Pack in `CoreRuleCatalog` zugeordnet.
Die Plugin-SPI bleibt dem vorbehalten, wofür sie gedacht ist: fremder Code mit eigenem
ClassLoader, Trust Store und Artefaktprüfung.
