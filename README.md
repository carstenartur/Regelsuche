# Regelsuche

Gradle-basiertes Java-Projekt für regelbasierte Ausdrucksumformungen mit:

- Eingabe von Termen, Gleichungen und Gleichungssystemen
- Parsing in einen abstrakten Syntaxbaum (AST)
- SymPy-Integration über GraalVM Polyglot (mit lokalem Fallback)
- Neo4j-Graphmodell (Knoten: Ausdrücke, Kanten: Umformungen)
- Heuristischer Suchbegrenzung (Suchtiefe, besuchte Ausdrücke)
- Hintergrundausführung der Umformungssuche
- Benachrichtigung bei deutlich besseren Vereinfachungen
- Abfrage des aktuellen Graphzustands und der besten gefundenen Lösung

## Starten

```bash
./gradlew :app:run --args='term "x + 0"'
```

Optionaler Neo4j-Store per Umgebungsvariablen:

- `NEO4J_URI`
- `NEO4J_USER`
- `NEO4J_PASSWORD`
