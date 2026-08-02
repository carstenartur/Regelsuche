# Regel-Tiers und Ablation

Regelsuche unterscheidet drei Tiers von Regelbeiträgen. Das Tier entscheidet, ob ein Paket für
einen Baseline-Lauf abgeschaltet werden darf. Ablation ist damit eine **Deklaration** aus Profil,
expliziten Pack-Schaltern und Manifest-Hash – keine Deployment-Entscheidung darüber, welche JARs
im Plugin-Verzeichnis liegen.

| Tier | ID | Herkunft | Abschaltbar |
|---|---|---|---|
| Kernel | `kernel` | eingebaute Core-Packs mit grundlegender Normalisierungsfunktion | nein |
| First-Party | `first-party` | eingebaute Core-Packs und Knowledge Packs mit mathematischen Transformationen | ja |
| Plugin | `plugin` | Beiträge über die Plugin-SPI (`RegelsuchePlugin`) | ja, über bestehende Plugin-/Regel-Deaktivierung |

## Core-Packs

`CoreRuleCatalog` klassifiziert jede eingebaute Regel aus
`AstRewriteTransformationEngine.allBuiltInRules()` in genau ein Pack:

| Pack | Tier | Standard | Inhalt |
|---|---|---:|---|
| `core-identities` | kernel | an | Neutrale und absorbierende Elemente (`x+0`, `x*1`, `x*0`, `x-0`, `x/1`) |
| `core-normalization` | kernel | an | Kanonische Normalisierung und numerisches Falten |
| `core-power-rules` | first-party | an | Potenz-/Produktumformungen und Exponentenarithmetik |
| `core-term-collection` | first-party | an | Zusammenfassen gleicher und linearer Terme |
| `core-distribution` | first-party | an | Distributive Expansion über Summen und Differenzen |
| `core-factorization` | first-party | an | Gemeinsame Faktoren und dritte binomische Formel |
| `core-polynomial-division` | first-party | an | Atomare Faktorkürzung mit expliziter Nebenbedingung |
| `core-exact-polynomial-division` | first-party | **aus** | Experimentelle exakte univariate Polynomdivision |

Die Packs sind Markierungen über der kanonischen Regelreihenfolge, keine Container.
`allBuiltInRules()` enthält auch experimentelle, standardmäßig deaktivierte Regeln. `defaultRules()`
liefert dagegen nur die für das gewählte Profil aktivierten Packs. Dadurch kann eine neue Fähigkeit
implementiert und getestet werden, ohne einen bereits festgelegten Benchmark nachträglich innerhalb
des gemessenen Standardinventars zu schließen.

Eine neue eingebaute Regel muss einem Pack zugeordnet werden; andernfalls schlägt
`defaultRules(...)` mit `IllegalStateException` fehl.

## Profile

`RuleProfile` steuert, welche Packs standardmäßig aktiv sind:

- `core` – Kernel plus die standardmäßig aktivierten First-Party-Packs.
- `full` – expliziter Alias für `core`; experimentelle Opt-in-Packs bleiben aus.
- `minimal-kernel` – nur Kernel-Packs.
- `all` – alle Core- und Knowledge-Packs, einschließlich experimenteller Opt-in-Packs.
- `core+sympy-polynomial`, `exploratory` – bestehende Knowledge-Pack-Profile.

Einzelne Packs lassen sich zusätzlich über `KnowledgePackSelection#enablePack` und
`#disablePack` aktivieren beziehungsweise abschalten. Die experimentelle Polynomdivision wird zum
Beispiel explizit mit `--enable-pack core-exact-polynomial-division` zugeschaltet. Ein Kernel-Pack
abzuschalten wird mit `IllegalArgumentException` abgelehnt.

## Regelinventar-Manifest

`RuleInventoryBuilder` erzeugt ein `RuleInventoryManifest` über alle drei Tiers: Core-Packs,
Knowledge Packs und Plugin-Beiträge. Das Manifest enthält die Profil-ID sowie pro Pack Tier,
Quelle, Aktivierungsstatus und die konkreten zugeordneten Regel-IDs. Zusätzlich enthält es die
effektiven aktiven Regel-IDs und einen SHA-256-Hash über die kanonische Serialisierung.

Der Hash ist unabhängig von der Aufzählungsreihenfolge, ändert sich aber auch dann, wenn zwei
gleich große Packs Regeln untereinander austauschen. Damit bezeichnet der Hash nicht nur eine
Regelanzahl, sondern die konkrete Pack-Zuordnung des ausgeführten Regelinventars.

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

1. Neue YAML-Datei unter `regelsuche-core/src/main/resources/rules/packs/` oder Zuordnung einer
   Java-Regel in `CoreRuleCatalog`.
2. `tier: first-party`, `maturity: EXPERIMENTAL`, `enabledByDefault: false`.
3. Gegen das unveränderte Standardinventar und ein explizites Opt-in-Profil messen.
4. Erst nach unabhängiger Validierung gegebenenfalls `enabledByDefault: true` setzen und die
   Benchmarkversion bewusst erhöhen.

Die Plugin-SPI bleibt fremdem Code mit eigenem ClassLoader, Trust Store und Artefaktprüfung
vorbehalten.
