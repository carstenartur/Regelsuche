# Web-Workbench — Benutzerhandbuch

Die Web-Workbench ist ein lokal laufender, eingebetteter HTTP-Server
(`de.regelsuche.web.WebWorkbenchServer`), der die REST-API hinter
einer Single-Page-Oberfläche zugänglich macht. Sie ist als
*Explorations-Werkzeug* gedacht – nicht für produktive Mehrbenutzer-
Szenarien.

## Start

```java
WebWorkbenchServer server = new WebWorkbenchServer(
    "127.0.0.1", 8090,
    graphStore,
    inventoryRepository,
    exportService
);
server.start();
// Browser → http://127.0.0.1:8090/
```

## Oberfläche

Die UI ist in Tabs gegliedert:

### Workbench
* **Ausdruck**: beliebige Eingabe in der unterstützten Syntax
  (siehe [`functions.md`](functions.md)).
* **Typ**: `Term` oder `Gleichung`.
* **Profil**: wählt eine voreingestellte `SearchProfile`.
* **Regel-Domänen**: Checkboxen für `core`, `polynomial`, `rational`,
  `trigonometric`, `logarithmic`, `radical`, `calculus_basic`.
  Werden mit dem Such-Request mitgeschickt.
* **Beispiele**: One-Click-Buttons befüllen das Formular mit
  `sin²+cos²`, `log(a·b)`, `2x+3 = 7`.

### Pfade
Zeigt die gefundenen Rechenwege; Klick auf einen Pfad ruft
`/api/explain/{id}?form=SCHOOL|LATEX|STEPS` auf und stellt das Ergebnis
dar (LaTeX-Quelltext lässt sich z.B. in Obsidian oder Notion einfügen).

### Graph
Lädt den Mermaid-Graph aller entdeckten Transformationen
(`/api/graph`). Quelltext kann nach `mermaid.live` kopiert werden.

### Regelkandidaten
Zeigt jeden Kandidaten mit Pattern, Beispiel-Anzahl,
Verbesserungs-Score, *Status* und *Beweisstatus*. Bekannte Domain-
Assumptions (`a > 0`, `cos(x) ≠ 0`, …) werden farblich markiert
eingeblendet.

### Inventar
Listet die wiederverwendbaren Regeln. Pro Regel werden Tags angezeigt
und ein „Aktivieren/Deaktivieren"-Toggle sowie ein „Tag hinzufügen"-
Eingabefeld eingeblendet. Die Änderung ist lokal (UI-Zustand); die
Persistenz erfolgt über `RuleInventoryRepository#setEnabled` /
`addTag`, sobald die zugehörigen REST-Endpunkte aktiviert sind.

### Exporte
One-Click-Downloads für Markdown, LaTeX, Mermaid und JSON-Bundle.

### Hilfe
Schnellreferenz für Syntax und Workflow.

## Sicherheit

* Der Server bindet standardmäßig auf `127.0.0.1`. Erst durch explizites
  Setzen einer öffentlichen IP wird er erreichbar.
* HTTPS sowie HTTP-Basic-Auth lassen sich über `WebSecurityConfig`
  aktivieren.
* Für interaktive Nutzung im Browser sollten Cookies, Tokens oder
  Reverse-Proxies vor dem Server geschaltet werden, falls über
  `127.0.0.1` hinaus exponiert.

## Bekannte Grenzen

* Die UI ist absichtlich ohne externe Frameworks (kein React, kein
  Tailwind) gehalten — alle Assets liegen unter
  `app/src/main/resources/web/` und werden als Static-Resources
  ausgeliefert.
* Job-Lifecycle-Endpunkte (`/api/jobs/{id}/start|pause|resume|cancel`)
  sind nicht in der Default-Konfiguration aktiviert; die UI bereitet
  die entsprechenden Bedienoberflächen vor, fällt aber auf Polling
  zurück, sobald die Endpunkte verfügbar gemacht werden.
* LaTeX wird als Text dargestellt; eine eingebettete Render-Engine
  (KaTeX/MathJax) ist bewusst nicht enthalten, um keinerlei externe
  CDN-Zugriffe zu erzwingen.
