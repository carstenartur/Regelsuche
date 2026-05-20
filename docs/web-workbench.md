# Web-Workbench

Der Web-Workbench (`de.regelsuche.web.WebWorkbenchServer`) verwendet den im JDK eingebauten `com.sun.net.httpserver.HttpServer` und bringt damit keinerlei zusätzliche Web-Framework-Abhängigkeit ins Projekt. Start per CLI:

```
./gradlew run --args="serve --port 8080 --host 127.0.0.1"
```

## REST-Endpoints

| Methode | Pfad | Beschreibung |
| --- | --- | --- |
| `POST` | `/api/search` | Body `{ "expression": "...", "type": "TERM\|EQUATION", "profile": "FAST_SIMPLIFY\|..." }` startet eine Suche. |
| `POST` | `/api/discover` | Body `{ "min": 1, "max": 3 }` startet eine Rule-Discovery. |
| `GET`  | `/api/paths`            | Liste aller entdeckten Umformungen sortiert nach Verbesserung. |
| `GET`  | `/api/paths/{id}`       | Einzelpfad mit allen Schritten. |
| `GET`  | `/api/graph`            | Mermaid-Graph aller Pfade. |
| `GET`  | `/api/graph/{id}`       | Mermaid-Graph eines einzelnen Pfads. |
| `GET`  | `/api/candidates`       | Aktuelle `RuleCandidate`-Liste. |
| `GET`  | `/api/inventory`        | Inventar inkl. `enabled` & Tags. |
| `POST` | `/api/inventory`        | Body `{ "json": "<bundle>" }` importiert ein Bundle. |
| `GET`  | `/api/exports`          | Komplettes JSON-Bundle. |
| `GET`  | `/api/exports/{format}` | Markdown, LaTeX, Mermaid oder JSON. |
| `GET`  | `/api/explain/{id}?form=SHORT\|SCHOOL\|EXPERT\|LATEX\|JSON` | Erklärung für einen Pfad. |

## Statische UI

Die HTML-Oberfläche unter `/` lädt `style.css` und `app.js` aus dem Classpath (`/web/`-Ressourcen) und ermöglicht das Auslösen einer Suche, das Listen von Pfaden/Inventar sowie direkte Export-Downloads.
