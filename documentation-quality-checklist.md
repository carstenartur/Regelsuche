# Documentation Quality Checklist

Diese Checkliste gilt für alle nutzerseitigen Texte: Demo-Gallery,
Web-Workbench-UI, Replay-Karten, Summary-Panels und Export-Berichte.
Sie ergänzt die in der Demo-Gallery angewandten Strukturregeln um
ein einheitliches Review-Raster.

## Inhaltliche Kriterien

- [ ] **Lokaler Bezug** — Jede Information, die im Kontext einer Demo
      angezeigt wird, hat einen klaren lokalen Bezug zu dieser Demo.
      Globale, demo-übergreifende Inhalte stehen ausschließlich in
      *Suchgedächtnis → Universelle Muster* und sind dort als solche
      gekennzeichnet.
- [ ] **Klare Trennung global ↔ lokal** — In Demo-Kontexten heißt der
      Regel-Abschnitt „Für diese Demo verwendete Regeln“ bzw.
      „Relevante Regeln dieses Rechenwegs“, nicht „Erkannte
      Identitäten“ und nicht „Inventar“.
- [ ] **Relevanzfilter aktiv** — Es werden nur Regeln gezeigt, die
      tatsächlich im Replay verwendet wurden, in einem alternativen
      Pfad derselben Suche vorkommen, als Makroregel für diese Demo
      aktiviert wurden oder für den Proof relevant sind.
- [ ] **Eingabe ≠ Ergebnis** — Eingabe und Ergebnis sind in jeder
      Demo getrennt dargestellt; der Rechenweg liegt sichtbar
      dazwischen.
- [ ] **Annahmen sichtbar** — Voraussetzungen (z. B. `x ≠ 0`) sind
      sichtbar erklärt, bevor das Ergebnis steht.
- [ ] **Proof-Status verständlich** — Der Proof-Status nutzt
      verständliche Bezeichnungen statt interner Konstantennamen,
      und der Unterschied zwischen *echtem Prover* und
      *E2E-Test-Prover* ist klar benannt, wo relevant.

## API- und GUI-Dokumentation

- [ ] **Swagger/OpenAPI ist die Vertragsquelle** — HTTP-Methoden, Pfade,
      Parameter, Payloads, Responses und Statuscodes werden nicht in
      Nutzerseiten parallel gepflegt.
- [ ] **GUI zuerst** — Eine nutzerbezogene Funktionsbeschreibung beginnt
      mit Tab, Panel oder Schaltfläche und erklärt den sichtbaren Ablauf,
      nicht den auslösenden Request.
- [ ] **Sichtbares Ergebnis beschrieben** — Die Dokumentation sagt, was
      nach der Aktion in der Oberfläche erscheint und wie damit
      weitergearbeitet wird.
- [ ] **Zustände beschrieben** — Bereit-, Lade-, Leer-, Erfolgs- und
      Fehlerzustände sind für den jeweiligen Bedienfluss nachvollziehbar.
- [ ] **Keine Endpoint-Kataloge** — Nutzerseiten enthalten keine Tabellen
      oder Listen, die Swagger durch eine Auswahl von REST-Endpunkten
      ersetzen.
- [ ] **Keine rohen Request-Beispiele als Nutzerweg** — `curl`, rohe
      HTTP-Blöcke und JSON-Payloads stehen nur in ausdrücklich
      programmatischen Integrationsleitfäden.
- [ ] **Technische Zuordnung bleibt knapp** — Falls hilfreich, nennt die
      Nutzerseite nur den Swagger-Tag oder die `operationId`; sie kopiert
      den Vertrag nicht.
- [ ] **GUI-Lücke wird als Produktlücke behandelt** — Existiert für eine
      öffentlich nutzbare REST-Funktion keine grafische Bedienmöglichkeit,
      wird nicht ersatzweise mehr REST-Prosa geschrieben. Stattdessen wird
      die fehlende GUI-Funktion als eigener Produktpunkt dokumentiert.

## Sprachliche Kriterien

- [ ] **Keine internen Klassennamen** — Texte für Nutzerinnen und
      Nutzer enthalten keine internen Klassennamen, Service-Namen,
      CSS-Klassen oder Job-Worker-Namen.
      *Beispiele, die vermieden werden:* `MacroRuleLearningService`,
      `StubAlwaysSucceedsWorker`, `replay-flip-notice`.
      *Gewählt wird stattdessen* eine kurze, lesbare Umschreibung,
      z. B. „Eine Makroregel wurde aus mehreren Beispielen gelernt“,
      „Der Proof-Test nutzt im E2E-Modus einen deterministischen
      Test-Prover“, „Der kritische Schritt wird im Replay hervorgehoben“.
- [ ] **Tooltips / Glossar für Fachbegriffe** — Begriffe wie
      *Makroregel*, *Suchgraph*, *Replay*, *Proof-Status*,
      *Universelle Muster*, *Equality Saturation*, *Suchgedächtnis*
      sind im Glossar erklärt und werden in der UI als Tooltip oder
      Info-Text angeboten.
- [ ] **Einheitliche mathematische Notation** — Pro Feld wird genau
      eine Form verwendet: Anzeigeform in Unicode/LaTeX
      (z. B. `(x + 3)²`, `x²`), technische Eingabe im Codeblock
      (z. B. `(x+3)^2`). Keine Mischung von `·` und `*` im selben
      Feld; keine Mischung von `x²` und `x^2` im selben Feld.

## Visuelle Kriterien

- [ ] **Screenshots mit Bildunterschrift** — Unter jedem Screenshot
      steht ein Satz, der das Bild beschreibt, damit die Seite auch
      ohne Bild verständlich bleibt
      (z. B. „Der Screenshot zeigt den Suchgraphen mit hervorgehobenem
      besten Rechenweg.“).
- [ ] **Aussagekräftiger Alt-Text** — Bilder haben einen Alt-Text,
      der nicht nur die Bilddatei benennt, sondern beschreibt, was
      zu sehen wäre.
- [ ] **Exportformate nach Nutzen** — Das Export-Bundle wird über den
      *Nutzen* der enthaltenen Formate erklärt, nicht über eine reine
      Dateiliste (z. B. „Markdown für Dokumentation“,
      „LaTeX für mathematische Texte“, „JSON für maschinelle
      Weiterverarbeitung“).

## Strukturelle Kriterien

- [ ] **Einheitliche Demo-Struktur** — Jede Demo folgt derselben
      Reihenfolge: Kurzbeschreibung, Eingabe, Ergebnis, Rechenweg,
      Verwendete Regeln, Annahmen, Proof-Status, Export. Leere
      Abschnitte werden weggelassen.
- [ ] **Produktstory pro Demo** — Jede Demo enthält einen Abschnitt
      „Warum ist das interessant?“, der die Demo in 1–3 Sätzen aus
      Nutzersicht einordnet.
- [ ] **Demo-Gallery als geführte Tour** — Die Gallery beginnt mit
      einer empfohlenen Tour und ist als Produktdemo lesbar, nicht
      als Asset-Liste.
- [ ] **Geprüfte Links und Asset-Pfade** — Alle Links innerhalb der
      Dokumentation und alle Screenshot-Pfade sind erreichbar; tote
      Links und fehlende Assets gelten als Fehler.
- [ ] **GUI und Browser-E2E gemeinsam aktualisiert** — Ändert sich ein
      sichtbarer Bedienfluss, werden Dokumentation, Screenshot und
      zugehöriger Browserflow zusammen angepasst.

## Akzeptanzkriterium

Die Demo-Gallery, die UI und ihre Nutzerdokumentation gelten als bereinigt, wenn:

1. keine fremden oder globalen Identitäten mehr unter lokalen Demos erscheinen,
2. jede angezeigte Regel begründet lokal relevant ist,
3. jede Demo Eingabe, Ergebnis, Rechenweg und Annahmen klar trennt,
4. Proof-Informationen nicht missverständlich sind,
5. keine internen Klassennamen in Nutzertexten auftauchen,
6. die Demo-Gallery wie eine geführte Produktdemo wirkt,
7. Nutzerseiten GUI-Abläufe statt REST-Verträge dokumentieren,
8. Swagger/OpenAPI die einzige parallel gepflegte REST-Referenz bleibt.

> Der wichtigste Punkt ist: **Alles, was im Demo-Kontext angezeigt
> wird, muss lokal erklärbar sein — und alles, was eine Nutzeraktion
> beschreibt, muss in der sichtbaren Oberfläche auffindbar sein.**
