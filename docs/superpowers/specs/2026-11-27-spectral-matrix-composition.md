# Geprüfte spektrale, Matrix- und zusammengesetzte Pläne

## Ziel und Auftrag

Die aus den OpenAI-Math-Papieren abgeleiteten Erweiterungen sollen als
ausführbare, unabhängig geprüfte Regelsuche-Verfahren nutzbar werden.
Der Implementierungsauftrag umfasst drei zusammenhängende Fähigkeiten.

1. Rationale nichtkommutative Formelvergleiche an gemeinsamen Matrixzentren,
   einschließlich einer begrenzten generischen Zentrumssuche.
2. Exakte Batch-Reparatur rationaler Involutionen mit frequenzabhängigen
   Ausnahmeindizes und tatsächlich ausgeführten komprimierten Prüfreihen.
3. Zusammensetzung bilinearer U/V/W-Pläne mit rekursiv optimierten linearen
   Stufen, gemeinsamer Ressourcenabrechnung und unabhängiger Gesamtprüfung.

## Verträge

- Jede Aussage bindet die ursprüngliche Quelle und ihren Definitionsbereich.
- Ein Prüfer rekonstruiert die erforderliche Identität unabhängig vom Erzeuger.
- Arbeit, logische Speicherplätze, Zeit und Abbruch gelten für den gesamten
  Aufruf; fehlgeschlagene Unterversuche werden bezahlt.
- Vorbereitete Programme führen den geprüften Plan aus. Eine direkte
  Vergleichsrechnung ersetzt keine behauptete spektrale Ausführung.
- Kosten enthalten alle Vor-, Zwischen- und Nachoperationen und werden mit
  derselben sparsamen direkten Rechnung verglichen. Gleichstand behält den
  bisherigen Plan. Modellkosten sind keine JVM-Laufzeitmessung.
- Begrenzte Suche liefert keine globale Optimalität und keine universelle
  Trefferliste. Ein Abbruch liefert keine mathematische Entscheidung.

## Integration und Nachweise

Die mathematischen Kerne liegen in `regelsuche-math-algorithms`, typisierte
Adapter in `regelsuche-optimization-sdk`. Bestehende Skalarvergleiche bleiben
kompatibel. Neue externe Java-25-Beispiele zeigen alle drei Einstiegspfade.
Die [Fachseite](../../research/spectral-matrix-composition.md) hält Beweis,
Vergleiche, Grenzen und Reproduktion fest; der Strategien-Index wird im
gleichen Änderungsstand gepflegt.
